package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binance 增强区间震荡回测策略。
 *
 * 策略逻辑：
 * 1. 用 15m K 线近似计算 1h 漂移方向与中轴单侧停留，只屏蔽容易被慢单边磨损的一侧。
 * 2. 触边后必须出现反转确认 K 线才允许开仓，避免沿边贴着走时连续逆势入场。
 * 3. 使用策略内部虚拟持仓统计止损结果，连续两次止损后进入冷却期。
 */
@Service("binanceRangeGuarded")
public class BinanceRangeGuardedBacktestStrategy implements BinanceBacktestStrategy {

    private static final int RANGE_LOOKBACK = 20;
    private static final double RANGE_MAX_WIDTH_PCT = 0.05;
    private static final double EDGE_ZONE_PCT = 0.15;
    private static final double STOP_BUFFER_PCT_OF_RANGE = 0.10;
    private static final double TAKE_BUFFER_PCT_OF_ZONE = 0.20;

    private static final int HTF_SMA_PERIOD_1H = 20;
    private static final int BARS_PER_HOUR = 4;
    private static final double HTF_DRIFT_SLOPE_PCT = 0.0040;
    private static final int MID_STAY_LOOKBACK = 16;
    private static final double MID_STAY_RATIO = 0.85;

    private static final int COOLING_STOP_COUNT = 2;
    private static final int COOLING_BARS = 12;

    private static final double REVERSAL_RECOVER_ZONE_RATIO = 0.20;

    private final Map<String, SymbolState> stateMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> rejectStats = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "binanceRangeGuarded";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int endIndex = series.getEndIndex();
        if (endIndex < minBarsRequired()) {
            recordReject(symbol, "not_enough_bars");
            return signal;
        }

        SymbolState state = stateMap.computeIfAbsent(symbol, key -> new SymbolState());
        if (endIndex <= state.lastProcessedIndex) {
            state.reset();
        }
        state.lastProcessedIndex = endIndex;

        updateVirtualPosition(series, endIndex, state);
        if (state.activePosition != null || endIndex <= state.cooldownUntilIndex) {
            recordReject(symbol, state.activePosition != null ? "virtual_position_active" : "cooldown");
            return signal;
        }

        double high = BinanceStrategyMath.highestHigh(series, endIndex, RANGE_LOOKBACK);
        double low = BinanceStrategyMath.lowestLow(series, endIndex, RANGE_LOOKBACK);
        double close = BinanceStrategyMath.close(series, endIndex);
        double widthPct = BinanceStrategyMath.bandWidthPct(high, low);
        double smaNow = BinanceStrategyMath.sma(series, endIndex, RANGE_LOOKBACK);
        double smaPrev = BinanceStrategyMath.sma(series, endIndex - 1, RANGE_LOOKBACK);
        double smaSlopePct = smaPrev == 0 ? 0.0 : Math.abs((smaNow - smaPrev) / smaPrev);
        if (widthPct > RANGE_MAX_WIDTH_PCT || smaSlopePct > 0.003) {
            recordReject(symbol, "range_filter");
            return signal;
        }

        double range = Math.max(0.0, high - low);
        if (range <= 0.0) {
            recordReject(symbol, "invalid_range");
            return signal;
        }
        double zone = range * EDGE_ZONE_PCT;

        DriftContext driftContext = buildDriftContext(series, endIndex, high, low);
        Bar currentBar = series.getBar(endIndex);
        double open = currentBar.getOpenPrice().doubleValue();
        double barHigh = currentBar.getHighPrice().doubleValue();
        double barLow = currentBar.getLowPrice().doubleValue();
        double prevClose = series.getBar(endIndex - 1).getClosePrice().doubleValue();

        boolean touchedLower = barLow <= low + zone;
        boolean touchedUpper = barHigh >= high - zone;
        boolean bullishReversal = touchedLower && isBullishReversal(open, close, prevClose, barHigh, barLow, low, zone);
        boolean bearishReversal = touchedUpper && isBearishReversal(open, close, prevClose, barHigh, barLow, high, zone);

        double stopBuffer = range * STOP_BUFFER_PCT_OF_RANGE;
        double takeBuffer = zone * TAKE_BUFFER_PCT_OF_ZONE;
        if (bullishReversal && !driftContext.blockBuy) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(low - stopBuffer);
            signal.takerPrice = BinanceStrategyMath.scale(high - takeBuffer);
        } else if (bearishReversal && !driftContext.blockSell) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(high + stopBuffer);
            signal.takerPrice = BinanceStrategyMath.scale(low + takeBuffer);
        } else {
            if (driftContext.blockBuy || driftContext.blockSell) {
                recordReject(symbol, driftContext.blockBuy ? "drift_block_buy" : "drift_block_sell");
            } else {
                recordReject(symbol, "no_reversal_confirm");
            }
            return signal;
        }

        state.activePosition = new VirtualPosition(
                signal.side,
                close,
                signal.stopPrice == null ? null : signal.stopPrice.doubleValue(),
                signal.takerPrice == null ? null : signal.takerPrice.doubleValue(),
                endIndex);
        return signal;
    }

    private void updateVirtualPosition(BarSeries series, int endIndex, SymbolState state) {
        if (state.activePosition == null || state.activePosition.entryIndex >= endIndex) {
            return;
        }

        Bar bar = series.getBar(endIndex);
        double high = bar.getHighPrice().doubleValue();
        double low = bar.getLowPrice().doubleValue();
        boolean hitStop;
        boolean hitTake;
        if (state.activePosition.side == Side.BUY) {
            hitStop = state.activePosition.stopPrice != null && low <= state.activePosition.stopPrice;
            hitTake = state.activePosition.takePrice != null && high >= state.activePosition.takePrice;
        } else {
            hitStop = state.activePosition.stopPrice != null && high >= state.activePosition.stopPrice;
            hitTake = state.activePosition.takePrice != null && low <= state.activePosition.takePrice;
        }

        if (!hitStop && !hitTake) {
            return;
        }

        if (hitStop) {
            state.consecutiveStopCount++;
            if (state.consecutiveStopCount >= COOLING_STOP_COUNT) {
                state.cooldownUntilIndex = endIndex + COOLING_BARS;
            }
        } else {
            state.consecutiveStopCount = 0;
        }
        state.activePosition = null;
    }

    private DriftContext buildDriftContext(BarSeries series, int endIndex, double high, double low) {
        DriftContext context = new DriftContext();
        double mid = (high + low) / 2.0;
        int stayStart = Math.max(0, endIndex - MID_STAY_LOOKBACK + 1);
        int sampleCount = endIndex - stayStart + 1;
        int aboveCount = 0;
        int belowCount = 0;
        for (int i = stayStart; i <= endIndex; i++) {
            double close = series.getBar(i).getClosePrice().doubleValue();
            if (close > mid) {
                aboveCount++;
            } else if (close < mid) {
                belowCount++;
            }
        }
        context.aboveMidRatio = sampleCount <= 0 ? 0.0 : (double) aboveCount / sampleCount;
        context.belowMidRatio = sampleCount <= 0 ? 0.0 : (double) belowCount / sampleCount;

        int htfPeriodBars = HTF_SMA_PERIOD_1H * BARS_PER_HOUR;
        double htfNow = BinanceStrategyMath.sma(series, endIndex, htfPeriodBars);
        double htfPrev = BinanceStrategyMath.sma(series, endIndex - BARS_PER_HOUR, htfPeriodBars);
        context.htfSlopePct = htfPrev == 0 ? 0.0 : (htfNow - htfPrev) / htfPrev;
        context.blockBuy = context.htfSlopePct <= -HTF_DRIFT_SLOPE_PCT && context.belowMidRatio >= MID_STAY_RATIO;
        context.blockSell = context.htfSlopePct >= HTF_DRIFT_SLOPE_PCT && context.aboveMidRatio >= MID_STAY_RATIO;
        return context;
    }

    private boolean isBullishReversal(double open, double close, double prevClose,
                                      double high, double low, double rangeLow, double zone) {
        double recoverLevel = rangeLow + zone * REVERSAL_RECOVER_ZONE_RATIO;
        return close >= recoverLevel
                && (close > open || close > prevClose);
    }

    private boolean isBearishReversal(double open, double close, double prevClose,
                                      double high, double low, double rangeHigh, double zone) {
        double recoverLevel = rangeHigh - zone * REVERSAL_RECOVER_ZONE_RATIO;
        return close <= recoverLevel
                && (close < open || close < prevClose);
    }

    private int minBarsRequired() {
        return Math.max(RANGE_LOOKBACK + 2, HTF_SMA_PERIOD_1H * BARS_PER_HOUR + BARS_PER_HOUR + 2);
    }

    @Override
    public void resetRejectStats(String symbol) {
        rejectStats.remove(symbol);
    }

    @Override
    public Map<String, Integer> snapshotRejectStats(String symbol) {
        Map<String, Integer> stats = rejectStats.get(symbol);
        return stats == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stats);
    }

    private void recordReject(String symbol, String reason) {
        rejectStats.computeIfAbsent(symbol, key -> new ConcurrentHashMap<>())
                .merge(reason, 1, Integer::sum);
    }

    private static class SymbolState {
        private VirtualPosition activePosition;
        private int consecutiveStopCount;
        private int cooldownUntilIndex = -1;
        private int lastProcessedIndex = -1;

        private void reset() {
            activePosition = null;
            consecutiveStopCount = 0;
            cooldownUntilIndex = -1;
            lastProcessedIndex = -1;
        }
    }

    private static class VirtualPosition {
        private final Side side;
        private final double entryPrice;
        private final Double stopPrice;
        private final Double takePrice;
        private final int entryIndex;

        private VirtualPosition(Side side, double entryPrice, Double stopPrice, Double takePrice, int entryIndex) {
            this.side = side;
            this.entryPrice = entryPrice;
            this.stopPrice = stopPrice;
            this.takePrice = takePrice;
            this.entryIndex = entryIndex;
        }
    }

    private static class DriftContext {
        private double htfSlopePct;
        private double aboveMidRatio;
        private double belowMidRatio;
        private boolean blockBuy;
        private boolean blockSell;
    }
}
