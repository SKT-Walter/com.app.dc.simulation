package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.exit.EthStructuralBearTrendPositionExitPolicy;
import com.app.dc.service.simulation.strategy.exit.PositionExitDecision;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeSnapshot;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

public class EthStructuralBearTrendPositionExitPolicyTest {
    @Test public void protectsProfitableShortAtBreakeven() throws Exception {
        Position p=position();p.lowestSinceEntry=90;
        PositionExitDecision d=evaluate(p,snapshot(EthBearMultiTimeframeSnapshot.BEAR,1,1),92);
        Assert.assertFalse(d.exit);Assert.assertEquals(99.9,p.stopPrice,.0001);
        Assert.assertEquals("eth_bear_breakeven_protection_exit",p.stopExitReason);
    }

    @Test public void exitsAfterThreeDistinctFourHourBullConfirmations() throws Exception {
        Position p=position();
        Assert.assertFalse(evaluate(p,snapshot(EthBearMultiTimeframeSnapshot.BULL,1,1),101).exit);
        Assert.assertFalse(evaluate(p,snapshot(EthBearMultiTimeframeSnapshot.BULL,2,2),101).exit);
        PositionExitDecision d=evaluate(p,snapshot(EthBearMultiTimeframeSnapshot.BULL,3,3),101);
        Assert.assertTrue(d.exit);Assert.assertEquals("eth_bear_4h_bull_reversal_exit",d.reason);
    }

    @Test public void waitsForMeaningfulWaveBeforeCapturingMfe() throws Exception {
        Position p=position();p.lowestSinceEntry=94;p.maxFavorableExcursionPct=.06;
        evaluate(p,snapshot(EthBearMultiTimeframeSnapshot.BEAR,1,1),95);
        Assert.assertEquals(108,p.stopPrice,.0001);
        p.lowestSinceEntry=91;p.maxFavorableExcursionPct=.09;
        PositionExitDecision d=evaluate(p,snapshot(EthBearMultiTimeframeSnapshot.BEAR,2,2),92);
        Assert.assertFalse(d.exit);Assert.assertEquals(95.5,p.stopPrice,.0001);
        Assert.assertEquals("eth_bear_staged_profit_lock_exit",p.stopExitReason);
    }

    @Test public void progressivelyTightensProfitLockWithoutCappingMatureWave() throws Exception {
        Position p=position();p.lowestSinceEntry=84;p.maxFavorableExcursionPct=.16;
        evaluate(p,snapshot(EthBearMultiTimeframeSnapshot.BEAR,1,1),84);
        Assert.assertEquals(88.784,p.stopPrice,.0001);
        Assert.assertEquals("eth_bear_staged_profit_lock_exit",p.stopExitReason);
        p.lowestSinceEntry=68;p.maxFavorableExcursionPct=.32;
        evaluate(p,snapshot(EthBearMultiTimeframeSnapshot.BEAR,2,2),68);
        Assert.assertEquals(70.56,p.stopPrice,.0001);
        Assert.assertEquals("eth_bear_mfe_capture_exit",p.stopExitReason);
    }

    @Test public void solTopWaveCapturesNinetyPercentAfterEightPercentMfe() throws Exception {
        Position p=position();p.strategyName="solStructuralBearTrendSOL";
        p.lowestSinceEntry=91;p.maxFavorableExcursionPct=.09;
        PositionExitDecision d=evaluate(p,snapshot(EthBearMultiTimeframeSnapshot.BEAR,1,1),91);
        Assert.assertFalse(d.exit);Assert.assertEquals(91.9,p.stopPrice,.0001);
        Assert.assertEquals("sol_bear_fast_mfe_capture_exit",p.stopExitReason);
        Assert.assertEquals("SOL_BEAR_FAST_MFE_CAPTURE",p.exitLifecyclePhase);
    }

    private Position position(){Position p=new Position();p.side=Side.SELL;p.entryPrice=100;p.stopPrice=108d;
        p.initialRiskPriceDistance=4;p.entryAtr=2;p.lowestSinceEntry=98;p.ethBearSoftStopPrice=104;return p;}
    private EthBearMultiTimeframeSnapshot snapshot(String trend,int h4,int h1){return new EthBearMultiTimeframeSnapshot(
            trend,.8,EthBearMultiTimeframeSnapshot.RUNNING,.9,1,104,90,2,h1,98,99,-1,102,
            h4,98,99,101,3,"RUNNING");}
    private PositionExitDecision evaluate(Position p,EthBearMultiTimeframeSnapshot s,double close)throws Exception{
        EthStructuralBearTrendPositionExitPolicy policy=new EthStructuralBearTrendPositionExitPolicy();
        Method m=EthStructuralBearTrendPositionExitPolicy.class.getDeclaredMethod("evaluate",Position.class,EthBearMultiTimeframeSnapshot.class,double.class);
        m.setAccessible(true);return (PositionExitDecision)m.invoke(policy,p,s,close);
    }
}
