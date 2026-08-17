package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.trend.bull.BtcMultiTimeframeSnapshot;
import org.junit.Assert;
import org.junit.Test;

public class BtcStructuralBullTrendPositionExitPolicyTest {
    @Test
    public void protectsEarlyProfitAboveFeesWithoutClosingImmediately(){
        BtcStructuralBullTrendPositionExitPolicy policy=new BtcStructuralBullTrendPositionExitPolicy();
        Position position=new Position();position.side=Side.BUY;position.entryPrice=100;
        position.initialRiskPriceDistance=2;position.highestSinceEntry=102;
        position.stopPrice=98d;position.btcSoftStopPrice=96;
        BtcMultiTimeframeSnapshot snapshot=new BtcMultiTimeframeSnapshot("BULL",.75,"RUNNING",.9,1,
                96,103,1,20,101,100,.2,98,10,101,99,95,2,100,true,"TEST");
        PositionExitDecision decision=policy.evaluate(position,snapshot,101);
        Assert.assertFalse(decision.exit);
        Assert.assertEquals(100.1,position.stopPrice,.000001);
        Assert.assertEquals("btc_early_profit_protection_exit",position.stopExitReason);
    }

    @Test
    public void requiresTwoClosedOneHourInvalidations(){
        BtcStructuralBullTrendPositionExitPolicy policy=new BtcStructuralBullTrendPositionExitPolicy();
        Position position=new Position();position.side=Side.BUY;position.entryPrice=100;
        position.initialRiskPriceDistance=2;position.highestSinceEntry=100;
        position.stopPrice=97d;position.btcSoftStopPrice=98;
        BtcMultiTimeframeSnapshot first=snapshot(20,97,-.2);
        Assert.assertFalse(policy.evaluate(position,first,97).exit);
        BtcMultiTimeframeSnapshot second=snapshot(21,97,-.2);
        PositionExitDecision decision=policy.evaluate(position,second,97);
        Assert.assertTrue(decision.exit);
        Assert.assertEquals("btc_1h_soft_invalidation_exit",decision.reason);
    }

    private BtcMultiTimeframeSnapshot snapshot(int oneHourIndex,double close,double slope){
        return new BtcMultiTimeframeSnapshot("BULL",.75,"RUNNING",.9,1,96,103,1,
                oneHourIndex,close,99,slope,98,10,100,99,95,2,99,true,"TEST");
    }
}
