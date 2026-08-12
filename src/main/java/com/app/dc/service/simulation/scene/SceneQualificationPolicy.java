package com.app.dc.service.simulation.scene;

import com.app.dc.service.simulation.BacktestModels;

import java.math.BigDecimal;

/**
 * The single qualification policy shared by scene replay and auto publish.
 * Missing or immature evidence is a hard fail; it must never be treated as a
 * profitable scene result by a caller that only checks the full-period result.
 */
public final class SceneQualificationPolicy {

    private SceneQualificationPolicy() {
    }

    public static Decision evaluate(BacktestModels.SceneShadowMetrics metrics,
                                    int minSceneRecords,
                                    int minTrades,
                                    double minProfitFactor,
                                    double maxDrawdownPct) {
        if (metrics == null) {
            return Decision.insufficient("scene replay result missing");
        }
        if (!"SCENE_GATED_QUALIFICATION".equals(metrics.mode)) {
            return Decision.insufficient("scene qualification mode missing");
        }
        if (metrics.sceneRecordCount == null || metrics.sceneRecordCount < Math.max(1, minSceneRecords)) {
            return Decision.insufficient("scene history records below threshold");
        }
        if (metrics.matchedBarCount == null || metrics.matchedBarCount <= 0) {
            return Decision.insufficient("no K-line interval matched the strategy scene");
        }
        if (metrics.tradeCount == null || metrics.tradeCount < Math.max(1, minTrades)) {
            return Decision.insufficient("scene-matched trades below threshold");
        }
        if (positive(metrics.totalPnl) <= 0) {
            return Decision.failed("scene fee-adjusted pnl <= 0");
        }
        if (decimal(metrics.profitFactor) < minProfitFactor) {
            return Decision.failed("scene profit factor below threshold");
        }
        if (decimal(metrics.maxDrawdownPct) > maxDrawdownPct) {
            return Decision.failed("scene drawdown above threshold");
        }
        return Decision.qualified();
    }

    private static double decimal(BigDecimal value) {
        return value == null ? 0D : value.doubleValue();
    }

    private static double positive(BigDecimal value) {
        return value == null ? 0D : value.doubleValue();
    }

    public static final class Decision {
        public final boolean passed;
        public final boolean sufficient;
        public final String reason;

        private Decision(boolean passed, boolean sufficient, String reason) {
            this.passed = passed;
            this.sufficient = sufficient;
            this.reason = reason;
        }

        public static Decision qualified() {
            return new Decision(true, true, "scene qualification passed");
        }

        public static Decision insufficient(String reason) {
            return new Decision(false, false, reason);
        }

        public static Decision failed(String reason) {
            return new Decision(false, true, reason);
        }
    }
}
