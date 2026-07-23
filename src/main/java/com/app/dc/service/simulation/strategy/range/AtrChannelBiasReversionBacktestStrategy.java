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
 * ATR 通道偏置回归回测策略。
 *
 * 策略逻辑：
 * 1. 用中轴斜率识别轻微偏多或偏空。
 * 2. 价格回踩到动态 ATR 通道边缘时，记录待确认方向。
 * 3. 后续 1 到 3 根 K 线重新转强或转弱后，顺着偏置方向开仓。
 */
@Service("atrChannelBiasReversion")
public class AtrChannelBiasReversionBacktestStrategy implements BinanceBacktestStrategy {

    private static final int MID_PERIOD = 20;
    private static final int SLOPE_LOOKBACK = 6;
    private static final int ATR_PERIOD = 14;
    private static final double ENTRY_ATR_MULTIPLIER = 1.2;
    private static final double STOP_ATR_MULTIPLIER = 0.8;
    private static final double TAKE_ATR_MULTIPLIER = 1.4;
    private static final double MIN_SLOPE_PCT = 0.0005;
    private static final double MAX_SLOPE_PCT = 0.0080;
    private static final int CONFIRM_EXPIRE_BARS = 3;

    private final Map<String, PendingState> stateMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> rejectStats = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "atrChannelBiasReversion";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        int minBars = Math.max(MID_PERIOD + SLOPE_LOOKBACK + 2, ATR_PERIOD + 2);
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
        double mid = BinanceStrategyMath.sma(series, end, MID_PERIOD);
        double midPrev = BinanceStrategyMath.sma(series, end - SLOPE_LOOKBACK, MID_PERIOD);
        double atr = BinanceStrategyMath.atr(series, end, ATR_PERIOD);
        if (atr <= 0.0) {
            recordReject(symbol, "invalid_atr");
            return signal;
        }

        double slopePct = midPrev == 0.0 ? 0.0 : (mid - midPrev) / midPrev;
        double absSlopePct = Math.abs(slopePct);
        if (absSlopePct < MIN_SLOPE_PCT || absSlopePct > MAX_SLOPE_PCT) {
            recordReject(symbol, "slope_filter");
            state.clearPendingIfExpired(end);
            return signal;
        }

        double upper = mid + atr * ENTRY_ATR_MULTIPLIER;
        double lower = mid - atr * ENTRY_ATR_MULTIPLIER;
        if (slopePct > 0 && close <= lower) {
            state.mark(Side.BUY, end);
        } else if (slopePct < 0 && close >= upper) {
            state.mark(Side.SELL, end);
        } else {
            recordReject(symbol, "no_channel_touch");
        }

        if (!state.isActive(end)) {
            recordReject(symbol, "no_pending_state");
            return signal;
        }

        if (state.side == Side.BUY && close > prevClose && close >= mid - atr * 0.15) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, lower - atr * STOP_ATR_MULTIPLIER));
            signal.takerPrice = BinanceStrategyMath.scale(mid + atr * TAKE_ATR_MULTIPLIER);
            state.reset();
        } else if (state.side == Side.SELL && close < prevClose && close <= mid + atr * 0.15) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(upper + atr * STOP_ATR_MULTIPLIER);
            signal.takerPrice = BinanceStrategyMath.scale(Math.max(0.0, mid - atr * TAKE_ATR_MULTIPLIER));
            state.reset();
        } else {
            recordReject(symbol, "confirm_failed");
            state.clearPendingIfExpired(end);
        }
        return signal;
    }

    @Override
    public void resetRejectStats(String symbol) {
        rejectStats.remove(symbol);
    }

    @Override
    public void resetRuntime(String symbol) {
        rejectStats.remove(symbol);
        stateMap.remove(symbol);
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
