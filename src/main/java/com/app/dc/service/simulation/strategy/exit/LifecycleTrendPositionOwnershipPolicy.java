package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import org.springframework.stereotype.Service;

/** Keeps a stateful trend position under its own exit policy until it is closed. */
@Service
public class LifecycleTrendPositionOwnershipPolicy {
    public static final String REJECTION_REASON="LIFECYCLE_TREND_POSITION_OWNED";

    public boolean blocksForeignReversal(Position position,String proposedStrategy) {
        if (position == null || !owns(position.strategyName)) return false;
        return proposedStrategy == null
                || !position.strategyName.equalsIgnoreCase(proposedStrategy);
    }

    public boolean shouldHandoffSameDirection(Position position,String proposedStrategy,Side proposedSide) {
        return position != null && position.side == proposedSide && owns(proposedStrategy)
                && (position.strategyName == null
                || !position.strategyName.equalsIgnoreCase(proposedStrategy));
    }

    public boolean owns(String strategyName) {
        return "ethStructuralBullTrend".equalsIgnoreCase(strategyName)
                || "ethStructuralBearTrend".equalsIgnoreCase(strategyName)
                || "solMomentumBullTrend".equalsIgnoreCase(strategyName);
    }
}
