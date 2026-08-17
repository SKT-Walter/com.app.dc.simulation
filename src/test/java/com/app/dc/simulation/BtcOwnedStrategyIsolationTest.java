package com.app.dc.simulation;

import com.app.dc.service.simulation.strategy.btc.trend.BtcBullLaunchTrendBTC;
import com.app.dc.service.simulation.strategy.btc.trend.BtcStructuralBearTrendBTC;
import com.app.dc.service.simulation.strategy.btc.trend.BtcStructuralBullTrendBTC;
import com.app.dc.service.simulation.strategy.trend.BtcBullLaunchTrendBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.BtcStructuralBearTrendBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.BtcStructuralBullTrendBacktestStrategy;
import org.junit.Assert;
import org.junit.Test;

/** Prevents BTC lifecycle strategies from silently inheriting ETH/SOL implementations again. */
public class BtcOwnedStrategyIsolationTest {
    @Test
    public void btcLifecycleStrategiesUseCompositionInsteadOfCrossSymbolInheritance(){
        Assert.assertEquals(Object.class,BtcStructuralBullTrendBacktestStrategy.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcStructuralBearTrendBacktestStrategy.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcBullLaunchTrendBacktestStrategy.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcStructuralBullTrendBTC.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcStructuralBearTrendBTC.class.getSuperclass());
        Assert.assertEquals(Object.class,BtcBullLaunchTrendBTC.class.getSuperclass());
    }
}
