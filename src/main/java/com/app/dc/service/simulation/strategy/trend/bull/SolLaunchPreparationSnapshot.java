package com.app.dc.service.simulation.strategy.trend.bull;

/** Immutable diagnostic snapshot for the SOL 15-minute early-launch sequence. */
public final class SolLaunchPreparationSnapshot {
    public final String phase;
    public final String reason;
    public final boolean breakoutPending;
    public final boolean confirmed;
    public final double readiness;
    public final double compressionLow;
    public final double breakoutLevel;
    public final int barIndex;

    SolLaunchPreparationSnapshot(String phase, String reason, boolean breakoutPending,
                                 boolean confirmed, double readiness, double compressionLow,
                                 double breakoutLevel, int barIndex) {
        this.phase = phase;
        this.reason = reason;
        this.breakoutPending = breakoutPending;
        this.confirmed = confirmed;
        this.readiness = Math.max(0, Math.min(1, Double.isFinite(readiness) ? readiness : 0));
        this.compressionLow = compressionLow;
        this.breakoutLevel = breakoutLevel;
        this.barIndex = barIndex;
    }

    public static SolLaunchPreparationSnapshot warmup() {
        return new SolLaunchPreparationSnapshot(BullTrendSnapshot.WARMUP,
                "SOL_LAUNCH_PREPARATION_WARMUP", false, false, 0,
                Double.NaN, Double.NaN, -1);
    }
}
