package com.app.dc.service.simulation.dynamic;

import java.util.LinkedHashMap;
import java.util.Map;

public class BacktestRegime {
    public String trend = "NONE";
    public String volatility = "NORMAL";
    public double confidence;
    public boolean tradeable;
    public boolean breakoutExpansion;
    public long barTime;
    public Map<String, Double> features = new LinkedHashMap<String, Double>();

    public String code() {
        return trend + "_" + volatility;
    }
}
