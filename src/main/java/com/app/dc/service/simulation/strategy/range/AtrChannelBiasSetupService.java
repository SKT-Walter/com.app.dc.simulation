package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advances ATR-channel setup memory on every closed bar. The same bar is
 * idempotent, allowing scorer and signal implementation to share one result.
 */
@Service
public class AtrChannelBiasSetupService {
    private static final int MID_PERIOD = 20;
    private static final int SLOPE_LOOKBACK = 6;
    private static final int ATR_PERIOD = 14;
    private static final double ENTRY_ATR_MULTIPLIER = 1.2;
    private static final double STOP_ATR_MULTIPLIER = .8;
    private static final double TAKE_ATR_MULTIPLIER = 1.4;
    private static final double MIN_SLOPE_PCT = .0005;
    private static final double MAX_SLOPE_PCT = .0080;
    private static final int CONFIRM_EXPIRE_BARS = 3;

    private final Map<String, State> states = new ConcurrentHashMap<String, State>();

    public AtrChannelBiasSetupSnapshot update(String symbol, String timeframe,
                                               BarSeries series) {
        if (series == null || series.getEndIndex() < minimumBars())
            return AtrChannelBiasSetupSnapshot.watch("ATR_CHANNEL_DATA_WARMUP");
        State state = states.computeIfAbsent(key(symbol, timeframe), ignored -> new State());
        synchronized (state) {
            int end = series.getEndIndex();
            if (state.lastProcessedIndex == end && state.cached != null) return state.cached;
            if (state.lastProcessedIndex >= 0 && end != state.lastProcessedIndex + 1)
                state.clearPending();
            state.lastProcessedIndex = end;

            double close = BinanceStrategyMath.close(series, end);
            double previousClose = BinanceStrategyMath.close(series, end - 1);
            double mid = BinanceStrategyMath.sma(series, end, MID_PERIOD);
            double previousMid = BinanceStrategyMath.sma(
                    series, end - SLOPE_LOOKBACK, MID_PERIOD);
            double atr = BinanceStrategyMath.atr(series, end, ATR_PERIOD);
            if (!(atr > 0) || !Double.isFinite(atr))
                return cache(state, AtrChannelBiasSetupSnapshot.watch("ATR_CHANNEL_INVALID_ATR"));

            double slope = previousMid == 0 ? 0 : (mid - previousMid) / previousMid;
            double absoluteSlope = Math.abs(slope);
            if (absoluteSlope < MIN_SLOPE_PCT || absoluteSlope > MAX_SLOPE_PCT) {
                expire(state, end);
                return cache(state, AtrChannelBiasSetupSnapshot.watch("ATR_CHANNEL_SLOPE_REJECTED"));
            }

            double upper = mid + atr * ENTRY_ATR_MULTIPLIER;
            double lower = mid - atr * ENTRY_ATR_MULTIPLIER;
            if (slope > 0 && close <= lower) state.arm(Side.BUY, end);
            else if (slope < 0 && close >= upper) state.arm(Side.SELL, end);

            expire(state, end);
            if (state.side == null)
                return cache(state, new AtrChannelBiasSetupSnapshot(
                        AtrChannelBiasSetupSnapshot.PREPARING,
                        "ATR_CHANNEL_WAITING_FOR_TOUCH", .35, Side.NONE,
                        null, null, -1));

            if (state.side == Side.BUY && close > previousClose
                    && close >= mid - atr * .15) {
                AtrChannelBiasSetupSnapshot result = triggered(state, end, Side.BUY,
                        Math.max(0, lower - atr * STOP_ATR_MULTIPLIER),
                        mid + atr * TAKE_ATR_MULTIPLIER);
                state.clearPending();
                return cache(state, result);
            }
            if (state.side == Side.SELL && close < previousClose
                    && close <= mid + atr * .15) {
                AtrChannelBiasSetupSnapshot result = triggered(state, end, Side.SELL,
                        upper + atr * STOP_ATR_MULTIPLIER,
                        Math.max(0, mid - atr * TAKE_ATR_MULTIPLIER));
                state.clearPending();
                return cache(state, result);
            }
            return cache(state, new AtrChannelBiasSetupSnapshot(
                    AtrChannelBiasSetupSnapshot.ARMED,
                    "ATR_CHANNEL_TOUCH_ARMED", .82, state.side,
                    null, null,
                    state.triggerIndex + CONFIRM_EXPIRE_BARS));
        }
    }

    public AtrChannelBiasSetupSnapshot current(String symbol, String timeframe,
                                                int barIndex) {
        State state = states.get(key(symbol, timeframe));
        if (state == null || state.lastProcessedIndex != barIndex || state.cached == null)
            return AtrChannelBiasSetupSnapshot.watch("ATR_CHANNEL_NOT_UPDATED");
        return state.cached;
    }

    public void reset(String symbol) {
        String prefix = normalize(symbol) + "|";
        for (String key : states.keySet()) if (key.startsWith(prefix)) states.remove(key);
    }

    private AtrChannelBiasSetupSnapshot triggered(State state, int end, Side side,
                                                   double stop, double take) {
        return new AtrChannelBiasSetupSnapshot(AtrChannelBiasSetupSnapshot.TRIGGERED,
                "ATR_CHANNEL_RECOVERY_TRIGGERED", 1, side,
                BinanceStrategyMath.scale(stop), BinanceStrategyMath.scale(take), end);
    }

    private AtrChannelBiasSetupSnapshot cache(State state,
                                               AtrChannelBiasSetupSnapshot value) {
        state.cached = value;
        return value;
    }

    private void expire(State state, int end) {
        if (state.side != null && end - state.triggerIndex > CONFIRM_EXPIRE_BARS)
            state.clearPending();
    }

    private int minimumBars() {
        return Math.max(MID_PERIOD + SLOPE_LOOKBACK + 2, ATR_PERIOD + 2);
    }

    private String key(String symbol, String timeframe) {
        return normalize(symbol) + "|" + normalize(timeframe);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static final class State {
        private Side side;
        private int triggerIndex = -1;
        private int lastProcessedIndex = -1;
        private AtrChannelBiasSetupSnapshot cached;

        private void arm(Side value, int index) {
            side = value;
            triggerIndex = index;
        }

        private void clearPending() {
            side = null;
            triggerIndex = -1;
        }
    }
}
