package com.app.dc.service.simulation;

import org.junit.Assert;
import org.junit.Test;
import java.math.BigDecimal;

public class LocalHoldoutReplayTest {
    @Test
    public void rejectsPositiveGrossPnlWithoutCostAdjustedOrSufficientEvidence() {
        BacktestModels.BacktestResult result = new BacktestModels.BacktestResult();
        result.tradeCount = 3;
        result.profitFactor = new BigDecimal("1.20");
        result.maxDrawdownPct = new BigDecimal("0.15");
        Assert.assertTrue(LocalHoldoutReplay.passes(result, BigDecimal.ONE));
        Assert.assertFalse(LocalHoldoutReplay.passes(result, BigDecimal.ZERO));
        result.tradeCount = 2;
        Assert.assertFalse(LocalHoldoutReplay.passes(result, BigDecimal.ONE));
        result.tradeCount = 3;
        result.maxDrawdownPct = new BigDecimal("0.151");
        Assert.assertFalse(LocalHoldoutReplay.passes(result, BigDecimal.ONE));
    }
}
