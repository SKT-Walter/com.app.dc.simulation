package com.app.dc.service.simulation.strategy.trend;

/** Immutable symbol-specific lifecycle and risk parameters for binanceTrend. */
public final class BinanceTrendSettings {
    public final boolean lifecycleEnabled;
    public final int impulseLookbackBars;
    public final int minimumPullbackBars;
    public final double minimumRetracement;
    public final double maximumRetracement;
    public final double invalidationRetracement;
    public final int maximumLifecycleBars;
    public final int triggerValidityBars;
    public final double minimumBodyAtr;
    public final double minimumVolumeRatio;
    public final double minimumCloseLocation;
    public final double maximumTriggerExtensionAtr;
    public final double stopPaddingAtr;
    public final double minimumStopAtr;
    public final double maximumStopAtr;
    public final double trailActivationAtr;
    public final double initialTrailAtr;
    public final double matureTrailActivationAtr;
    public final double matureTrailAtr;

    public BinanceTrendSettings(boolean lifecycleEnabled, int impulseLookbackBars,
                                int minimumPullbackBars, double minimumRetracement,
                                double maximumRetracement, double invalidationRetracement,
                                int maximumLifecycleBars, int triggerValidityBars,
                                double minimumBodyAtr, double minimumVolumeRatio,
                                double minimumCloseLocation,
                                double maximumTriggerExtensionAtr,
                                double stopPaddingAtr, double minimumStopAtr,
                                double maximumStopAtr, double trailActivationAtr,
                                double initialTrailAtr,
                                double matureTrailActivationAtr,
                                double matureTrailAtr) {
        this.lifecycleEnabled = lifecycleEnabled;
        this.impulseLookbackBars = impulseLookbackBars;
        this.minimumPullbackBars = minimumPullbackBars;
        this.minimumRetracement = minimumRetracement;
        this.maximumRetracement = maximumRetracement;
        this.invalidationRetracement = invalidationRetracement;
        this.maximumLifecycleBars = maximumLifecycleBars;
        this.triggerValidityBars = triggerValidityBars;
        this.minimumBodyAtr = minimumBodyAtr;
        this.minimumVolumeRatio = minimumVolumeRatio;
        this.minimumCloseLocation = minimumCloseLocation;
        this.maximumTriggerExtensionAtr = maximumTriggerExtensionAtr;
        this.stopPaddingAtr = stopPaddingAtr;
        this.minimumStopAtr = minimumStopAtr;
        this.maximumStopAtr = maximumStopAtr;
        this.trailActivationAtr = trailActivationAtr;
        this.initialTrailAtr = initialTrailAtr;
        this.matureTrailActivationAtr = matureTrailActivationAtr;
        this.matureTrailAtr = matureTrailAtr;
    }

    public static BinanceTrendSettings legacy() {
        return new BinanceTrendSettings(false, 96, 3, .236, .618, .786,
                384, 4, .30, .80, .65, 1, .50, 2, 4,
                2, 3.5, 4, 3);
    }
}
