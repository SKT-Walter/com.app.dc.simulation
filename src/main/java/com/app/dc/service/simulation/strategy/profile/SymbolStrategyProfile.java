package com.app.dc.service.simulation.strategy.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/** Symbol/timeframe-specific execution overrides; strategy algorithms remain shared. */
public class SymbolStrategyProfile {
    public String profileVersion;
    public String symbol;
    public String timeframe;
    public boolean enabled = true;
    public Map<String, StrategyOverride> strategies =
            new LinkedHashMap<String, StrategyOverride>();

    public static class StrategyOverride {
        public Boolean enabled;
        public Double takeProfitPct;
        public Boolean binanceTrendBuyEnabled;
        public Boolean trendLifecycleEnabled;
        public Integer trendImpulseLookbackBars;
        public Integer trendMinimumPullbackBars;
        public Double trendMinimumRetracement;
        public Double trendMaximumRetracement;
        public Double trendInvalidationRetracement;
        public Integer trendMaximumLifecycleBars;
        public Integer trendTriggerValidityBars;
        public Double trendMinimumBodyAtr;
        public Double trendMinimumVolumeRatio;
        public Double trendMinimumCloseLocation;
        public Double trendMaximumTriggerExtensionAtr;
        public Double trendStopPaddingAtr;
        public Double trendMinimumStopAtr;
        public Double trendMaximumStopAtr;
        public Double trendTrailActivationAtr;
        public Double trendInitialTrailAtr;
        public Double trendMatureTrailActivationAtr;
        public Double trendMatureTrailAtr;
        public Double minimumTriggerRangeAtr;
        public Double minimumBuyRecoveryBodyAtr;
        public Double minimumBuyCloseLocation;
        public Boolean rangeStateMachineEnabled;
        public Integer rangeLookbackBars;
        public Integer rangeStabilityBars;
        public Integer rangeMinimumMidCrosses;
        public Double minimumBoxRangeAtr;
        public Double maximumBoxRangeAtr;
        public Double rangeEdgeZoneRatio;
        public Integer rangeTouchValidityBars;
        public Integer rangeConfirmedValidityBars;
        public Boolean rangeNextBarConfirmationRequired;
        public Double rangeMaximumBreakoutAtr;
        public Double rangeMinimumRewardRisk;
        public Double rangeStopPaddingAtr;
        public Double rangeTargetExtensionRatio;
    }
}
