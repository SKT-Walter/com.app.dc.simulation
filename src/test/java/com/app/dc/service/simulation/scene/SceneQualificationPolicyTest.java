package com.app.dc.service.simulation.scene;

import com.app.dc.service.simulation.BacktestModels;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;

public class SceneQualificationPolicyTest {

    @Test
    public void shouldPassOnlyMatureProfitableSceneEvidence() {
        BacktestModels.SceneShadowMetrics metrics = metrics();
        SceneQualificationPolicy.Decision decision = SceneQualificationPolicy.evaluate(metrics, 30, 5, 1.05, 0.25);

        Assert.assertTrue(decision.passed);
        Assert.assertTrue(decision.sufficient);
    }

    @Test
    public void shouldRejectNegativeScenePnl() {
        BacktestModels.SceneShadowMetrics metrics = metrics();
        metrics.totalPnl = BigDecimal.valueOf(-1D);

        SceneQualificationPolicy.Decision decision = SceneQualificationPolicy.evaluate(metrics, 30, 5, 1.05, 0.25);

        Assert.assertFalse(decision.passed);
        Assert.assertTrue(decision.sufficient);
        Assert.assertEquals("scene fee-adjusted pnl <= 0", decision.reason);
    }

    @Test
    public void shouldTreatMissingEvidenceAsInsufficient() {
        SceneQualificationPolicy.Decision decision = SceneQualificationPolicy.evaluate(null, 30, 5, 1.05, 0.25);

        Assert.assertFalse(decision.passed);
        Assert.assertFalse(decision.sufficient);
    }

    private static BacktestModels.SceneShadowMetrics metrics() {
        BacktestModels.SceneShadowMetrics metrics = new BacktestModels.SceneShadowMetrics();
        metrics.mode = "SCENE_GATED_QUALIFICATION";
        metrics.sceneRecordCount = 120;
        metrics.matchedBarCount = 500;
        metrics.tradeCount = 25;
        metrics.totalPnl = BigDecimal.valueOf(100D);
        metrics.profitFactor = BigDecimal.valueOf(1.50D);
        metrics.maxDrawdownPct = BigDecimal.valueOf(0.10D);
        return metrics;
    }
}
