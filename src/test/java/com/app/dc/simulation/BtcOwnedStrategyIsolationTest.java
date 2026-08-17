package com.app.dc.simulation;

import com.app.dc.strategy.core.strategy.btc.trend.BtcBullLaunchTrendBTC;
import com.app.dc.strategy.core.strategy.btc.trend.BtcStructuralBearTrendBTC;
import com.app.dc.strategy.core.strategy.btc.trend.BtcStructuralBullTrendBTC;
import com.app.dc.strategy.core.strategy.trend.BtcBullLaunchTrendStrategyAlgorithm;
import com.app.dc.strategy.core.strategy.trend.BtcStructuralBearTrendStrategyAlgorithm;
import com.app.dc.strategy.core.strategy.trend.BtcStructuralBullTrendStrategyAlgorithm;
import org.junit.Assert;
import org.junit.Test;

/** Prevents BTC lifecycle strategies from silently inheriting ETH/SOL implementations again. */
public class BtcOwnedStrategyIsolationTest {
    @Test
    public void btcLifecycleStrategiesUseCompositionInsteadOfCrossSymbolInheritance(){
        Assert.assertEquals(Object.class,BtcStructuralBullTrendStrategyAlgorithm.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcStructuralBearTrendStrategyAlgorithm.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcBullLaunchTrendStrategyAlgorithm.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcStructuralBullTrendBTC.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcStructuralBearTrendBTC.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcBullLaunchTrendBTC.class.getSuperclass());
    }
}
