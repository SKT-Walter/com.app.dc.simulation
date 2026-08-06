package com.app.dc.service.simulation.deterministic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Arms during an established UP_LOW/DOWN_LOW regime and emits a one-bar trigger
 * only after volatility expands and price leaves the frozen compression boundary.
 */
@Service
public class TrendCompressionService {
    @Value("${backtest.trend-compression.preparation-bars:3}")
    private int preparationBars = 3;
    @Value("${backtest.trend-compression.armed-validity-bars:16}")
    private int armedValidityBars = 16;
    @Value("${backtest.trend-compression.minimum-volume-ratio:0.80}")
    private double minimumVolumeRatio = .80;
    @Value("${backtest.trend-compression.minimum-breakout-atr:0.05}")
    private double minimumBreakoutAtr = .05;
    @Value("${backtest.trend-compression.minimum-body-atr:0.20}")
    private double minimumBodyAtr = .20;
    @Value("${backtest.trend-compression.retest-validity-bars:4}")
    private int retestValidityBars = 4;
    @Value("${backtest.trend-compression.maximum-retest-atr:0.50}")
    private double maximumRetestAtr = .50;

    public TrendCompressionState newState() {
        return new TrendCompressionState();
    }

    public TrendCompressionSnapshot update(TrendCompressionState state,
                                           StrategyEvaluationContext context) {
        if (state == null || context == null || context.regime == null
                || context.technical == null) return TrendCompressionSnapshot.none();
        TechnicalSnapshot t = context.technical;
        String regimeTrend = context.regime.trend;
        if (state.breakoutPendingUntil >= 0)
            return evaluateRetest(state, context);
        boolean lowDirectional = ("UP".equals(regimeTrend) || "DOWN".equals(regimeTrend))
                && "LOW".equals(context.regime.volatility);
        boolean aligned = aligned(regimeTrend, t)
                && structuralCompatible(regimeTrend, context.structuralTrend);

        if (lowDirectional && aligned) {
            if (!regimeTrend.equals(state.direction)) {
                state.reset();
                state.direction = regimeTrend;
                state.compressionHigh = t.recentHigh;
                state.compressionLow = t.recentLow;
            }
            state.preparationBars++;
            state.compressionHigh = Math.max(state.compressionHigh, t.high);
            state.compressionLow = Math.min(state.compressionLow, t.low);
            if (state.preparationBars >= Math.max(1, preparationBars))
                state.armedUntil = context.barIndex + Math.max(1, armedValidityBars);
            String phase = state.armedUntil >= context.barIndex
                    ? TrendCompressionSnapshot.ARMED
                    : TrendCompressionSnapshot.PREPARING;
            return snapshot(state, phase, false);
        }

        if (TrendCompressionSnapshot.NONE.equals(state.direction))
            return TrendCompressionSnapshot.none();
        if (opposite(regimeTrend, state.direction) || !aligned(state.direction, t)
                || !structuralCompatible(state.direction, context.structuralTrend)) {
            state.reset();
            return TrendCompressionSnapshot.none();
        }
        if (context.barIndex > state.armedUntil) {
            TrendCompressionSnapshot expired = snapshot(
                    state, TrendCompressionSnapshot.EXPIRED, false);
            state.reset();
            return expired;
        }

        boolean volatilityExpanded = "NORMAL".equals(context.regime.volatility)
                || "HIGH".equals(context.regime.volatility);
        double breakoutDistance = Math.max(0, minimumBreakoutAtr) * t.atr;
        boolean directionalBreak = "UP".equals(state.direction)
                ? t.close > state.compressionHigh + breakoutDistance
                    && t.closeLocation >= .60
                : t.close < state.compressionLow - breakoutDistance
                    && t.closeLocation <= .40;
        boolean quality = t.volumeRatio >= minimumVolumeRatio
                && t.bodyAtr >= minimumBodyAtr;
        if (volatilityExpanded && directionalBreak && quality) {
            state.breakoutLevel = "UP".equals(state.direction)
                    ? state.compressionHigh : state.compressionLow;
            state.breakoutPendingUntil = context.barIndex
                    + Math.max(1, retestValidityBars);
            return snapshot(state, TrendCompressionSnapshot.BREAKOUT_PENDING, false);
        }
        return snapshot(state, TrendCompressionSnapshot.ARMED, false);
    }

    private TrendCompressionSnapshot evaluateRetest(TrendCompressionState state,
                                                    StrategyEvaluationContext context) {
        TechnicalSnapshot t = context.technical;
        if (context.barIndex > state.breakoutPendingUntil) {
            TrendCompressionSnapshot expired = snapshot(
                    state, TrendCompressionSnapshot.EXPIRED, false);
            state.reset();
            return expired;
        }
        double tolerance = Math.max(0, maximumRetestAtr) * t.atr;
        boolean invalid = "UP".equals(state.direction)
                ? t.close < state.breakoutLevel - tolerance
                : t.close > state.breakoutLevel + tolerance;
        if (invalid || opposite(context.regime.trend, state.direction)
                || !aligned(state.direction, t)
                || !structuralCompatible(state.direction, context.structuralTrend)) {
            state.reset();
            return TrendCompressionSnapshot.none();
        }
        boolean retest = "UP".equals(state.direction)
                ? t.low <= state.breakoutLevel + tolerance
                    && t.close > state.breakoutLevel
                    && t.close > t.open && t.close > t.previousClose
                    && t.closeLocation >= .55
                : t.high >= state.breakoutLevel - tolerance
                    && t.close < state.breakoutLevel
                    && t.close < t.open && t.close < t.previousClose
                    && t.closeLocation <= .45;
        if (retest) {
            TrendCompressionSnapshot triggered = snapshot(
                    state, TrendCompressionSnapshot.TRIGGERED, true);
            state.reset();
            return triggered;
        }
        return snapshot(state, TrendCompressionSnapshot.BREAKOUT_PENDING, false);
    }

    private boolean aligned(String direction, TechnicalSnapshot t) {
        if ("UP".equals(direction))
            return t.ema20 > t.ema60 && t.emaSlowSlope > 0;
        if ("DOWN".equals(direction))
            return t.ema20 < t.ema60 && t.emaSlowSlope < 0;
        return false;
    }

    private boolean opposite(String current, String armed) {
        return "UP".equals(current) && "DOWN".equals(armed)
                || "DOWN".equals(current) && "UP".equals(armed);
    }

    private boolean structuralCompatible(String direction,
                                         StructuralTrendSnapshot structural) {
        if (structural == null || !structural.ready) return true;
        return !("UP".equals(direction) && structural.isBear())
                && !("DOWN".equals(direction) && structural.isBull());
    }

    private TrendCompressionSnapshot snapshot(TrendCompressionState state,
                                              String phase, boolean triggered) {
        return new TrendCompressionSnapshot(phase, state.direction,
                state.preparationBars, state.armedUntil,
                state.compressionHigh, state.compressionLow,
                state.breakoutLevel, triggered);
    }
}
