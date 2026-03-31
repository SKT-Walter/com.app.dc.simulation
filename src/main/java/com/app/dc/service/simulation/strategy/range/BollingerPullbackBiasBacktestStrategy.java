package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 布林回踩偏置震荡回测策略。
 *
 * 策略逻辑：
 * 1. 先用布林中轨斜率识别轻微偏多或偏空。
 * 2. 当价格回踩到中轨附近的 pullback 区后，记录一个短暂的待确认状态。
 * 3. 后续 1 到 3 根 K 线里只要价格重新转强或转弱，就顺着偏置方向开仓。
 */
@Service("bollingerPullbackBias")
public class BollingerPullbackBiasBacktestStrategy implements BinanceBacktestStrategy {

    private static final int PERIOD = 20;
    private static final int SLOPE_LOOKBACK = 6;
    private static final int ATR_PERIOD = 14;
    private static final double BAND_K = 2.0;
    private static final double MAX_WIDTH_PCT = 0.08;
    private static final double MIN_SLOPE_PCT = 0.0005;
    private static final double MAX_SLOPE_PCT = 0.0080;
    private static final double PULLBACK_STD_RATIO = 0.60;
    private static final double STOP_ATR_MULTIPLIER = 0.6;
    private static final double TAKE_STD_RATIO = 1.0;
    private static final int CONFIRM_EXPIRE_BARS = 3;

    private final Map<String, PendingState> stateMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> rejectStats = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "bollingerPullbackBias";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        int minBars = Math.max(PERIOD + SLOPE_LOOKBACK + 2, ATR_PERIOD + 2);
        if (end < minBars) {
            recordReject(symbol, "not_enough_bars");
            return signal;
        }

        PendingState state = stateMap.computeIfAbsent(symbol, key -> new PendingState());
        if (end <= state.lastProcessedIndex) {
            state.reset();
        }
        state.lastProcessedIndex = end;

        double close = BinanceStrategyMath.close(series, end);
        double prevClose = BinanceStrategyMath.close(series, end - 1);
        double mean = BinanceStrategyMath.sma(series, end, PERIOD);
        double meanPrev = BinanceStrategyMath.sma(series, end - SLOPE_LOOKBACK, PERIOD);
        double std = stdClose(series, end, PERIOD, mean);
        if (std <= 0.0) {
            recordReject(symbol, "invalid_std");
            return signal;
        }
        double upper = mean + BAND_K * std;
        double lower = mean - BAND_K * std;
        double widthPct = mean == 0.0 ? 0.0 : (upper - lower) / mean;
        if (widthPct <= 0.0 || widthPct > MAX_WIDTH_PCT) {
            recordReject(symbol, "width_filter");
            state.clearPendingIfExpired(end);
            return signal;
        }

        double slopePct = meanPrev == 0.0 ? 0.0 : (mean - meanPrev) / meanPrev;
        double absSlopePct = Math.abs(slopePct);
        if (absSlopePct < MIN_SLOPE_PCT || absSlopePct > MAX_SLOPE_PCT) {
            recordReject(symbol, "slope_filter");
            state.clearPendingIfExpired(end);
            return signal;
        }

        double atr = BinanceStrategyMath.atr(series, end, ATR_PERIOD);
        double longPullbackLine = mean - std * PULLBACK_STD_RATIO;
        double shortPullbackLine = mean + std * PULLBACK_STD_RATIO;

        if (slopePct > 0 && close <= longPullbackLine && close >= lower - atr * 0.5) {
            state.mark(Side.BUY, end);
        } else if (slopePct < 0 && close >= shortPullbackLine && close <= upper + atr * 0.5) {
            state.mark(Side.SELL, end);
        } else {
            recordReject(symbol, "no_pullback_trigger");
        }

        if (!state.isActive(end)) {
            recordReject(symbol, "no_pending_state");
            return signal;
        }

        double takeLong = mean + std * TAKE_STD_RATIO;
        double takeShort = mean - std * TAKE_STD_RATIO;
        if (state.side == Side.BUY && close > prevClose && close >= mean - std * 0.15) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, lower - atr * STOP_ATR_MULTIPLIER));
            signal.takerPrice = BinanceStrategyMath.scale(takeLong);
            state.reset();
        } else if (state.side == Side.SELL && close < prevClose && close <= mean + std * 0.15) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(upper + atr * STOP_ATR_MULTIPLIER);
            signal.takerPrice = BinanceStrategyMath.scale(Math.max(0.0, takeShort));
            state.reset();
        } else {
            recordReject(symbol, "confirm_failed");
            state.clearPendingIfExpired(end);
        }
        return signal;
    }

    private double stdClose(BarSeries series, int endIndex, int period, double mean) {
        int start = Math.max(0, endIndex - period + 1);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i <= endIndex; i++) {
            double value = series.getBar(i).getClosePrice().doubleValue();
            double diff = value - mean;
            sum += diff * diff;
            count++;
        }
        return count <= 1 ? 0.0 : Math.sqrt(sum / count);
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

    private static class PendingState {
        private Side side;
        private int triggerIndex = -1;
        private int lastProcessedIndex = -1;

        private void mark(Side nextSide, int index) {
            side = nextSide;
            triggerIndex = index;
        }

        private boolean isActive(int currentIndex) {
            return side != null && triggerIndex >= 0 && currentIndex - triggerIndex <= CONFIRM_EXPIRE_BARS;
        }

        private void clearPendingIfExpired(int currentIndex) {
            if (side != null && triggerIndex >= 0 && currentIndex - triggerIndex > CONFIRM_EXPIRE_BARS) {
                reset();
            }
        }

        private void reset() {
            side = null;
            triggerIndex = -1;
            lastProcessedIndex = -1;
        }
    }
}
