package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.strategy.core.StrategyRuntimeModels.Position;
import com.app.dc.strategy.core.deterministic.*;
import com.app.dc.strategy.core.dynamic.MarketRegime;
import com.app.dc.strategy.core.strategy.exit.LifecycleTrendPositionOwnershipPolicy;
import com.app.dc.strategy.core.strategy.trend.TrendLifecycleSnapshot;
import com.app.dc.strategy.core.strategy.trend.bull.BullTrendSnapshot;
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

    @Test public void actionableSolTriggerTakesPriorityFromOrdinaryStrategy(){
        LifecycleTrendPriorityPolicy policy=new LifecycleTrendPriorityPolicy();
        StrategyEvaluationContext context=solContext(true);
        Assert.assertEquals("solMomentumBullTrendSOL",policy.priorityStrategy(context,
                Arrays.asList(score("solMomentumBullTrendSOL",80,70)),"binanceRangeSOL"));
        Assert.assertNull(policy.priorityStrategy(solContext(false),
                Arrays.asList(score("solMomentumBullTrendSOL",90,70)),"binanceRangeSOL"));
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
        position.strategyName="solBullLaunchTrendSOL";
        Assert.assertTrue(policy.blocksForeignReversal(position,"binanceRangeSOL"));
        Assert.assertFalse(policy.shouldHandoffSameDirection(position,
                "solMomentumBullTrendSOL",Side.BUY));
    }

    @Test public void actionableSolLaunchHasPriorityButMatureTriggerWinsTie(){
        LifecycleTrendPriorityPolicy policy=new LifecycleTrendPriorityPolicy();
        StrategyEvaluationContext launch=solLaunchContext(false,true);
        Assert.assertEquals("solBullLaunchTrendSOL",policy.priorityStrategy(launch,
                Arrays.asList(score("solBullLaunchTrendSOL",80,67)),null));
        StrategyEvaluationContext both=solLaunchContext(true,true);
        Assert.assertEquals("solMomentumBullTrendSOL",policy.priorityStrategy(both,
                Arrays.asList(score("solMomentumBullTrendSOL",80,67),
                        score("solBullLaunchTrendSOL",90,67)),null));
    }

    private StrategyEvaluationContext context(boolean triggered){
        MarketRegime regime=new MarketRegime();regime.tradeable=true;regime.trend="UP";
        regime.volatility="NORMAL";regime.confidence=.8;
        BullTrendSnapshot eth=new BullTrendSnapshot("ethStructuralBullTrend",
                triggered?BullTrendSnapshot.TRIGGERED:BullTrendSnapshot.ARMED,"TEST",.9,
                triggered,95,"TEST",100);
        return new StrategyEvaluationContext("ETHUSDT","15M",100,null,null,regime,null,
                StructuralTrendSnapshot.warmup(),TrendCompressionSnapshot.none(),
                TrendLifecycleSnapshot.none(),eth,BullTrendSnapshot.none("solMomentumBullTrend"));
    }

    private StrategyEvaluationContext solContext(boolean triggered){
        MarketRegime regime=new MarketRegime();regime.tradeable=true;regime.trend="UP";
        regime.volatility="NORMAL";regime.confidence=.8;
        BullTrendSnapshot sol=new BullTrendSnapshot("solMomentumBullTrend",
                triggered?BullTrendSnapshot.TRIGGERED:BullTrendSnapshot.ARMED,"TEST",.9,
                triggered,95,"TEST",100);
        return new StrategyEvaluationContext("SOLUSDT","15M",100,null,null,regime,null,
                StructuralTrendSnapshot.warmup(),TrendCompressionSnapshot.none(),
                TrendLifecycleSnapshot.none(),BullTrendSnapshot.none("ethStructuralBullTrend"),sol);
    }

    private StrategyEvaluationContext solLaunchContext(boolean matureTriggered,boolean launchTriggered){
        MarketRegime regime=new MarketRegime();regime.tradeable=true;regime.trend="UP";
        regime.volatility="NORMAL";regime.confidence=.8;
        BullTrendSnapshot mature=new BullTrendSnapshot("solMomentumBullTrend",
                matureTriggered?BullTrendSnapshot.TRIGGERED:BullTrendSnapshot.OBSERVING,
                "TEST",.9,matureTriggered,95,"MATURE",100);
        BullTrendSnapshot launch=new BullTrendSnapshot("solBullLaunchTrend",
                launchTriggered?BullTrendSnapshot.TRIGGERED:BullTrendSnapshot.OBSERVING,
                "TEST",.9,launchTriggered,95,"LAUNCH",100);
        return new StrategyEvaluationContext("SOLUSDT","15M",100,null,null,regime,null,
                StructuralTrendSnapshot.warmup(),TrendCompressionSnapshot.none(),
                TrendLifecycleSnapshot.none(),BullTrendSnapshot.none("ethStructuralBullTrend"),
                mature,launch,null);
    }

    private DeterministicScoreCard score(String name,double value,double threshold){
        DeterministicScoreCard score=new DeterministicScoreCard();score.strategyName=name;
        score.family="TREND";score.score=value;score.minimumScore=threshold;return score;
    }
}
