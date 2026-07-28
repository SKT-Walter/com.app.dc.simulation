package com.app.dc.service.simulation.deterministic;

/**
 * Slow structural trend derived only from closed 15-minute bars.
 * It is observational context and never rejects a baseline candidate.
 */
public final class StructuralTrendSnapshot {
    public static final String BULL = "BULL";
    public static final String BEAR = "BEAR";
    public static final String NEUTRAL = "NEUTRAL";

    public final String direction;
    public final String rawDirection;
    public final String phase;
    public final double confidence;
    public final int directionBars;
    public final int pullbackBars;
    public final boolean ready;

    public StructuralTrendSnapshot(String direction, String rawDirection, String phase,
                                   double confidence, int directionBars, int pullbackBars,
                                   boolean ready) {
        this.direction = direction;
        this.rawDirection = rawDirection;
        this.phase = phase;
        this.confidence = confidence;
        this.directionBars = directionBars;
        this.pullbackBars = pullbackBars;
        this.ready = ready;
    }

    public boolean isBull() {
        return ready && BULL.equals(direction);
    }

    public boolean isBear() {
        return ready && BEAR.equals(direction);
    }

    public static StructuralTrendSnapshot warmup() {
        return new StructuralTrendSnapshot(NEUTRAL, NEUTRAL, "WARMUP",
                0, 0, 0, false);
    }
}
