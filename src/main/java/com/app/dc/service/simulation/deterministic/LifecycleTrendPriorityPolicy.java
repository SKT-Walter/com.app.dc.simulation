package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import com.app.dc.service.simulation.strategy.SymbolStrategyNames;

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
        if (context == null) return null;
        String baseCandidate = actionable(context.btcBullTrend) ? "btcStructuralBullTrend"
                : actionable(context.ethBullTrend) ? "ethStructuralBullTrend"
                : actionable(context.btcBearTrend) ? "btcStructuralBearTrend"
                : actionable(context.btcBullLaunchTrend) ? "btcBullLaunchTrend"
                : actionable(context.ethBearTrend) ? "ethStructuralBearTrend"
                : actionable(context.solBearTrend) ? "solStructuralBearTrend"
                : actionable(context.solBullTrend) ? "solMomentumBullTrend"
                : actionable(context.solBullLaunchTrend) ? "solBullLaunchTrend" : null;
        String candidate=SymbolStrategyNames.qualify(baseCandidate,context.symbol);
        if (candidate == null || scores == null) return null;
        // Early launch is an additive flat-account entry source. It must never
        // split or replace an already-running mature continuation position.
        if (positionOwner != null&&("solBullLaunchTrend".equalsIgnoreCase(baseCandidate)
                ||"btcBullLaunchTrend".equalsIgnoreCase(baseCandidate))) return null;
        // SOL A-wave detection is additive. An existing short already owns the
        // decline and must not be closed/reopened merely to change strategy labels.
        if (positionOwner != null&&"solStructuralBearTrend".equalsIgnoreCase(baseCandidate)) return null;
        if (positionOwner != null && baseCandidate.equalsIgnoreCase(SymbolStrategyNames.baseName(positionOwner))) return null;
        // Presence in score cards proves that the hard candidate chain accepted it.
        // TRIGGERED already contains 4H direction, 1H structure and 15m execution
        // confirmation; the generic family score must not veto that event again.
        for (DeterministicScoreCard score : scores)
            if (candidate.equalsIgnoreCase(score.strategyName))
                return candidate;
        // Unit/fixed-strategy callers may still supply the legacy unsuffixed name.
        for (DeterministicScoreCard score : scores)
            if (baseCandidate.equalsIgnoreCase(score.strategyName))
                return baseCandidate;
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
