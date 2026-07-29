package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.service.simulation.BacktestModels.Position;

/** Extension point for close-based, strategy-specific position invalidation. */
public interface StrategyPositionExitPolicy {
    boolean supports(String strategyName);
    PositionExitDecision evaluate(Position position, StrategyPositionExitContext context);
}
