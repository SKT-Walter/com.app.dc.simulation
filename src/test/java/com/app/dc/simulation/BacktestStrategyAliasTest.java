package com.app.dc.simulation;

import com.app.dc.service.simulation.BacktestSupportService;
import org.junit.Assert;
import org.junit.Test;

public class BacktestStrategyAliasTest {

    private final BacktestSupportService support = new BacktestSupportService();

    @Test
    public void allLegacyBaseRangeNamesResolveToBinanceRange() {
        Assert.assertEquals("binanceRange", support.normalizeStrategyName("range"));
        Assert.assertEquals("binanceRange", support.normalizeStrategyName("binanceRange"));
        Assert.assertEquals("binanceRange", support.normalizeStrategyName("binanceRangeGuarded"));
        Assert.assertEquals("binanceRange", support.normalizeStrategyName("grid"));
        Assert.assertEquals("binanceRange", support.normalizeStrategyName("gridRange"));
    }

    @Test
    public void binanceRangeMacdRemainsIndependent() {
        Assert.assertEquals("binanceRangeMacd",
                support.normalizeStrategyName("binanceRangeMacd"));
        Assert.assertEquals("binanceRangeMacd",
                support.normalizeStrategyName("range_macd"));
    }

    @Test public void independentBullTrendAliasesRemainDistinct(){
        Assert.assertEquals("ethStructuralBullTrend",support.normalizeStrategyName("ethbull"));
        Assert.assertEquals("solMomentumBullTrend",support.normalizeStrategyName("solbull"));
        Assert.assertEquals("solBullLaunchTrend",support.normalizeStrategyName("sollaunch"));
    }

    @Test public void ethBearTrendAliasesResolveToIndependentStrategy(){
        Assert.assertEquals("ethStructuralBearTrend",support.normalizeStrategyName("ethbear"));
        Assert.assertEquals("ethStructuralBearTrend",support.normalizeStrategyName("eth_structural_bear"));
    }
}
