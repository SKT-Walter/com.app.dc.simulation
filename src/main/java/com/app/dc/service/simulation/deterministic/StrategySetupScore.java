package com.app.dc.service.simulation.deterministic;

import java.util.ArrayList;
import java.util.List;

/** Strategy-specific, pure setup assessment. Values are normalized to 0..1. */
public final class StrategySetupScore {
    public final double readiness;
    public final double penalty;
    public final List<String> supportingFactors;
    public final List<String> penaltyFactors;

    public StrategySetupScore(double readiness, double penalty, List<String> supportingFactors,
                              List<String> penaltyFactors) {
        this.readiness = MarketContextFactory.clamp(readiness);
        this.penalty = Math.max(0, Double.isFinite(penalty) ? penalty : 0);
        this.supportingFactors = supportingFactors == null
                ? new ArrayList<String>() : new ArrayList<String>(supportingFactors);
        this.penaltyFactors = penaltyFactors == null
                ? new ArrayList<String>() : new ArrayList<String>(penaltyFactors);
    }

    public static StrategySetupScore of(double readiness, String factor) {
        List<String> factors = new ArrayList<String>();
        if (factor != null && !factor.isEmpty()) factors.add(factor);
        return new StrategySetupScore(readiness, 0, factors, null);
    }
}
