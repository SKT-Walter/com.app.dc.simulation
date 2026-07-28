package com.app.dc.service.simulation.dynamic;

import java.util.ArrayList;
import java.util.List;

public class DynamicStrategyMeta {
    public String strategyName;
    public String family;
    public boolean enabled = true;
    public double minimumScore = 60;
    public List<String> supportedRegimes = new ArrayList<String>();
    public List<String> requiredFeatures = new ArrayList<String>();

    public boolean supports(BacktestRegime regime) {
        if (!enabled || regime == null || !regime.tradeable) return false;
        boolean match = false;
        for (String supported : supportedRegimes) {
            if ("*".equals(supported) || regime.code().equalsIgnoreCase(supported)) {
                match = true;
                break;
            }
        }
        if (!match) return false;
        for (String feature : requiredFeatures)
            if (!regime.features.containsKey(feature)) return false;
        if ("BREAKOUT".equalsIgnoreCase(family)) return regime.breakoutExpansion;
        if ("TREND".equalsIgnoreCase(family)) return !"NONE".equals(regime.trend);
        if ("MEAN_REVERSION".equalsIgnoreCase(family)) return "NONE".equals(regime.trend);
        return false;
    }
}
