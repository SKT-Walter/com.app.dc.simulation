package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.exit.BullTrendPositionExitPolicy;
import com.app.dc.service.simulation.strategy.exit.PositionExitDecision;
import com.app.dc.service.simulation.strategy.trend.bull.EthMultiTimeframeSnapshot;
import org.junit.Assert;
import org.junit.Test;

public class BullTrendPositionExitPolicyTest {
    @Test public void ordinaryFifteenMinutePullbackDoesNotExitSolCampaign(){
        BullTrendPositionExitPolicy policy=new BullTrendPositionExitPolicy();
        Position p=position();
        PositionExitDecision decision=policy.evaluate(p,snapshot("BULL",1,1,110,4),105);
        Assert.assertFalse(decision.exit);
        Assert.assertNull(p.stopExitReason);
    }

    @Test public void threeRProtectsBreakevenAndFourHourTrailOnlyTightens(){
        BullTrendPositionExitPolicy policy=new BullTrendPositionExitPolicy();
        Position p=position();p.highestSinceEntry=108;
        policy.evaluate(p,snapshot("BULL",1,1,106,4),106);
        Assert.assertEquals(100.1,p.stopPrice,0.000001);
        p.highestSinceEntry=125;
        policy.evaluate(p,snapshot("BULL",2,2,120,4),120);
        Assert.assertEquals(101,p.stopPrice,0.000001);
        double tightened=p.stopPrice;
        p.highestSinceEntry=120;
        policy.evaluate(p,snapshot("BULL",3,3,116,5),116);
        Assert.assertTrue(p.stopPrice>=tightened);
    }

    @Test public void requiresConfirmedHigherTimeframeInvalidation(){
        BullTrendPositionExitPolicy policy=new BullTrendPositionExitPolicy();
        Position p=position();
        Assert.assertFalse(policy.evaluate(p,snapshot("BEAR",1,1,95,4),95).exit);
        PositionExitDecision fourHour=policy.evaluate(p,snapshot("BEAR",2,2,94,4),94);
        Assert.assertTrue(fourHour.exit);
        Assert.assertEquals("sol_4h_bear_reversal_exit",fourHour.reason);

        p=position();p.solSoftStopPrice=98;
        Assert.assertFalse(policy.evaluate(p,snapshot("BULL",1,1,97,4),97).exit);
        PositionExitDecision oneHour=policy.evaluate(p,snapshot("BULL",1,2,96,4),96);
        Assert.assertTrue(oneHour.exit);
        Assert.assertEquals("sol_1h_structure_invalidation_exit",oneHour.reason);
    }

    @Test public void provenProfitIgnoresOneHourNoiseAndBecomesFourHourCore(){
        BullTrendPositionExitPolicy policy=new BullTrendPositionExitPolicy();
        Position p=position();p.solSoftStopPrice=98;p.highestSinceEntry=109;
        PositionExitDecision locked=policy.evaluate(p,snapshot("BULL",1,1,100.5,4),100.5);
        Assert.assertFalse(locked.exit);
        Assert.assertEquals("SOL_4H_CORE_HOLD",p.exitLifecyclePhase);
        Assert.assertEquals("sol_breakeven_protection_exit",p.stopExitReason);
        Assert.assertTrue(p.stopPrice>=100.1);
        Assert.assertFalse(policy.evaluate(p,snapshot("BULL",2,2,110,2),110).exit);
        Assert.assertEquals("sol_4h_core_trailing_exit",p.stopExitReason);
        Assert.assertEquals(102.5,p.stopPrice,0.000001);
    }

    @Test public void threeRWithoutEightPercentStillRequiresTwoInvalidBars(){
        BullTrendPositionExitPolicy policy=new BullTrendPositionExitPolicy();
        Position p=position();p.solSoftStopPrice=98;p.highestSinceEntry=107.6;
        Assert.assertFalse(policy.evaluate(p,snapshot("BULL",1,1,100.5,4),100.5).exit);
        PositionExitDecision confirmed=policy.evaluate(p,snapshot("BULL",1,2,100.4,4),100.4);
        Assert.assertTrue(confirmed.exit);
        Assert.assertEquals("sol_1h_structure_invalidation_exit",confirmed.reason);
    }

    private Position position(){
        Position p=new Position();p.side=Side.BUY;p.strategyName="solMomentumBullTrendSOL";
        p.entryPrice=100;p.entryIndex=0;p.entryAtr=1;p.initialRiskPriceDistance=4;
        p.stopPrice=94d;p.highestSinceEntry=100;return p;
    }

    private EthMultiTimeframeSnapshot snapshot(String trend,int fourHourIndex,int oneHourIndex,
                                                double close,double atr){
        return new EthMultiTimeframeSnapshot(trend,.8,EthMultiTimeframeSnapshot.RUNNING,.9,1,
                98,110,2,oneHourIndex,close,100,-1,101,98,oneHourIndex-1,
                fourHourIndex,close,105,100,atr,"TEST");
    }
}
