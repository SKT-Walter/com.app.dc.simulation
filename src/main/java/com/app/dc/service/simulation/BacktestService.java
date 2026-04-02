package com.app.dc.service.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels.BacktestResponse;
import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.EquityContext;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskDao;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import com.app.dc.service.simulation.runtime.VersionedBacktestRunner;
import com.app.dc.service.simulation.strategy.BinanceBacktestMarketGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class BacktestService {

    @Autowired
    private BacktestQueryService queryService;

    @Autowired
    private BacktestSupportService supportService;

    @Autowired
    private BacktestStrategyService strategyService;

    @Autowired
    private BacktestTradeService tradeService;

    @Autowired
    private BacktestMetricService metricService;

    @Autowired
    private BinanceBacktestMarketGuard marketGuard;

    @Autowired
    private StrategyBacktestTaskDao strategyBacktestTaskDao;

    @Autowired
    private VersionedBacktestRunner versionedBacktestRunner;

    public BacktestResponse run(BacktestParam param) throws Exception {
        BacktestParam req = normalizeParam(param);
        if (req.strategyName == null || req.strategyName.trim().isEmpty()) {
            throw new IllegalArgumentException("strategyName is required");
        }
        if (req.strategyVersion == null || req.strategyVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("strategyVersion is required");
        }
        StrategyCandidateRow candidate = strategyBacktestTaskDao.loadCandidate(req.strategyName, req.strategyVersion);
        if (candidate == null) {
            throw new IllegalArgumentException("strategy candidate not found: " + req.strategyName + "@" + req.strategyVersion);
        }
        req.runtimeType = candidate.runtimeType;
        req.scene = candidate.scene;
        req.strategyPayload = candidate.payload;

        List<String> symbols = supportService.resolveSymbols(req.symbols, req.symbol);

        BacktestResponse response = new BacktestResponse();
        response.symbol = symbols.size() == 1 ? symbols.get(0) : "MULTI";
        response.symbols = symbols;
        response.text = req.text;
        response.beginDate = req.beginDate;
        response.endDate = req.endDate;
        response.strategyName = candidate.strategyName;
        response.strategyVersion = candidate.strategyVersion;
        response.baselineVersion = req.baselineVersion;
        response.runtimeType = candidate.runtimeType;
        response.scene = candidate.scene;

        List<BacktestResult> results = new ArrayList<BacktestResult>();
        for (String symbol : symbols) {
            List<TTbookOhlc> ohlcList = queryService.queryOhlc(symbol, req.text, req.beginDate, req.endDate);
            if (ohlcList.isEmpty()) {
                continue;
            }
            BacktestParam symbolParam = copyParamForSymbol(req, symbol);
            results.add(versionedBacktestRunner.run(candidate, symbolParam, ohlcList));
        }
        response.results = results.isEmpty() ? Collections.<BacktestResult>emptyList() : results;
        return response;
    }

    private BacktestParam copyParamForSymbol(BacktestParam source, String symbol) {
        BacktestParam target = new BacktestParam();
        target.strategyName = source.strategyName;
        target.strategyVersion = source.strategyVersion;
        target.baselineVersion = source.baselineVersion;
        target.runtimeType = source.runtimeType;
        target.scene = source.scene;
        target.strategyPayload = source.strategyPayload;
        target.symbol = symbol;
        target.symbols = symbol;
        target.text = source.text;
        target.beginDate = source.beginDate;
        target.endDate = source.endDate;
        target.initialCapital = source.initialCapital;
        target.feeRatePct = source.feeRatePct;
        target.fallbackStopLossPct = source.fallbackStopLossPct;
        target.fallbackTakeProfitPct = source.fallbackTakeProfitPct;
        target.maxHoldBars = source.maxHoldBars;
        target.ignoreSentimentGuard = source.ignoreSentimentGuard;
        return target;
    }

    public BacktestResult runSingleStrategy(String strategyName, BacktestParam param,
                                            List<TTbookOhlc> ohlcList) throws Exception {
        String normalizedStrategy = supportService.normalizeStrategyName(strategyName);
        Duration duration = supportService.resolveDuration(param.text);
        BarSeries replaySeries = new BaseBarSeries(param.symbol + "-" + param.text + "-" + normalizedStrategy);
        strategyService.getStrategy(normalizedStrategy).resetRejectStats(param.symbol);

        BacktestResult result = initResult(normalizedStrategy, param);
        EquityContext equityContext = metricService.initEquityContext(param.initialCapital.doubleValue());
        BinanceBacktestMarketGuard.GuardContext guardContext =
                marketGuard.prepareContext(param.symbol, param.beginDate, param.endDate);
        Position position = null;

        for (TTbookOhlc ohlc : ohlcList) {
            Bar bar = toBar(ohlc, duration);
            addBar(replaySeries, bar);
            result.totalBars = replaySeries.getBarCount();

            if (position != null && position.entryIndex < replaySeries.getEndIndex()) {
                TradeRecord riskClosed = tradeService.tryCloseByRisk(position, bar, replaySeries.getEndIndex(),
                        param.feeRatePct.doubleValue());
                if (riskClosed != null) {
                    metricService.applyTrade(result, riskClosed, equityContext);
                    position = null;
                }
            }

            com.app.dc.po.Signal signal = strategyService.evaluateSignal(normalizedStrategy, param.symbol, param.text, replaySeries, ohlc);
            if (signal.side == null || signal.side == Side.NONE) {
                continue;
            }
            signal.strategyName = normalizedStrategy;
            signal.algoName = signal.strategyName;
            signal.strategyVersion = param.strategyVersion;
            signal.scene = param.scene;
            signal.strategyPayload = param.strategyPayload;
            boolean ignoreSentimentGuard = Boolean.TRUE.equals(param.ignoreSentimentGuard);
            if (marketGuard.shouldBlock(normalizedStrategy, guardContext, bar.getEndTime().toInstant(), ignoreSentimentGuard)) {
                continue;
            }

            if (position == null) {
                position = tradeService.openPosition(signal, replaySeries.getEndIndex(), bar, param);
                continue;
            }

            if (tradeService.isOpposite(position.side, signal.side)) {
                TradeRecord reversed = tradeService.closePosition(position, bar.getClosePrice().doubleValue(),
                        bar.getEndTime().toString(), "reverse_signal", replaySeries.getEndIndex(),
                        param.feeRatePct.doubleValue());
                metricService.applyTrade(result, reversed, equityContext);
                position = tradeService.openPosition(signal, replaySeries.getEndIndex(), bar, param);
            }
        }

        if (position != null) {
            Bar lastBar = replaySeries.getLastBar();
            TradeRecord ended = tradeService.closePosition(position, lastBar.getClosePrice().doubleValue(),
                    lastBar.getEndTime().toString(), "end_of_test", replaySeries.getEndIndex(),
                    param.feeRatePct.doubleValue());
            metricService.applyTrade(result, ended, equityContext);
        }

        metricService.finishResult(result, equityContext);
        result.rejectReasonCounts = new LinkedHashMap<String, Integer>(
                strategyService.getStrategy(normalizedStrategy).snapshotRejectStats(param.symbol));
        return result;
    }

    public BacktestParam normalizeParam(BacktestParam param) {
        BacktestParam req = param == null ? new BacktestParam() : param;
        if (req.symbol == null || req.symbol.trim().isEmpty()) {
            req.symbol = "ETHUSDT";
        }
        if (req.symbols == null || req.symbols.trim().isEmpty()) {
            req.symbols = req.symbol;
        }
        if (req.text == null || req.text.trim().isEmpty()) {
            req.text = "15m";
        }
        req.text = supportService.normalizeText(req.text);
        if (req.initialCapital == null || req.initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            req.initialCapital = BigDecimal.valueOf(10000);
        }
        if (req.feeRatePct == null || req.feeRatePct.compareTo(BigDecimal.ZERO) < 0) {
            req.feeRatePct = BigDecimal.ZERO;
        }
        if (req.fallbackStopLossPct == null || req.fallbackStopLossPct.compareTo(BigDecimal.ZERO) < 0) {
            req.fallbackStopLossPct = BigDecimal.ZERO;
        }
        if (req.fallbackTakeProfitPct == null || req.fallbackTakeProfitPct.compareTo(BigDecimal.ZERO) < 0) {
            req.fallbackTakeProfitPct = BigDecimal.ZERO;
        }
        if (req.maxHoldBars == null || req.maxHoldBars < 0) {
            req.maxHoldBars = 0;
        }
        if (req.ignoreSentimentGuard == null) {
            req.ignoreSentimentGuard = true;
        }
        if (req.runtimeType == null || req.runtimeType.trim().isEmpty()) {
            req.runtimeType = "JAR";
        }
        return req;
    }

    public BacktestResult initResult(String strategyName, BacktestParam param) {
        BacktestResult result = new BacktestResult();
        result.strategyName = strategyName;
        result.strategyVersion = param.strategyVersion;
        result.baselineVersion = param.baselineVersion;
        result.runtimeType = param.runtimeType;
        result.scene = param.scene;
        result.strategyPayload = param.strategyPayload;
        result.symbol = param.symbol;
        result.text = param.text;
        result.beginDate = param.beginDate;
        result.endDate = param.endDate;
        result.initialCapital = scale(param.initialCapital.doubleValue());
        result.finalCapital = scale(param.initialCapital.doubleValue());
        result.feeRatePct = scale(param.feeRatePct.doubleValue());
        result.fallbackStopLossPct = scale(param.fallbackStopLossPct.doubleValue());
        result.fallbackTakeProfitPct = scale(param.fallbackTakeProfitPct.doubleValue());
        result.maxHoldBars = param.maxHoldBars;
        result.tradeList = new ArrayList<TradeRecord>();
        return result;
    }

    public Bar toBar(TTbookOhlc ohlc, Duration duration) throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        ZonedDateTime time = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(sdf.parse(ohlc.starttime).getTime()), ZoneId.systemDefault());
        return new BaseBar(duration, time, ohlc.open, ohlc.high, ohlc.low, ohlc.close, ohlc.volume);
    }

    public void addBar(BarSeries series, Bar newBar) {
        boolean replace = false;
        if (series.getBarCount() > 0) {
            ZonedDateTime lastBarTime = series.getLastBar().getEndTime();
            if (newBar.getEndTime().equals(lastBarTime)) {
                replace = true;
            } else if (newBar.getEndTime().isBefore(lastBarTime)) {
                return;
            }
        }
        series.addBar(newBar, replace);
    }

    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
