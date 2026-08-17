package com.app.dc.service.simulation.strategy.trend.bull;

import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SOL-only, causal launch preparation state machine:
 * compression -> leading volume -> volatility acceleration -> 1H turn -> two 15M breakouts.
 */
@Service
public class SolLaunchPreparationService {
    private static final int MIN_BARS = 55;
    private static final int MIN_COMPRESSION_BARS = 3;
    private static final int PREPARATION_VALIDITY = 48;
    private static final int VOLUME_VALIDITY = 24;
    private static final int ACCELERATION_VALIDITY = 16;
    private static final int BREAKOUT_VALIDITY = 3;

    private final Map<String, State> states = new ConcurrentHashMap<String, State>();

    public SolLaunchPreparationSnapshot update(String symbol, String timeframe, BarSeries series,
                                                boolean oneHourArmed) {
        if (!supports(symbol, timeframe) || series == null || series.getBarCount() < MIN_BARS) {
            return SolLaunchPreparationSnapshot.warmup();
        }
        String key = key(symbol, timeframe);
        State state = states.get(key);
        if (state == null || state.lastIndex > series.getEndIndex()) {
            state = new State();
            states.put(key, state);
        }
        int end = series.getEndIndex();
        if (state.lastIndex == end) return state.snapshot;
        state.lastIndex = end;
        if (oneHourArmed && !state.oneHourArmed) state.oneHourArmedAt = end;
        if (!oneHourArmed) {
            state.oneHourArmedAt = -1;
            state.firstBreakoutBar = -1;
            state.breakoutUntil = -1;
            state.breakoutLevel = Double.NaN;
        }
        state.oneHourArmed = oneHourArmed;

        double atr = BullTrendMath.atr(series, end, 14);
        double priorAtr = BullTrendMath.atr(series, end - 1, 14);
        double longAtr = BullTrendMath.atr(series, end - 1, 50);
        if (!finitePositive(atr) || !finitePositive(priorAtr) || !finitePositive(longAtr)) {
            return snapshot(state, end, BullTrendSnapshot.INVALIDATED,
                    "SOL_LAUNCH_PREPARATION_INVALID_ATR", false, false, 0);
        }

        expire(state, end);
        updateCompression(state, series, end, priorAtr, longAtr);
        updateLeadingVolume(state, series, end, atr);
        updateAcceleration(state, series, end, priorAtr);

        if (state.firstBreakoutBar >= 0) {
            if (end > state.breakoutUntil
                    || BullTrendMath.close(series, end) < state.breakoutLevel - .25 * atr) {
                state.firstBreakoutBar = -1;
                state.breakoutUntil = -1;
                state.breakoutLevel = Double.NaN;
            } else if (end > state.firstBreakoutBar && oneHourArmed
                    && confirmsBreakout(series, end, state.breakoutLevel)) {
                state.confirmed = true;
                return snapshot(state, end, BullTrendSnapshot.TRIGGERED,
                        "SOL_LAUNCH_CONSECUTIVE_BREAKOUT_CONFIRMED", false, true, 1);
            }
        }

        if (state.firstBreakoutBar < 0 && oneHourArmed
                && state.oneHourArmedAt >= 0 && end - state.oneHourArmedAt <= 4
                && state.accelerationAt >= 0 && state.accelerationAt < state.oneHourArmedAt
                && end <= state.accelerationUntil && startsBreakout(series, end, atr)) {
            state.firstBreakoutBar = end;
            state.breakoutUntil = end + BREAKOUT_VALIDITY;
            state.breakoutLevel = BullTrendMath.close(series, end);
            return snapshot(state, end, BullTrendSnapshot.ARMED,
                    "SOL_LAUNCH_FIRST_BREAKOUT_PENDING_CONFIRMATION", true, false, .95);
        }

        if (state.firstBreakoutBar >= 0) {
            return snapshot(state, end, BullTrendSnapshot.ARMED,
                    "SOL_LAUNCH_FIRST_BREAKOUT_PENDING_CONFIRMATION", true, false, .95);
        }
        if (end <= state.accelerationUntil) {
            return snapshot(state, end, BullTrendSnapshot.PREPARING,
                    oneHourArmed ? "SOL_LAUNCH_ACCELERATION_WAITING_BREAKOUT"
                            : "SOL_LAUNCH_ACCELERATION_WAITING_1H_TURN", false, false, .85);
        }
        if (end <= state.volumeUntil) {
            return snapshot(state, end, BullTrendSnapshot.PREPARING,
                    "SOL_LAUNCH_VOLUME_PREHEAT", false, false, .70);
        }
        if (end <= state.preparedUntil) {
            return snapshot(state, end, BullTrendSnapshot.PREPARING,
                    "SOL_LAUNCH_COMPRESSION_READY", false, false, .55);
        }
        return snapshot(state, end, BullTrendSnapshot.OBSERVING,
                "SOL_LAUNCH_WAITING_COMPRESSION", false, false, .30);
    }

    public void consume(String symbol, String timeframe) {
        State state = states.get(key(symbol, timeframe));
        if (state != null) state.clearSequence();
    }

    public void reset(String symbol) {
        String prefix = symbol == null ? "" : symbol.toUpperCase() + "|";
        states.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private void updateCompression(State state, BarSeries series, int end,
                                   double priorAtr, double longAtr) {
        double high = BullTrendMath.highest(series, end - 1, 12);
        double low = BullTrendMath.lowest(series, end - 1, 12);
        boolean compressed = high - low <= 6.0 * priorAtr && priorAtr <= 1.20 * longAtr;
        state.compressionBars = compressed ? state.compressionBars + 1 : 0;
        if (state.compressionBars >= MIN_COMPRESSION_BARS) {
            state.preparedUntil = end + PREPARATION_VALIDITY;
            state.compressionHigh = high;
            state.compressionLow = low;
        }
    }

    private void updateLeadingVolume(State state, BarSeries series, int end, double atr) {
        if (end > state.preparedUntil || !Double.isFinite(state.compressionHigh)) return;
        double close = BullTrendMath.close(series, end);
        if (BullTrendMath.volumeRatio(series, end, 20) >= 1.10
                && close <= state.compressionHigh + .50 * atr) {
            state.volumeAt = end;
            state.volumeUntil = end + VOLUME_VALIDITY;
        }
    }

    private void updateAcceleration(State state, BarSeries series, int end, double priorAtr) {
        if (state.volumeAt < 0 || state.volumeAt >= end || end > state.volumeUntil) return;
        // Acceleration is a leading condition. Once 1H has armed, it must not be created retroactively.
        if (state.oneHourArmedAt >= 0 && end >= state.oneHourArmedAt) return;
        Bar bar = series.getBar(end);
        double trueRange = Math.max(bar.getHighPrice().doubleValue() - bar.getLowPrice().doubleValue(),
                Math.max(Math.abs(bar.getHighPrice().doubleValue() - BullTrendMath.close(series, end - 1)),
                        Math.abs(bar.getLowPrice().doubleValue() - BullTrendMath.close(series, end - 1))));
        double shortAtr = BullTrendMath.atr(series, end, 5);
        if ((trueRange >= 1.05 * priorAtr || shortAtr >= 1.03 * priorAtr)
                && BullTrendMath.close(series, end) > BullTrendMath.ema(series, end, 20)) {
            state.accelerationAt = end;
            state.accelerationUntil = end + ACCELERATION_VALIDITY;
        }
    }

    private boolean startsBreakout(BarSeries series, int end, double atr) {
        double close = BullTrendMath.close(series, end);
        double priorHigh = BullTrendMath.highest(series, end - 1, 12);
        double extension = (close - BullTrendMath.ema(series, end, 20)) / atr;
        return BullTrendMath.bull(series, end) && close > priorHigh
                && BullTrendMath.bodyAtr(series, end, atr) >= .35
                && BullTrendMath.closeLocation(series, end) >= .60
                && BullTrendMath.volumeRatio(series, end, 20) >= .90
                && extension <= 2.5;
    }

    private boolean confirmsBreakout(BarSeries series, int end, double breakoutLevel) {
        double close = BullTrendMath.close(series, end);
        double atr = BullTrendMath.atr(series, end, 14);
        return BullTrendMath.bull(series, end) && close >= breakoutLevel
                && close > BullTrendMath.close(series, end - 1)
                && BullTrendMath.bodyAtr(series, end, atr) >= .20
                && BullTrendMath.closeLocation(series, end) >= .55
                && BullTrendMath.volumeRatio(series, end, 20) >= .85;
    }

    private void expire(State state, int end) {
        state.confirmed = false;
        if (state.preparedUntil >= 0 && end > state.preparedUntil) state.compressionBars = 0;
        if (state.volumeUntil >= 0 && end > state.volumeUntil) state.volumeAt = -1;
        if (state.accelerationUntil >= 0 && end > state.accelerationUntil
                && state.firstBreakoutBar < 0) {
            state.accelerationAt = -1;
            state.accelerationUntil = -1;
        }
    }

    private SolLaunchPreparationSnapshot snapshot(State state, int end, String phase, String reason,
                                                  boolean pending, boolean confirmed, double readiness) {
        state.snapshot = new SolLaunchPreparationSnapshot(phase, reason, pending, confirmed,
                readiness, state.compressionLow, state.breakoutLevel, end);
        return state.snapshot;
    }

    private boolean supports(String symbol, String timeframe) {
        return "SOLUSDT".equalsIgnoreCase(symbol) && "15M".equalsIgnoreCase(timeframe);
    }

    private String key(String symbol, String timeframe) {
        return (symbol == null ? "" : symbol.toUpperCase()) + "|"
                + (timeframe == null ? "" : timeframe.toUpperCase());
    }

    private boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static final class State {
        int lastIndex = -1;
        int compressionBars;
        int preparedUntil = -1;
        int volumeAt = -1;
        int volumeUntil = -1;
        int accelerationUntil = -1;
        int accelerationAt = -1;
        int oneHourArmedAt = -1;
        int firstBreakoutBar = -1;
        int breakoutUntil = -1;
        double compressionHigh = Double.NaN;
        double compressionLow = Double.NaN;
        double breakoutLevel = Double.NaN;
        boolean confirmed;
        boolean oneHourArmed;
        SolLaunchPreparationSnapshot snapshot = SolLaunchPreparationSnapshot.warmup();

        void clearSequence() {
            preparedUntil = volumeUntil = accelerationAt = accelerationUntil = oneHourArmedAt = firstBreakoutBar = breakoutUntil = -1;
            volumeAt = -1;
            compressionBars = 0;
            compressionHigh = compressionLow = breakoutLevel = Double.NaN;
            confirmed = false;
            oneHourArmed = false;
        }
    }
}
