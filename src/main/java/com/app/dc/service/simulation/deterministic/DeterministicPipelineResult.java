package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.Signal;

public final class DeterministicPipelineResult {
    public StrategyEvaluationContext context;
    public CandidateSelectionResult candidates;
    public StrategyRoutingDecision routingDecision;
    public Signal signal;
}
