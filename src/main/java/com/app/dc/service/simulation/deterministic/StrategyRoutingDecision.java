package com.app.dc.service.simulation.deterministic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StrategyRoutingDecision {
    public long barTime;
    public String regime;
    public double regimeConfidence;
    public String previousStrategyName;
    public String strategyName;
    public String challengerStrategyName;
    public double activeScore;
    public double challengerScore;
    public double scoreGap;
    public String reason;
    public int pendingCount;
    public final List<DeterministicScoreCard> scoreCards = new ArrayList<DeterministicScoreCard>();
    public final Map<String, String> candidateRejections = new LinkedHashMap<String, String>();
}
