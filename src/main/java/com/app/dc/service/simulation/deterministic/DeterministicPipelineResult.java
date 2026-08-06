package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.Signal;
import com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;

public final class DeterministicPipelineResult {
    public StrategyEvaluationContext context;
    public CandidateSelectionResult candidates;
    public StrategyRoutingDecision routingDecision;
    public StructuralTrendSnapshot structuralTrend;
    public TrendLifecycleSnapshot trendLifecycle;
    public BullTrendSnapshot ethBullTrend;
    public BullTrendSnapshot solBullTrend;
    public BearTrendSnapshot ethBearTrend;
    public Signal signal;
    public String signalSource;
    public String executionStrategyName;
}
