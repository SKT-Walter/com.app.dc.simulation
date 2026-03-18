package com.app.dc.service.simulation;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BinanceBacktestParam;
import com.app.dc.service.simulation.BinanceBacktestModels.BacktestResponse;
import com.app.dc.service.simulation.BinanceBacktestModels.BacktestResult;
import com.app.dc.service.simulation.BinanceBacktestModels.EquityContext;
import com.app.dc.service.simulation.BinanceBacktestModels.Position;
import com.app.dc.service.simulation.BinanceBacktestModels.TradeRecord;
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
import java.util.List;

/**
 * 币安策略回测门面服务。
 * 负责协调查询、策略评估、撮合与结果统计。
 */
@Service
public class BinanceBacktestService {

    @Autowired
    private BinanceBacktestQueryService queryService;

    @Autowired
    private BinanceBacktestSupportService supportService;

    @Autowired
    private BinanceBacktestStrategyService strategyService;

    @Autowired
    private BinanceBacktestTradeService tradeService;

    @Autowired
    private BinanceBacktestMetricService metricService;

    @Autowired
    private BinanceBacktestMarketGuard marketGuard;

    /**
     * 执行回测。
     */
    public BacktestResponse run(BinanceBacktestParam param) throws Exception {
        BinanceBacktestParam req = normalizeParam(param);
        List<TTbookOhlc> ohlcList = queryService.queryOhlc(req.symbol, req.text, req.beginDate, req.endDate);

        BacktestResponse response = new BacktestResponse();
        response.symbol = req.symbol;
        response.text = req.text;
        response.beginDate = req.beginDate;
        response.endDate = req.endDate;
        response.strategyName = req.strategyName;

        if (ohlcList.isEmpty()) {
            response.results = Collections.emptyList();
            return response;
        }

        List<BacktestResult> results = new ArrayList<>();
        if ("all".equalsIgnoreCase(req.strategyName)) {
            results.add(runSingleStrategy("binanceRange", req, ohlcList));
            results.add(runSingleStrategy("binanceChannel", req, ohlcList));
            results.add(runSingleStrategy("binanceTrend", req, ohlcList));
        } else {
            results.add(runSingleStrategy(req.strategyName, req, ohlcList));
        }
        response.results = results;
        return response;
    }

    /**
     * 执行单一策略回测。
     */
    public BacktestResult runSingleStrategy(String strategyName, BinanceBacktestParam param,
                                            List<TTbookOhlc> ohlcList) throws Exception {
        String normalizedStrategy = supportService.normalizeStrategyName(strategyName);
        Duration duration = supportService.resolveDuration(param.text);
        BarSeries replaySeries = new BaseBarSeries(param.symbol + "-" + param.text + "-" + normalizedStrategy);

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

            Signal signal = strategyService.evaluateSignal(normalizedStrategy, param.symbol, param.text, replaySeries, ohlc);
            if (signal.side == null) {
                continue;
            }
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
        return result;
    }

    /**
     * 归一化回测请求，补足默认值。
     */
    public BinanceBacktestParam normalizeParam(BinanceBacktestParam param) {
        BinanceBacktestParam req = param == null ? new BinanceBacktestParam() : param;
        if (req.symbol == null || req.symbol.trim().isEmpty()) {
            req.symbol = "ETHUSDT";
        }
        if (req.text == null || req.text.trim().isEmpty()) {
            req.text = "15m";
        }
        req.text = supportService.normalizeText(req.text);
        if (req.strategyName == null || req.strategyName.trim().isEmpty()) {
            req.strategyName = "all";
        }
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
        return req;
    }

    /**
     * 初始化单策略回测结果。
     */
    public BacktestResult initResult(String strategyName, BinanceBacktestParam param) {
        BacktestResult result = new BacktestResult();
        result.strategyName = strategyName;
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
        result.tradeList = new ArrayList<>();
        return result;
    }

    /**
     * 将实体 K 线转换为 ta4j Bar。
     */
    public Bar toBar(TTbookOhlc ohlc, Duration duration) throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        ZonedDateTime time = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(sdf.parse(ohlc.starttime).getTime()), ZoneId.systemDefault());
        return new BaseBar(duration, time, ohlc.open, ohlc.high, ohlc.low, ohlc.close, ohlc.volume);
    }

    /**
     * 向序列中加入新 bar，若时间重复则覆盖。
     */
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

    /**
     * 统一保留小数位。
     */
    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
