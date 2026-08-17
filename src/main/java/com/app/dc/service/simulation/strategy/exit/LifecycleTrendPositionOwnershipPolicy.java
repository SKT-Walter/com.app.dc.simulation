package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import org.springframework.stereotype.Service;
import com.app.dc.service.simulation.strategy.SymbolStrategyNames;

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
        if(position!=null&&isSolBullPair(position.strategyName,proposedStrategy))return false;
        return position != null && position.side == proposedSide && owns(proposedStrategy)
                && (position.strategyName == null
                || !position.strategyName.equalsIgnoreCase(proposedStrategy));
    }

    private boolean isSolBullPair(String current,String proposed){
        String a=SymbolStrategyNames.baseName(current),b=SymbolStrategyNames.baseName(proposed);
        return ("solMomentumBullTrend".equalsIgnoreCase(a)||"solBullLaunchTrend".equalsIgnoreCase(a))
                &&("solMomentumBullTrend".equalsIgnoreCase(b)||"solBullLaunchTrend".equalsIgnoreCase(b));
    }

    public boolean owns(String strategyName) {
        String baseName=SymbolStrategyNames.baseName(strategyName);
        return "ethStructuralBullTrend".equalsIgnoreCase(baseName)
                || "btcStructuralBullTrend".equalsIgnoreCase(baseName)
                || "btcBullLaunchTrend".equalsIgnoreCase(baseName)
                || "btcStructuralBearTrend".equalsIgnoreCase(baseName)
                || "ethStructuralBearTrend".equalsIgnoreCase(baseName)
                || "solStructuralBearTrend".equalsIgnoreCase(baseName)
                || "solMomentumBullTrend".equalsIgnoreCase(baseName)
                || "solBullLaunchTrend".equalsIgnoreCase(baseName);
    }
}
