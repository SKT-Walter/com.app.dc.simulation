package com.app.dc.service.simulation.deterministic;

/** Immutable result of the low-volatility directional compression state machine. */
public final class TrendCompressionSnapshot {
    public static final String NONE = "NONE";
    public static final String PREPARING = "PREPARING";
    public static final String ARMED = "ARMED";
    public static final String TRIGGERED = "TRIGGERED";
    public static final String BREAKOUT_PENDING = "BREAKOUT_PENDING";
    public static final String EXPIRED = "EXPIRED";

    public final String phase;
    public final String direction;
    public final int preparationBars;
    public final int armedUntil;
    public final double compressionHigh;
    public final double compressionLow;
    public final double breakoutLevel;
    public final boolean triggered;

    public TrendCompressionSnapshot(String phase, String direction, int preparationBars,
                                    int armedUntil, double compressionHigh,
                                    double compressionLow, double breakoutLevel,
                                    boolean triggered) {
        this.phase = phase;
        this.direction = direction;
        this.preparationBars = preparationBars;
        this.armedUntil = armedUntil;
        this.compressionHigh = compressionHigh;
        this.compressionLow = compressionLow;
        this.breakoutLevel = breakoutLevel;
        this.triggered = triggered;
    }

    public static TrendCompressionSnapshot none() {
        return new TrendCompressionSnapshot(NONE, NONE, 0, -1,
                Double.NaN, Double.NaN, Double.NaN, false);
    }
}
