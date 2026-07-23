package com.app.dc.service.simulation.deterministic;

public interface StrategySetupScorer {
    String strategyName();
    String family();
    StrategySetupScore score(StrategyEvaluationContext context);
}
