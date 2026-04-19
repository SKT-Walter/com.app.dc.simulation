package com.app.dc.service.simulation.runtime;

import com.app.dc.po.Signal;
import com.app.dc.po.Side;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.signal.MarketSeriesRegistry;
import com.app.dc.signal.StrategyDefinition;
import com.app.dc.service.simulation.BacktestMetricService;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestService;
import com.app.dc.service.simulation.BacktestSupportService;
import com.app.dc.service.simulation.BacktestTradeService;
import com.app.dc.service.simulation.strategy.BinanceBacktestMarketGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;

@Service
@Slf4j
public class VersionedBacktestRunner {

    @Autowired
    private BacktestService legacyBacktestService;

    @Autowired
    private BacktestSupportService supportService;

    @Autowired
    private BacktestTradeService tradeService;

    @Autowired
    private BacktestMetricService metricService;

    @Autowired
    private BinanceBacktestMarketGuard marketGuard;

    @Autowired
    private com.app.dc.signal.BuySellSignalFacade runtimeFacade;

    public BacktestModels.BacktestResult run(StrategyCandidateRow candidate, BacktestParam rawParam,
                                             List<TTbookOhlc> ohlcList) throws Exception {
        BacktestParam param = legacyBacktestService.normalizeParam(rawParam);
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is null");
        }
        log.info("VersionedBacktestRunner start, strategy:{}@{}, symbol:{}, text:{}, bars:{}, beginDate:{}, endDate:{}, runtimeType:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                param.symbol,
                param.text,
                ohlcList == null ? 0 : ohlcList.size(),
                param.beginDate,
                param.endDate,
                candidate.runtimeType);

        StrategyDefinition definition = candidate.toDefinition(param.strategyParams);
        runtimeFacade.load(definition);

        Duration duration = supportService.resolveDuration(param.text);
        MarketSeriesRegistry marketSeriesRegistry = new MarketSeriesRegistry();
        BarSeries replaySeries = null;
        BacktestModels.BacktestResult result = legacyBacktestService.initResult(candidate.strategyName, param);
        result.strategyVersion = candidate.strategyVersion;
        result.runtimeType = candidate.runtimeType;
        result.scene = candidate.scene;
        result.strategyPayload = candidate.payload;
        BacktestModels.EquityContext equityContext = metricService.initEquityContext(param.initialCapital.doubleValue());
        BacktestModels.Position position = null;

        for (TTbookOhlc ohlc : ohlcList) {
            Bar bar = legacyBacktestService.toBar(ohlc, duration);
            marketSeriesRegistry.appendBar(param.text, param.symbol, bar);
            replaySeries = marketSeriesRegistry.getSeries(param.text, param.symbol);
            if (replaySeries == null) {
                continue;
            }
            result.totalBars = replaySeries.getBarCount();

            if (position != null && position.entryIndex < replaySeries.getEndIndex()) {
                BacktestModels.TradeRecord riskClosed = tradeService.tryCloseByRisk(position, bar,
                        replaySeries.getEndIndex(),
                        param.entryMakerFeeRatePct.doubleValue(),
                        param.exitTakerFeeRatePct.doubleValue());
                if (riskClosed != null) {
                    metricService.applyTrade(result, riskClosed, equityContext);
                    position = null;
                }
            }

            com.app.dc.signal.SignalContext signalContext = com.app.dc.signal.SignalContext.backtest(
                    param.symbol, param.text, ohlc, replaySeries);
            signalContext.strategyName = candidate.strategyName;
            signalContext.strategyVersion = candidate.strategyVersion;
            signalContext.scene = candidate.scene;
            signalContext.indicatorSymbol = marketSeriesRegistry.getIndicatorSymbol(param.text);
            signalContext.marketSeriesRegistry = marketSeriesRegistry;
            signalContext.parameters.putAll(definition.parameters);

            Signal signal = runtimeFacade.evaluate(candidate.strategyName, candidate.strategyVersion, signalContext);
            if (signal == null || signal.side == null || signal.side == Side.NONE) {
                continue;
            }
            signal.strategyName = candidate.strategyName;
            signal.strategyVersion = candidate.strategyVersion;
            signal.scene = candidate.scene;
            signal.algoName = candidate.strategyName;
            if (!hasDynamicRiskTargets(signal)) {
                incrementRejectReason(result, "missing_dynamic_stop_take");
                continue;
            }

            if (position == null) {
                position = tradeService.openPosition(signal, replaySeries.getEndIndex(), bar, param, equityContext.equity);
                continue;
            }
            if (tradeService.isOpposite(position.side, signal.side)) {
                BacktestModels.TradeRecord reversed = tradeService.closePosition(position, bar.getClosePrice().doubleValue(),
                        bar.getEndTime().toString(), "reverse_signal", replaySeries.getEndIndex(),
                        param.entryMakerFeeRatePct.doubleValue(),
                        param.exitTakerFeeRatePct.doubleValue());
                metricService.applyTrade(result, reversed, equityContext);
                position = tradeService.openPosition(signal, replaySeries.getEndIndex(), bar, param, equityContext.equity);
            }
        }

        if (position != null) {
            Bar lastBar = replaySeries.getLastBar();
            BacktestModels.TradeRecord ended = tradeService.closePosition(position, lastBar.getClosePrice().doubleValue(),
                    lastBar.getEndTime().toString(), "end_of_test", replaySeries.getEndIndex(),
                    param.entryMakerFeeRatePct.doubleValue(),
                    param.exitTakerFeeRatePct.doubleValue());
            metricService.applyTrade(result, ended, equityContext);
        }

        metricService.finishResult(result, equityContext);
        log.info("VersionedBacktestRunner end, strategy:{}@{}, symbol:{}, trades:{}, winCount:{}, lossCount:{}, totalPnl:{}, maxDrawdownPct:{}, rejectReasons:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                param.symbol,
                result.tradeCount,
                result.winCount,
                result.lossCount,
                result.totalPnl,
                result.maxDrawdownPct,
                result.rejectReasonCounts);
        return result;
    }

    private boolean hasDynamicRiskTargets(Signal signal) {
        return signal != null
                && signal.stopPrice != null
                && signal.stopPrice.compareTo(java.math.BigDecimal.ZERO) > 0
                && signal.takerPrice != null
                && signal.takerPrice.compareTo(java.math.BigDecimal.ZERO) > 0;
    }

    private void incrementRejectReason(BacktestModels.BacktestResult result, String reason) {
        if (result == null || reason == null || reason.trim().isEmpty()) {
            return;
        }
        if (result.rejectReasonCounts == null) {
            result.rejectReasonCounts = new LinkedHashMap<String, Integer>();
        }
        Integer old = result.rejectReasonCounts.get(reason);
        result.rejectReasonCounts.put(reason, Integer.valueOf((old == null ? 0 : old.intValue()) + 1));
    }
}
