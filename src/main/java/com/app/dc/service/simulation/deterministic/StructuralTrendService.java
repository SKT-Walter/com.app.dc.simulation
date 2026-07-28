package com.app.dc.service.simulation.deterministic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

/**
 * Stateful structural trend classifier.
 *
 * <p>The default windows describe days rather than hours on a 15-minute
 * series. A confirmed direction survives ordinary neutral bars and short
 * counter-trend pullbacks; reversal requires independent confirmation.</p>
 */
@Service
public class StructuralTrendService {
    @Value("${backtest.structural-trend.enabled:true}") private boolean enabled;
    @Value("${backtest.structural-trend.minimumBars:672}") private int minimumBars;
    @Value("${backtest.structural-trend.fastEmaBars:192}") private int fastEmaBars;
    @Value("${backtest.structural-trend.slowEmaBars:672}") private int slowEmaBars;
    @Value("${backtest.structural-trend.slopeLookbackBars:96}") private int slopeLookbackBars;
    @Value("${backtest.structural-trend.structureLookbackBars:192}") private int structureLookbackBars;
    @Value("${backtest.structural-trend.confirmationBars:16}") private int confirmationBars;
    @Value("${backtest.structural-trend.reversalConfirmationBars:32}") private int reversalConfirmationBars;
    @Value("${backtest.structural-trend.pullbackToleranceBars:192}") private int pullbackToleranceBars;
    @Value("${backtest.structural-trend.minimumConfidence:0.60}") private double minimumConfidence;
    @Value("${backtest.structural-trend.fullSpreadPct:0.04}") private double fullSpreadPct;
    @Value("${backtest.structural-trend.fullSlopePct:0.01}") private double fullSlopePct;

    public StructuralTrendState newState() {
        return new StructuralTrendState();
    }

    public StructuralTrendSnapshot update(StructuralTrendState state, BarSeries series) {
        if (!enabled || state == null || series == null || series.getBarCount() < minimumBars) {
            return StructuralTrendSnapshot.warmup();
        }

        updateIndicators(state, series);
        Evidence evidence = evidence(state, series);
        String raw = evidence.confidence >= minimumConfidence
                ? evidence.direction : StructuralTrendSnapshot.NEUTRAL;

        if (StructuralTrendSnapshot.NEUTRAL.equals(state.direction)) {
            if (StructuralTrendSnapshot.NEUTRAL.equals(raw)) {
                state.clearPending();
            } else {
                pending(state, raw);
                if (state.pendingBars >= Math.max(1, confirmationBars)) state.confirm(raw);
            }
        } else if (state.direction.equals(raw)) {
            state.directionBars++;
            state.pullbackBars = 0;
            state.clearPending();
        } else if (StructuralTrendSnapshot.NEUTRAL.equals(raw)) {
            state.directionBars++;
            state.pullbackBars++;
            state.clearPending();
            if (state.pullbackBars > Math.max(0, pullbackToleranceBars)) state.clearDirection();
        } else {
            state.directionBars++;
            state.pullbackBars++;
            pending(state, raw);
            if (state.pendingBars >= Math.max(1, reversalConfirmationBars)) state.confirm(raw);
        }

        String phase = phase(state, raw, evidence);
        double confidence = StructuralTrendSnapshot.NEUTRAL.equals(state.direction)
                ? evidence.confidence : retainedConfidence(state, evidence);
        return new StructuralTrendSnapshot(state.direction, raw, phase, confidence,
                state.directionBars, state.pullbackBars, true);
    }

    private void pending(StructuralTrendState state, String direction) {
        if (direction.equals(state.pendingDirection)) state.pendingBars++;
        else {
            state.pendingDirection = direction;
            state.pendingBars = 1;
        }
    }

    private String phase(StructuralTrendState state, String raw, Evidence evidence) {
        if (StructuralTrendSnapshot.NEUTRAL.equals(state.direction)) return "NEUTRAL";
        if (!state.direction.equals(raw)) return "PULLBACK";
        if (StructuralTrendSnapshot.BULL.equals(state.direction) && evidence.close < evidence.fastEma)
            return "PULLBACK";
        if (StructuralTrendSnapshot.BEAR.equals(state.direction) && evidence.close > evidence.fastEma)
            return "PULLBACK";
        return "ESTABLISHED";
    }

    private double retainedConfidence(StructuralTrendState state, Evidence evidence) {
        if (state.direction.equals(evidence.direction)) return evidence.confidence;
        double decay = Math.min(.35, state.pullbackBars
                / (double) Math.max(1, pullbackToleranceBars) * .35);
        return clamp(Math.max(minimumConfidence - decay, evidence.confidence * .75));
    }

    private Evidence evidence(StructuralTrendState state, BarSeries series) {
        int end = series.getEndIndex();
        double close = close(series, end);
        double fast = state.fastEma;
        double slow = state.slowEma;
        double pastSlow = state.slowEmaHistory.isEmpty()
                ? slow : state.slowEmaHistory.peekFirst();
        double spread = slow == 0 ? 0 : (fast - slow) / slow;
        double slope = pastSlow == 0 ? 0 : (slow - pastSlow) / pastSlow;
        double structure = structureDirection(series, end, structureLookbackBars);

        double bullVotes = 0;
        double bearVotes = 0;
        if (fast > slow) bullVotes++; else if (fast < slow) bearVotes++;
        if (slope > 0) bullVotes++; else if (slope < 0) bearVotes++;
        if (close > slow) bullVotes++; else if (close < slow) bearVotes++;
        if (structure > 0) bullVotes++; else if (structure < 0) bearVotes++;

        String direction = bullVotes >= 3 ? StructuralTrendSnapshot.BULL
                : bearVotes >= 3 ? StructuralTrendSnapshot.BEAR
                : StructuralTrendSnapshot.NEUTRAL;
        double directionVotes = Math.max(bullVotes, bearVotes) / 4d;
        double spreadStrength = normalize(Math.abs(spread), 0, fullSpreadPct);
        double slopeStrength = normalize(Math.abs(slope), 0, fullSlopePct);
        double confidence = clamp(.35 * directionVotes + .35 * spreadStrength
                + .30 * slopeStrength);
        return new Evidence(direction, confidence, close, fast);
    }

    private void updateIndicators(StructuralTrendState state, BarSeries series) {
        int end = series.getEndIndex();
        if (!state.indicatorsInitialized || end <= state.lastIndicatorIndex) {
            state.slowEmaHistory.clear();
            int begin = series.getBeginIndex();
            state.fastEma = close(series, begin);
            state.slowEma = state.fastEma;
            double fastAlpha = 2d / (Math.max(2, fastEmaBars) + 1d);
            double slowAlpha = 2d / (Math.max(2, slowEmaBars) + 1d);
            for (int i = begin; i <= end; i++) {
                double value = close(series, i);
                if (i > begin) {
                    state.fastEma = value * fastAlpha + state.fastEma * (1 - fastAlpha);
                    state.slowEma = value * slowAlpha + state.slowEma * (1 - slowAlpha);
                }
                rememberSlowEma(state, state.slowEma);
            }
            state.indicatorsInitialized = true;
            state.lastIndicatorIndex = end;
            return;
        }
        double value = close(series, end);
        double fastAlpha = 2d / (Math.max(2, fastEmaBars) + 1d);
        double slowAlpha = 2d / (Math.max(2, slowEmaBars) + 1d);
        state.fastEma = value * fastAlpha + state.fastEma * (1 - fastAlpha);
        state.slowEma = value * slowAlpha + state.slowEma * (1 - slowAlpha);
        rememberSlowEma(state, state.slowEma);
        state.lastIndicatorIndex = end;
    }

    private void rememberSlowEma(StructuralTrendState state, double value) {
        state.slowEmaHistory.addLast(value);
        int maximum = Math.max(2, slopeLookbackBars + 1);
        while (state.slowEmaHistory.size() > maximum) state.slowEmaHistory.removeFirst();
    }

    private double structureDirection(BarSeries series, int end, int lookback) {
        int half = Math.max(2, lookback / 2);
        int recentStart = Math.max(series.getBeginIndex(), end - half + 1);
        int previousEnd = recentStart - 1;
        if (previousEnd <= series.getBeginIndex()) return 0;
        int previousStart = Math.max(series.getBeginIndex(), previousEnd - half + 1);
        double recentHigh = highest(series, recentStart, end);
        double recentLow = lowest(series, recentStart, end);
        double previousHigh = highest(series, previousStart, previousEnd);
        double previousLow = lowest(series, previousStart, previousEnd);
        if (recentHigh > previousHigh && recentLow > previousLow) return 1;
        if (recentHigh < previousHigh && recentLow < previousLow) return -1;
        return 0;
    }

    private double highest(BarSeries series, int start, int end) {
        double value = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= end; i++) value = Math.max(value,
                series.getBar(i).getHighPrice().doubleValue());
        return value;
    }

    private double lowest(BarSeries series, int start, int end) {
        double value = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) value = Math.min(value,
                series.getBar(i).getLowPrice().doubleValue());
        return value;
    }

    private double close(BarSeries series, int index) {
        return series.getBar(index).getClosePrice().doubleValue();
    }

    private double normalize(double value, double min, double max) {
        if (!Double.isFinite(value) || max <= min) return 0;
        return clamp((value - min) / (max - min));
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }

    private static final class Evidence {
        final String direction;
        final double confidence;
        final double close;
        final double fastEma;

        Evidence(String direction, double confidence, double close, double fastEma) {
            this.direction = direction;
            this.confidence = confidence;
            this.close = close;
            this.fastEma = fastEma;
        }
    }
}
