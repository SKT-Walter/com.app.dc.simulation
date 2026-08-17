package com.app.dc.strategy.core.deterministic.scoring;

import com.app.dc.strategy.core.deterministic.StrategyEvaluationContext;
import com.app.dc.strategy.core.deterministic.StrategySetupScore;
import com.app.dc.strategy.core.deterministic.TechnicalSnapshot;
import org.junit.Assert;
import org.junit.Test;

public class VwapReversionSetupScorerTest {
    @Test
    public void recoveryConfirmationMateriallyIncreasesReadiness() {
        VwapReversionSetupScorer scorer = new VwapReversionSetupScorer();
        StrategySetupScore extending = scorer.score(context(102, 101.5, 102.5, 100, 100.2, 104, 101));
        StrategySetupScore recovering = scorer.score(context(102, 102.5, 102.5, 100, 99.5, 104, 101));

        Assert.assertTrue(Double.isFinite(extending.readiness));
        Assert.assertTrue(extending.readiness >= 0 && extending.readiness <= 1);
        Assert.assertTrue(recovering.readiness >= 0 && recovering.readiness <= 1);
        Assert.assertEquals(0.45, recovering.readiness - extending.readiness, 0.000001);
    }

    private StrategyEvaluationContext context(double close, double previousClose, double open,
                                              double vwap, double previousVwap,
                                              double previousHigh, double previousLow) {
        TechnicalSnapshot technical = new TechnicalSnapshot(
                open, Math.max(open, close) + 1, Math.min(open, close) - 1, close, previousClose,
                1, .5, 18, 100, 100, 100, 100, 100,
                0, 1, .02, .02, 1, 50, vwap, previousVwap,
                105, 95, 104, 96, previousHigh, previousLow,
                .5, .5, .2, .2, .2);
        return new StrategyEvaluationContext("ETHUSDT", "15m", 100,
                null, null, null, technical);
    }
}
