package com.app.dc.simulation;

import com.app.dc.service.simulation.strategy.trend.bull.SolBullLaunchConvictionPolicy;
import org.junit.Assert;
import org.junit.Test;

public class SolBullLaunchConvictionPolicyTest {
    private final SolBullLaunchConvictionPolicy policy=new SolBullLaunchConvictionPolicy();

    @Test public void blocksDeepBearAlignmentAndWeakEma60Reclaim(){
        Assert.assertFalse(policy.allows(100.6,98.0,100.0,2.0,2.5));
        Assert.assertFalse(policy.allows(100.4,99.5,100.0,2.0,.3));
    }

    @Test public void acceptsEstablishedReclaimOrStrongThreeBarMomentum(){
        Assert.assertTrue(policy.allows(101.2,99.5,100.0,2.0,.3));
        Assert.assertTrue(policy.allows(100.2,99.5,100.0,2.0,1.2));
    }
}
