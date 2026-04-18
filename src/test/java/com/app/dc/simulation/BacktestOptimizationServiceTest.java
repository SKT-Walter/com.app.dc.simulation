package com.app.dc.simulation;

import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestOptimizationService;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class BacktestOptimizationServiceTest {

    private final BacktestOptimizationService service = new BacktestOptimizationService();

    @Test
    public void testProfitFirstRankingRejectsWeakForwardContribution() {
        BacktestOptimizationService.OptimizationPlan plan = service.buildPlan("{"
                + "\"optimizationSupported\":true,"
                + "\"defaultParams\":{\"lookback\":20},"
                + "\"parameterSchema\":{\"parameters\":[{\"name\":\"lookback\",\"type\":\"int\",\"candidates\":[10,20,30]}]},"
                + "\"optimizationProfile\":{\"mode\":\"RANDOM_LOCAL\",\"objective\":\"PROFIT_FIRST\",\"minForwardContribution\":0.20}"
                + "}");

        BacktestModels.OptimizationTrial weakForward = trial(1, "COARSE", 100, 99, 1, 100, 1, 10);
        BacktestModels.OptimizationTrial balanced = trial(2, "COARSE", 80, 20, 60, 80, 1, 9);

        List<BacktestModels.OptimizationTrial> trials = java.util.Arrays.asList(weakForward, balanced);
        service.rankTrials(plan, trials);

        Assert.assertEquals(Integer.valueOf(1), balanced.rank);
        Assert.assertEquals(Integer.valueOf(2), weakForward.rank);
    }

    @Test
    public void testRandomLocalBuildsUniqueCoarseCandidates() {
        BacktestOptimizationService.OptimizationPlan plan = service.buildPlan("{"
                + "\"optimizationSupported\":true,"
                + "\"defaultParams\":{\"a\":2,\"b\":0.5},"
                + "\"parameterSchema\":{\"parameters\":["
                + "{\"name\":\"a\",\"type\":\"int\",\"candidates\":[1,2,3,4]},"
                + "{\"name\":\"b\",\"type\":\"double\",\"candidates\":[0.3,0.5,0.7]}"
                + "]},"
                + "\"optimizationProfile\":{\"mode\":\"RANDOM_LOCAL\",\"maxCoarseCandidates\":8,\"randomSeed\":7}"
                + "}");

        List<Map<String, Object>> coarse = service.buildCoarseParamSets(plan);
        Assert.assertFalse(coarse.isEmpty());
        Assert.assertTrue(coarse.size() <= 8);
        Assert.assertEquals(coarse.size(), new java.util.LinkedHashSet<Map<String, Object>>(coarse).size());
    }

    private BacktestModels.OptimizationTrial trial(int trialNo,
                                                   String phase,
                                                   double fitPnl,
                                                   double validatePnl,
                                                   double forwardPnl,
                                                   double totalPnl,
                                                   int overfitPass,
                                                   double forwardScore) {
        BacktestModels.OptimizationTrial trial = new BacktestModels.OptimizationTrial();
        trial.trialNo = trialNo;
        trial.phase = phase;
        trial.fitPnl = BigDecimal.valueOf(fitPnl);
        trial.validatePnl = BigDecimal.valueOf(validatePnl);
        trial.forwardPnl = BigDecimal.valueOf(forwardPnl);
        trial.totalPnl = BigDecimal.valueOf(totalPnl);
        trial.overfitPass = overfitPass;
        trial.forwardScore = BigDecimal.valueOf(forwardScore);
        trial.maxDrawdownPct = BigDecimal.ONE;
        trial.paramSetJson = "{}";
        return trial;
    }
}
