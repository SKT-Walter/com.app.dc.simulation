package com.app.dc.service.simulation.deterministic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeterministicScoreCard {
    public String strategyName;
    public String family;
    public double minimumScore;
    public double score;
    public double setupReadiness;
    public double regimeScore;
    public double penalty;
    public final Map<String, Double> components = new LinkedHashMap<String, Double>();
    public final List<String> supportingFactors = new ArrayList<String>();
    public final List<String> penaltyFactors = new ArrayList<String>();

    public boolean reachesThreshold() { return score >= minimumScore; }
}
