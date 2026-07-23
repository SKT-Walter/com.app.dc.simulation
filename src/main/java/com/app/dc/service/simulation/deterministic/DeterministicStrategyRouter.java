package com.app.dc.service.simulation.deterministic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Stable deterministic winner selection with confirmation, hold, margin and hysteresis. */
@Service
public class DeterministicStrategyRouter {
    @Value("${backtest.routing.confirmationBars:3}") private int confirmationBars;
    @Value("${backtest.routing.minimumHoldBars:4}") private int minimumHoldBars;
    @Value("${backtest.routing.switchMargin:5}") private double switchMargin;
    @Value("${backtest.routing.retentionScoreDelta:10}") private double retentionScoreDelta;
    @Value("${backtest.routing.lowScoreConfirmationBars:3}") private int lowScoreConfirmationBars;

    public StrategyRoutingState newState() { return new StrategyRoutingState(); }

    public StrategyRoutingDecision route(StrategyRoutingState state, StrategyEvaluationContext context,
                                         CandidateSelectionResult selection,
                                         List<DeterministicScoreCard> scores) {
        StrategyRoutingDecision decision = base(state, context, selection, scores);
        int barIndex = context.barIndex;
        DeterministicScoreCard active = find(scores, state.activeStrategy);
        boolean hardInvalid = state.activeStrategy != null && active == null;
        if (hardInvalid) {
            state.clearActive();
            state.clearPending();
            decision.reason = "HARD_INVALID";
        }

        DeterministicScoreCard winner = winner(scores);
        if (winner == null) {
            if (state.activeStrategy != null) state.clearActive();
            state.clearPending();
            decision.strategyName = null;
            decision.reason = hardInvalid ? "HARD_INVALID" : "NO_CANDIDATE";
            return decision;
        }

        if (state.activeStrategy == null) {
            if (!winner.reachesThreshold()) {
                state.clearPending();
                decision.reason = hardInvalid ? "HARD_INVALID" : "NO_SCORE_ABOVE_THRESHOLD";
                return decision;
            }
            confirm(state, winner.strategyName);
            decision.challengerStrategyName = winner.strategyName;
            decision.challengerScore = winner.score;
            decision.pendingCount = state.pendingCount;
            if (state.pendingCount < Math.max(1, confirmationBars)) {
                decision.reason = hardInvalid ? "HARD_INVALID_AWAITING_CONFIRMATION" : "AWAITING_ACTIVATION";
                return decision;
            }
            state.activate(winner.strategyName, winner.score, barIndex, minimumHoldBars);
            decision.strategyName = state.activeStrategy;
            decision.activeScore = state.activeScore;
            decision.reason = hardInvalid ? "HARD_INVALID_SWITCHED" : "ACTIVATED";
            return decision;
        }

        active = find(scores, state.activeStrategy);
        state.activeScore = active.score;
        decision.strategyName = state.activeStrategy;
        decision.activeScore = active.score;
        double retentionThreshold = Math.max(0, active.minimumScore - retentionScoreDelta);
        if (active.score < retentionThreshold) {
            state.lowScoreCount++;
            boolean replacementReady = !winner.strategyName.equalsIgnoreCase(state.activeStrategy)
                    && winner.reachesThreshold();
            if (replacementReady) confirm(state, winner.strategyName);
            else state.clearPending();
            decision.challengerStrategyName = replacementReady ? winner.strategyName : null;
            decision.challengerScore = replacementReady ? winner.score : 0;
            decision.pendingCount = state.pendingCount;
            if (state.lowScoreCount >= Math.max(1, lowScoreConfirmationBars)) {
                if (replacementReady && state.pendingCount >= Math.max(1, confirmationBars)) {
                    state.activate(winner.strategyName, winner.score, barIndex, minimumHoldBars);
                    decision.strategyName = state.activeStrategy;
                    decision.activeScore = state.activeScore;
                    decision.reason = "LOW_SCORE_SWITCHED";
                } else {
                    state.clearActive();
                    state.clearPending();
                    decision.strategyName = null;
                    decision.activeScore = 0;
                    decision.reason = "LOW_SCORE_EXIT";
                }
                return decision;
            }
            decision.reason = "LOW_SCORE_PENDING";
            return decision;
        }
        state.lowScoreCount = 0;

        if (winner.strategyName.equalsIgnoreCase(state.activeStrategy)) {
            state.clearPending();
            decision.reason = "ACTIVE_RETAIN";
            return decision;
        }
        decision.challengerStrategyName = winner.strategyName;
        decision.challengerScore = winner.score;
        decision.scoreGap = winner.score - active.score;
        if (!winner.reachesThreshold() || decision.scoreGap < switchMargin) {
            state.clearPending();
            decision.reason = "CHALLENGER_MARGIN_NOT_MET";
            return decision;
        }
        if (barIndex < state.holdUntil) {
            state.clearPending();
            decision.reason = "MINIMUM_HOLD";
            return decision;
        }
        confirm(state, winner.strategyName);
        decision.pendingCount = state.pendingCount;
        if (state.pendingCount < Math.max(1, confirmationBars)) {
            decision.reason = "AWAITING_SWITCH";
            return decision;
        }
        state.activate(winner.strategyName, winner.score, barIndex, minimumHoldBars);
        decision.strategyName = state.activeStrategy;
        decision.activeScore = state.activeScore;
        decision.reason = "SWITCHED";
        return decision;
    }

    private StrategyRoutingDecision base(StrategyRoutingState state, StrategyEvaluationContext context,
                                         CandidateSelectionResult selection, List<DeterministicScoreCard> scores) {
        StrategyRoutingDecision decision = new StrategyRoutingDecision();
        decision.barTime = context.regime.barTime;
        decision.regime = context.regime.code();
        decision.regimeConfidence = context.regime.confidence;
        decision.previousStrategyName = state.activeStrategy;
        if (scores != null) decision.scoreCards.addAll(sorted(scores));
        if (selection != null) decision.candidateRejections.putAll(selection.rejectedStrategies);
        return decision;
    }

    private void confirm(StrategyRoutingState state, String strategy) {
        if (!strategy.equalsIgnoreCase(state.pendingStrategy)) {
            state.pendingStrategy = strategy;
            state.pendingCount = 1;
        } else state.pendingCount++;
    }

    private DeterministicScoreCard winner(List<DeterministicScoreCard> scores) {
        List<DeterministicScoreCard> sorted = sorted(scores);
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    private List<DeterministicScoreCard> sorted(List<DeterministicScoreCard> scores) {
        List<DeterministicScoreCard> result = new ArrayList<DeterministicScoreCard>();
        if (scores != null) result.addAll(scores);
        Collections.sort(result, new Comparator<DeterministicScoreCard>() {
            @Override public int compare(DeterministicScoreCard a, DeterministicScoreCard b) {
                int c = Double.compare(b.score, a.score);
                if (c == 0) c = Double.compare(b.setupReadiness, a.setupReadiness);
                if (c == 0) c = Double.compare(b.regimeScore, a.regimeScore);
                if (c == 0) c = a.strategyName.compareToIgnoreCase(b.strategyName);
                return c;
            }
        });
        return result;
    }

    private DeterministicScoreCard find(List<DeterministicScoreCard> scores, String strategy) {
        if (strategy == null || scores == null) return null;
        for (DeterministicScoreCard score : scores)
            if (strategy.equalsIgnoreCase(score.strategyName)) return score;
        return null;
    }
}
