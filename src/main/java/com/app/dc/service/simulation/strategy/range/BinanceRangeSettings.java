package com.app.dc.service.simulation.strategy.range;

/** Immutable symbol-specific parameters for the range setup. */
public final class BinanceRangeSettings {
    public final boolean stateMachineEnabled;
    public final int lookbackBars;
    public final int stabilityBars;
    public final int minimumMidCrosses;
    public final double minimumBoxRangeAtr;
    public final double maximumBoxRangeAtr;
    public final double edgeZoneRatio;
    public final int touchValidityBars;
    public final int confirmedValidityBars;
    public final boolean nextBarConfirmationRequired;
    public final double maximumBreakoutAtr;
    public final double minimumRewardRisk;
    public final double stopPaddingAtr;
    public final double targetExtensionRatio;
    public final double minimumTriggerRangeAtr;
    public final double minimumBuyRecoveryBodyAtr;
    public final double minimumBuyCloseLocation;

    public BinanceRangeSettings(boolean stateMachineEnabled, int lookbackBars,
                                int stabilityBars, int minimumMidCrosses,
                                double minimumBoxRangeAtr, double maximumBoxRangeAtr,
                                double edgeZoneRatio, int touchValidityBars,
                                int confirmedValidityBars,
                                boolean nextBarConfirmationRequired,
                                double maximumBreakoutAtr,
                                double minimumRewardRisk, double stopPaddingAtr,
                                double targetExtensionRatio,
                                double minimumTriggerRangeAtr,
                                double minimumBuyRecoveryBodyAtr,
                                double minimumBuyCloseLocation) {
        this.stateMachineEnabled = stateMachineEnabled;
        this.lookbackBars = lookbackBars;
        this.stabilityBars = stabilityBars;
        this.minimumMidCrosses = minimumMidCrosses;
        this.minimumBoxRangeAtr = minimumBoxRangeAtr;
        this.maximumBoxRangeAtr = maximumBoxRangeAtr;
        this.edgeZoneRatio = edgeZoneRatio;
        this.touchValidityBars = touchValidityBars;
        this.confirmedValidityBars = confirmedValidityBars;
        this.nextBarConfirmationRequired = nextBarConfirmationRequired;
        this.maximumBreakoutAtr = maximumBreakoutAtr;
        this.minimumRewardRisk = minimumRewardRisk;
        this.stopPaddingAtr = stopPaddingAtr;
        this.targetExtensionRatio = targetExtensionRatio;
        this.minimumTriggerRangeAtr = minimumTriggerRangeAtr;
        this.minimumBuyRecoveryBodyAtr = minimumBuyRecoveryBodyAtr;
        this.minimumBuyCloseLocation = minimumBuyCloseLocation;
    }

    public static BinanceRangeSettings legacy(double minimumTriggerRangeAtr,
                                              double minimumBuyRecoveryBodyAtr,
                                              double minimumBuyCloseLocation) {
        return new BinanceRangeSettings(false, 20, 4, 2, 0, Double.MAX_VALUE,
                .15, 3, 3, true, .60, 1.25, .25, 0, minimumTriggerRangeAtr,
                minimumBuyRecoveryBodyAtr, minimumBuyCloseLocation);
    }
}
