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
        public Double minimumTriggerRangeAtr;
    }
}
