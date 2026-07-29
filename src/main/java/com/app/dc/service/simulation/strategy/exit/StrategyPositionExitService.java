package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.service.simulation.BacktestModels.Position;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** Selects the policy owned by the strategy that opened the current position. */
@Service
public class StrategyPositionExitService {
    private final List<StrategyPositionExitPolicy> policies;

    @Autowired
    public StrategyPositionExitService(List<StrategyPositionExitPolicy> policies) {
        this.policies = policies;
    }

    public PositionExitDecision evaluate(Position position, StrategyPositionExitContext context) {
        if (position == null || position.strategyName == null) return PositionExitDecision.hold();
        for (StrategyPositionExitPolicy policy : policies) {
            if (policy.supports(position.strategyName)) return policy.evaluate(position, context);
        }
        return PositionExitDecision.hold();
    }
}
