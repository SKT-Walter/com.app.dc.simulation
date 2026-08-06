package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.deterministic.*;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.strategy.exit.LifecycleTrendPositionOwnershipPolicy;
import com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class LifecycleTrendPoliciesTest {
    @Test public void actionableEthTriggerCanTakePriorityFromAnotherStrategy(){
        LifecycleTrendPriorityPolicy policy=new LifecycleTrendPriorityPolicy();
        StrategyEvaluationContext context=context(true);
        DeterministicScoreCard eth=score("ethStructuralBullTrend",80,70);
        Assert.assertEquals("ethStructuralBullTrend",
                policy.priorityStrategy(context,Arrays.asList(eth),null));
        Assert.assertEquals("ethStructuralBullTrend",
                policy.priorityStrategy(context,Arrays.asList(eth),"binanceRange"));
        Assert.assertNull(policy.priorityStrategy(context,Arrays.asList(eth),"ethStructuralBullTrend"));
        eth.score=60;
        Assert.assertEquals("ethStructuralBullTrend",
                policy.priorityStrategy(context,Arrays.asList(eth),null));
        Assert.assertNull(policy.priorityStrategy(context,
                Arrays.asList(score("binanceRange",90,65)),null));
    }

    @Test public void nonTriggeredLifecycleStateCannotClaimPriority(){
        LifecycleTrendPriorityPolicy policy=new LifecycleTrendPriorityPolicy();
        Assert.assertNull(policy.priorityStrategy(context(false),
                Arrays.asList(score("ethStructuralBullTrend",90,70)),null));
    }

    @Test public void lifecyclePositionRejectsForeignButNotOwnerSignal(){
        LifecycleTrendPositionOwnershipPolicy policy=new LifecycleTrendPositionOwnershipPolicy();
        Position position=new Position();position.strategyName="ethStructuralBullTrend";
        Assert.assertTrue(policy.blocksForeignReversal(position,"donchianReversion"));
        Assert.assertFalse(policy.blocksForeignReversal(position,"ethStructuralBullTrend"));
        position.side=Side.BUY;
        Assert.assertTrue(policy.shouldHandoffSameDirection(position,"ethStructuralBearTrend",Side.BUY));
        Assert.assertFalse(policy.shouldHandoffSameDirection(position,"ethStructuralBullTrend",Side.BUY));
        Assert.assertFalse(policy.shouldHandoffSameDirection(position,"ethStructuralBearTrend",Side.SELL));
        position.strategyName="binanceRange";
        Assert.assertFalse(policy.blocksForeignReversal(position,"donchianReversion"));
    }

    private StrategyEvaluationContext context(boolean triggered){
        BacktestRegime regime=new BacktestRegime();regime.tradeable=true;regime.trend="UP";
        regime.volatility="NORMAL";regime.confidence=.8;
        BullTrendSnapshot eth=new BullTrendSnapshot("ethStructuralBullTrend",
                triggered?BullTrendSnapshot.TRIGGERED:BullTrendSnapshot.ARMED,"TEST",.9,
                triggered,95,"TEST",100);
        return new StrategyEvaluationContext("ETHUSDT","15M",100,null,null,regime,null,
                StructuralTrendSnapshot.warmup(),TrendCompressionSnapshot.none(),
                TrendLifecycleSnapshot.none(),eth,BullTrendSnapshot.none("solMomentumBullTrend"));
    }

    private DeterministicScoreCard score(String name,double value,double threshold){
        DeterministicScoreCard score=new DeterministicScoreCard();score.strategyName=name;
        score.family="TREND";score.score=value;score.minimumScore=threshold;return score;
    }
}
