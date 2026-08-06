package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.dynamic.DynamicStrategyMeta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Converts the slow, stateful structural trend into a bounded score adjustment.
 *
 * <p>This service is deliberately not a candidate rule and cannot publish a signal.
 * It only nudges already legal candidates, so the normal router, strategy algorithm
 * and trade risk checks remain the sole execution path.</p>
 */
@Service
public class StructuralTrendScoreModifier {
    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    @Value("${backtest.structural-score.enabled:true}") private boolean enabled;
    @Value("${backtest.structural-score.trend-alignment-bonus:2}") private double trendAlignmentBonus;
    @Value("${backtest.structural-score.trend-conflict-penalty:3}") private double trendConflictPenalty;
    @Value("${backtest.structural-score.breakout-alignment-bonus:1.5}") private double breakoutAlignmentBonus;
    @Value("${backtest.structural-score.breakout-conflict-penalty:2.5}") private double breakoutConflictPenalty;
    @Value("${backtest.structural-score.mean-reversion-alignment-bonus:0.5}") private double meanReversionAlignmentBonus;
    @Value("${backtest.structural-score.mean-reversion-conflict-penalty:1}") private double meanReversionConflictPenalty;
    @Value("${backtest.structural-score.pullback-factor:0.5}") private double pullbackFactor;

    public void apply(StrategyEvaluationContext context, DynamicStrategyMeta meta,
                      DeterministicScoreCard card) {
        StructuralTrendSnapshot structural = context == null ? null : context.structuralTrend;
        if (!enabled || structural == null || !structural.ready
                || StructuralTrendSnapshot.NEUTRAL.equals(structural.direction)) {
            card.structuralAdjustmentReason = "STRUCTURAL_CONTEXT_NEUTRAL";
            return;
        }

        String expectedDirection = expectedDirection(context, meta);
        if (expectedDirection == null) {
            card.structuralAdjustmentReason = "STRATEGY_DIRECTION_UNRESOLVED";
            return;
        }

        boolean aligned = (StructuralTrendSnapshot.BULL.equals(structural.direction)
                && BUY.equals(expectedDirection))
                || (StructuralTrendSnapshot.BEAR.equals(structural.direction)
                && SELL.equals(expectedDirection));
        double magnitude = aligned ? alignmentBonus(meta.family) : conflictPenalty(meta.family);
        double phaseFactor = "PULLBACK".equals(structural.phase) ? clamp(pullbackFactor) : 1d;
        double confidenceFactor = clamp(structural.confidence);
        double signed = magnitude * phaseFactor * confidenceFactor * (aligned ? 1d : -1d);
        if (!Double.isFinite(signed)) signed = 0;

        card.structuralAdjustment = signed;
        card.structuralAdjustmentReason = aligned
                ? "STRUCTURAL_DIRECTION_ALIGNED" : "STRUCTURAL_DIRECTION_CONFLICT";
        if (signed > 0) card.supportingFactors.add(card.structuralAdjustmentReason);
        else if (signed < 0) card.penaltyFactors.add(card.structuralAdjustmentReason);
    }

    private double alignmentBonus(String family) {
        if ("TREND".equalsIgnoreCase(family)) return Math.max(0, trendAlignmentBonus);
        if ("BREAKOUT".equalsIgnoreCase(family))
            return Math.max(0, breakoutAlignmentBonus);
        return Math.max(0, meanReversionAlignmentBonus);
    }

    private double conflictPenalty(String family) {
        if ("TREND".equalsIgnoreCase(family)) return Math.max(0, trendConflictPenalty);
        if ("BREAKOUT".equalsIgnoreCase(family))
            return Math.max(0, breakoutConflictPenalty);
        return Math.max(0, meanReversionConflictPenalty);
    }

    private String expectedDirection(StrategyEvaluationContext context, DynamicStrategyMeta meta) {
        if (context == null || context.regime == null || context.technical == null || meta == null)
            return null;
        String family = meta.family == null ? "" : meta.family;
        if ("TREND".equalsIgnoreCase(family)) {
            if ("emaPullbackBuy".equalsIgnoreCase(meta.strategyName)
                    || "ethStructuralBullTrend".equalsIgnoreCase(meta.strategyName)
                    || "solMomentumBullTrend".equalsIgnoreCase(meta.strategyName)) return BUY;
            if ("UP".equals(context.regime.trend)) return BUY;
            if ("DOWN".equals(context.regime.trend)) return SELL;
            return null;
        }
        if ("BREAKOUT".equalsIgnoreCase(family)) {
            if ("UP".equals(context.regime.trend)) return BUY;
            if ("DOWN".equals(context.regime.trend)) return SELL;
            return context.technical.close >= context.technical.ema20 ? BUY : SELL;
        }
        if ("MEAN_REVERSION".equalsIgnoreCase(family)) {
            if (Math.abs(context.technical.zScore20) < .10) return null;
            return context.technical.zScore20 < 0 ? BUY : SELL;
        }
        return null;
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }
}
