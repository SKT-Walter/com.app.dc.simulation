package com.app.dc.service.simulation;

import com.app.dc.po.OCType;
import com.app.dc.po.Signal;
import com.app.dc.po.Side;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels.BacktestResponse;
import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.EquityContext;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestMarketGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class BacktestService {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DB_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();

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

    public BacktestResponse run(BacktestParam param) throws Exception {
        BacktestParam req = normalizeParam(param);
        List<String> symbols = supportService.resolveSymbols(req.symbols, req.symbol);

        BacktestResponse response = new BacktestResponse();
        response.symbol = symbols.size() == 1 ? symbols.get(0) : "MULTI";
        response.symbols = symbols;
        response.text = req.text;
        response.beginDate = req.beginDate;
        response.endDate = req.endDate;
        response.strategyName = req.strategyName;

        List<BacktestResult> results = new ArrayList<>();
        for (String symbol : symbols) {
            List<TTbookOhlc> ohlcList = queryService.queryOhlc(symbol, req.text, req.beginDate, req.endDate);
            if (ohlcList.isEmpty()) {
                continue;
            }
            BacktestParam symbolParam = copyParamForSymbol(req, symbol);
            if ("all".equalsIgnoreCase(req.strategyName)) {
                results.add(runSingleStrategy("binanceRange", symbolParam, ohlcList));
                results.add(runSingleStrategy("binanceRangeGuarded", symbolParam, ohlcList));
                results.add(runSingleStrategy("binanceRangeMacd", symbolParam, ohlcList));
                results.add(runSingleStrategy("binanceChannel", symbolParam, ohlcList));
                results.add(runSingleStrategy("binanceTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("difDeaLifecycle", symbolParam, ohlcList));
                results.add(runSingleStrategy("breakoutRetestContinuationTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("emaPullbackBuy", symbolParam, ohlcList));
                results.add(runSingleStrategy("trendRestart", symbolParam, ohlcList));
                results.add(runSingleStrategy("smallRangeBreakout", symbolParam, ohlcList));
                results.add(runSingleStrategy("strongMomentumContinuation", symbolParam, ohlcList));
                results.add(runSingleStrategy("trendPullbackRecovery", symbolParam, ohlcList));
                results.add(runSingleStrategy("bollingerMeanReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("bollingerPullbackBias", symbolParam, ohlcList));
                results.add(runSingleStrategy("breakoutRetestContinuation", symbolParam, ohlcList));
                results.add(runSingleStrategy("compressionBreak", symbolParam, ohlcList));
                results.add(runSingleStrategy("failedBreakReversal", symbolParam, ohlcList));
                results.add(runSingleStrategy("impulseReclaim", symbolParam, ohlcList));
//                results.add(runSingleStrategy("rsiKdjReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("donchianReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("vwapReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("zscoreReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("gridRange", symbolParam, ohlcList));
                results.add(runSingleStrategy("atrChannelReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("atrChannelBiasReversion", symbolParam, ohlcList));
//                results.add(runSingleStrategy("orderBookImbalanceReversion", symbolParam, ohlcList));
            } else {
                results.add(runSingleStrategy(req.strategyName, symbolParam, ohlcList));
            }
        }
        response.results = results.isEmpty() ? Collections.<BacktestResult>emptyList() : results;
        return response;
    }

    private BacktestParam copyParamForSymbol(BacktestParam source, String symbol) {
        BacktestParam target = new BacktestParam();
        target.strategyName = source.strategyName;
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

            Signal signal = strategyService.evaluateSignal(normalizedStrategy, param.symbol, param.text, replaySeries, ohlc);
            // NONE 表示无信号，不开仓也不反手。
            if (signal.side == null || signal.side == Side.NONE) {
                continue;
            }
            boolean ignoreSentimentGuard = Boolean.TRUE.equals(param.ignoreSentimentGuard);
            if (marketGuard.shouldBlock(normalizedStrategy, guardContext, bar.getEndTime().toInstant(), ignoreSentimentGuard)) {
                continue;
            }

            if (isCloseSignal(signal)) {
                if (position != null && tradeService.isOpposite(position.side, signal.side)) {
                    String exitReason = isReverseCloseSignal(signal) ? "reverse_signal" : "strategy_close_signal";
                    TradeRecord closed = tradeService.closePosition(position, bar.getClosePrice().doubleValue(),
                            bar, exitReason, replaySeries.getEndIndex(), param.feeRatePct.doubleValue());
                    metricService.applyTrade(result, closed, equityContext);
                    position = isReverseCloseSignal(signal)
                            ? tradeService.openPosition(signal, replaySeries.getEndIndex(), bar, param)
                            : null;
                }
                continue;
            }

            if (position == null) {
                position = tradeService.openPosition(signal, replaySeries.getEndIndex(), bar, param);
                continue;
            }

            if (tradeService.isOpposite(position.side, signal.side)) {
                TradeRecord reversed = tradeService.closePosition(position, bar.getClosePrice().doubleValue(),
                        bar, "reverse_signal", replaySeries.getEndIndex(), param.feeRatePct.doubleValue());
                metricService.applyTrade(result, reversed, equityContext);
                position = tradeService.openPosition(signal, replaySeries.getEndIndex(), bar, param);
            }
        }

        if (position != null) {
            Bar lastBar = replaySeries.getLastBar();
            TradeRecord ended = tradeService.closePosition(position, lastBar.getClosePrice().doubleValue(),
                    lastBar, "end_of_test", replaySeries.getEndIndex(), param.feeRatePct.doubleValue());
            metricService.applyTrade(result, ended, equityContext);
        }

        metricService.finishResult(result, equityContext);
        result.rejectReasonCounts = new LinkedHashMap<>(
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

    public BacktestResult initResult(String strategyName, BacktestParam param) {
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

    public Bar toBar(TTbookOhlc ohlc, Duration duration) throws Exception {
        String barTime = resolveBarTime(ohlc);
        ZonedDateTime endTime = parseBarTime(barTime, duration);
        return new BaseBar(duration, endTime, ohlc.open, ohlc.high, ohlc.low, ohlc.close, ohlc.volume);
    }

    /**
     * 解析数据库K线时间，并按周期归一到对应K线的开始时刻。
     */
    private ZonedDateTime parseBarTime(String barEndTime, Duration duration) {
        LocalDateTime localDateTime = LocalDateTime.parse(barEndTime.trim(), DB_TIME_FORMATTER);
        return floorToBarStart(localDateTime, duration).atZone(BEIJING_ZONE);
    }

    /**
     * 将任意K线时间向下归到周期开始点，例如08:34:59.999归到08:30:00。
     */
    private LocalDateTime floorToBarStart(LocalDateTime value, Duration duration) {
        long seconds = Math.max(1L, duration.getSeconds());
        long daySecond = value.toLocalTime().toSecondOfDay();
        long flooredSecond = daySecond / seconds * seconds;
        return value.toLocalDate().atStartOfDay().plusSeconds(flooredSecond);
    }

    /**
     * 优先使用K线结束时间构造BaseBar，缺失时回退到starttime。
     */
    /**
     * 解析K线归桶时间，优先使用starttime避免整点endtime落到下一根K线。
     */
    private String resolveBarTime(TTbookOhlc ohlc) {
        if (ohlc == null) {
            return null;
        }
        if (ohlc.starttime != null && ohlc.starttime.trim().length() > 0) {
            return ohlc.starttime.trim();
        }
        try {
            java.lang.reflect.Field endField = ohlc.getClass().getDeclaredField("endtime");
            endField.setAccessible(true);
            Object endValue = endField.get(ohlc);
            if (endValue != null && endValue.toString().trim().length() > 0) {
                return endValue.toString().trim();
            }
        } catch (Exception ignore) {
            // ignore TTbookOhlc field differences across environments
        }
        return ohlc.starttime;
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

    /**
     * 判断当前信号是否为仅用于平仓的离场信号。
     */
    private boolean isCloseSignal(Signal signal) {
        return signal != null && signal.ocType == OCType.ClOSE;
    }

    /**
     * 判断离场信号是否由反向交叉触发，若是则允许执行层平仓后反手。
     */
    private boolean isReverseCloseSignal(Signal signal) {
        if (signal == null || signal.remark == null) {
            return false;
        }
        boolean reverseCross = signal.remark.contains("reason=dif_dea_cross_down")
                || signal.remark.contains("reason=dif_dea_cross_up");
        return reverseCross && signal.remark.contains("reverseEntry=true");
    }

    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
