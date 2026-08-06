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
    public String structuralTrend;
    public String structuralPhase;
    public double structuralConfidence;
    public String executionStrategyName;
    public String signalSource;
    public double structuralScoreAdjustment;
    public String structuralScoreReason;
    public String trendCompressionPhase;
    public String trendCompressionDirection;
    public final List<DeterministicScoreCard> scoreCards = new ArrayList<DeterministicScoreCard>();
    public final Map<String, String> candidateRejections = new LinkedHashMap<String, String>();
}
