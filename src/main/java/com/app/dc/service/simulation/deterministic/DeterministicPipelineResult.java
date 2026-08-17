package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.Signal;
import com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
import com.app.dc.service.simulation.strategy.range.AtrChannelBiasSetupSnapshot;

public final class DeterministicPipelineResult {
    public StrategyEvaluationContext context;
    public CandidateSelectionResult candidates;
    public StrategyRoutingDecision routingDecision;
    public StructuralTrendSnapshot structuralTrend;
    public TrendLifecycleSnapshot trendLifecycle;
    public BullTrendSnapshot ethBullTrend;
    public BullTrendSnapshot solBullTrend;
    public BullTrendSnapshot solBullLaunchTrend;
    public BearTrendSnapshot ethBearTrend;
    public BearTrendSnapshot solBearTrend;
    public BullTrendSnapshot btcBullLaunchTrend;
    public BullTrendSnapshot btcBullTrend;
    public BearTrendSnapshot btcBearTrend;
    public Signal signal;
    public String signalSource;
    public String executionStrategyName;
    public boolean participationBlocked;
    public String participationBlockReason;
    public AtrChannelBiasSetupSnapshot atrChannelBiasSetup;
}
