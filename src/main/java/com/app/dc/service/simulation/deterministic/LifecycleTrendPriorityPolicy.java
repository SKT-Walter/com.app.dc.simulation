package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gives a short-lived, validated lifecycle trigger execution priority.
 * This is intentionally narrower than generic HOLD starvation: only an actionable
 * symbol-specific trend state may preempt, and only while the account is flat.
 */
@Service
public class LifecycleTrendPriorityPolicy {
    public String priorityStrategy(StrategyEvaluationContext context,
                                   List<DeterministicScoreCard> scores,
                                   String positionOwner) {
        if (context == null || positionOwner != null) return null;
        String candidate = actionable(context.ethBullTrend)
                ? "ethStructuralBullTrend"
                : actionable(context.ethBearTrend) ? "ethStructuralBearTrend"
                : actionable(context.solBullTrend) ? "solMomentumBullTrend" : null;
        if (candidate == null || scores == null) return null;
        // Presence in score cards proves that the hard candidate chain accepted it.
        // TRIGGERED already contains 4H direction, 1H structure and 15m execution
        // confirmation; the generic family score must not veto that event again.
        for (DeterministicScoreCard score : scores)
            if (candidate.equalsIgnoreCase(score.strategyName))
                return candidate;
        return null;
    }

    private boolean actionable(BullTrendSnapshot snapshot) {
        return snapshot != null && snapshot.actionable
                && BullTrendSnapshot.TRIGGERED.equals(snapshot.phase);
    }

    private boolean actionable(BearTrendSnapshot snapshot) {
        return snapshot != null && snapshot.actionable
                && BearTrendSnapshot.TRIGGERED.equals(snapshot.phase);
    }
}
