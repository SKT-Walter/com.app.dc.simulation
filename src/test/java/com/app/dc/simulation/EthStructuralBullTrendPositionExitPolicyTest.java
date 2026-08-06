package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.exit.EthStructuralBullTrendPositionExitPolicy;
import com.app.dc.service.simulation.strategy.exit.PositionExitDecision;
import com.app.dc.service.simulation.strategy.trend.bull.EthMultiTimeframeSnapshot;
import org.junit.Assert;
import org.junit.Test;

public class EthStructuralBullTrendPositionExitPolicyTest {
    @Test public void repeatedFifteenMinuteCallsCannotFakeFourHourConfirmation(){
        EthStructuralBullTrendPositionExitPolicy policy=new EthStructuralBullTrendPositionExitPolicy();
        Position p=position();
        EthMultiTimeframeSnapshot bear=snapshot("BEAR",100,400,95,96,97,2,98,96,5);
        Assert.assertFalse(policy.evaluate(p,bear).exit);
        Assert.assertFalse(policy.evaluate(p,bear).exit);
        Assert.assertEquals(1,p.ethFourHourBearBars);
        Assert.assertFalse(policy.evaluate(p,snapshot("BEAR",101,404,94,95,96,2,97,2,6)).exit);
        PositionExitDecision exit=policy.evaluate(p,snapshot("BEAR",102,405,93,94,95,2,96,2,7));
        Assert.assertTrue(exit.exit);
        Assert.assertEquals("eth_4h_bear_reversal_exit",exit.reason);
    }

    @Test public void threeRMovesProtectionToFeeCoveredBreakeven(){
        EthStructuralBullTrendPositionExitPolicy policy=new EthStructuralBullTrendPositionExitPolicy();
        Position p=position();p.highestSinceEntry=110;
        EthMultiTimeframeSnapshot newSwing=snapshot("BULL",102,400,105,104,103,2,99,2,101);
        policy.evaluate(p,newSwing);
        Assert.assertEquals(100.1d,p.stopPrice,.0001);
        Assert.assertEquals("eth_breakeven_protection_exit",p.stopExitReason);
    }

    @Test public void fourHourChandelierWaitsForMatureProfit(){
        EthStructuralBullTrendPositionExitPolicy policy=new EthStructuralBullTrendPositionExitPolicy();
        Position p=position();p.highestSinceEntry=115;
        policy.evaluate(p,snapshot("BULL",100,400,108,105,103,2,Double.NaN,2,-1));
        Assert.assertEquals(100.1d,p.stopPrice,.0001);
        p.highestSinceEntry=117;
        policy.evaluate(p,snapshot("BULL",101,401,112,106,104,2,Double.NaN,2,-1));
        Assert.assertEquals(106d,p.stopPrice,.0001);
        Assert.assertEquals("eth_4h_chandelier_exit",p.stopExitReason);
    }

    @Test public void softInvalidationRequiresTwoDistinctOneHourClosesAndWeakeningSlope(){
        EthStructuralBullTrendPositionExitPolicy policy=new EthStructuralBullTrendPositionExitPolicy();
        Position p=position();p.ethSoftStopPrice=98;
        EthMultiTimeframeSnapshot first=snapshot("BULL",100,400,97.5,99,103,2,Double.NaN,2,-1);
        Assert.assertFalse(policy.evaluate(p,first).exit);
        Assert.assertFalse(policy.evaluate(p,first).exit);
        Assert.assertEquals(1,p.ethSoftInvalidationBars);
        PositionExitDecision exit=policy.evaluate(p,snapshot("BULL",101,400,97,98.5,103,2,Double.NaN,2,-1));
        Assert.assertTrue(exit.exit);
        Assert.assertEquals("eth_1h_soft_invalidation_exit",exit.reason);
    }

    @Test public void softInvalidationResetsWhenOneHourSlopeRecovers(){
        EthStructuralBullTrendPositionExitPolicy policy=new EthStructuralBullTrendPositionExitPolicy();
        Position p=position();p.ethSoftStopPrice=98;
        Assert.assertFalse(policy.evaluate(p,snapshot("BULL",100,400,97,99,103,2,Double.NaN,2,-1)).exit);
        Assert.assertFalse(policy.evaluate(p,snapshotWithSlope("BULL",101,400,97,99,1,103,2,Double.NaN,2,-1)).exit);
        Assert.assertEquals(0,p.ethSoftInvalidationBars);
    }

    private Position position(){
        Position p=new Position();p.side=Side.BUY;p.strategyName="ethStructuralBullTrend";
        p.entryPrice=100;p.stopPrice=95d;p.initialRiskPriceDistance=2;p.highestSinceEntry=100;
        return p;
    }

    private EthMultiTimeframeSnapshot snapshot(String trend,int oneIndex,int fourIndex,
                                                double oneClose,double oneEma20,double oneEma60,double oneAtr,
                                                double swingLow,double fourAtr,int swingIndex){
        return snapshotWithSlope(trend,oneIndex,fourIndex,oneClose,oneEma20,-1,oneEma60,oneAtr,swingLow,fourAtr,swingIndex);
    }

    private EthMultiTimeframeSnapshot snapshotWithSlope(String trend,int oneIndex,int fourIndex,
                                                double oneClose,double oneEma20,double oneEma20Slope,double oneEma60,double oneAtr,
                                                double swingLow,double fourAtr,int swingIndex){
        return new EthMultiTimeframeSnapshot(trend,.8,EthMultiTimeframeSnapshot.RUNNING,.9,1,
                96,110,oneAtr,oneIndex,oneClose,oneEma20,oneEma20Slope,oneEma60,swingLow,swingIndex,
                fourIndex,oneClose,oneEma20,oneEma60,fourAtr,"TEST");
    }
}
