package com.app.dc.simulation;

import com.app.dc.strategy.core.deterministic.CandidateSelectionResult;
import com.app.dc.strategy.core.deterministic.DeterministicScoreCard;
import com.app.dc.strategy.core.deterministic.DeterministicStrategyRouter;
import com.app.dc.strategy.core.deterministic.StrategyEvaluationContext;
import com.app.dc.strategy.core.deterministic.StrategyRoutingDecision;
import com.app.dc.strategy.core.deterministic.StrategyRoutingState;
import com.app.dc.strategy.core.dynamic.MarketRegime;
import com.app.dc.strategy.core.dynamic.DynamicStrategyMeta;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

public class DeterministicStrategyRouterTest {

    @Test
    public void activatesAfterConfirmationAndSwitchesOnlyAfterMarginAndHold() throws Exception {
        DeterministicStrategyRouter router = router(3, 4, 5, 10, 3);
        StrategyRoutingState state = router.newState();
        CandidateSelectionResult candidates = candidates("binanceTrend", "strongMomentumContinuation");

        Assert.assertEquals("AWAITING_ACTIVATION",
                route(router, state, 60, candidates, score("binanceTrend", 72, 67)).reason);
        Assert.assertEquals("AWAITING_ACTIVATION",
                route(router, state, 61, candidates, score("binanceTrend", 73, 67)).reason);
        StrategyRoutingDecision activated =
                route(router, state, 62, candidates, score("binanceTrend", 74, 67));
        Assert.assertEquals("ACTIVATED", activated.reason);
        Assert.assertEquals("binanceTrend", activated.strategyName);

        StrategyRoutingDecision smallGap = route(router, state, 66, candidates,
                score("binanceTrend", 74, 67), score("strongMomentumContinuation", 78, 67));
        Assert.assertEquals("CHALLENGER_MARGIN_NOT_MET", smallGap.reason);

        Assert.assertEquals("AWAITING_SWITCH", route(router, state, 67, candidates,
                score("binanceTrend", 72, 67), score("strongMomentumContinuation", 80, 67)).reason);
        Assert.assertEquals("AWAITING_SWITCH", route(router, state, 68, candidates,
                score("binanceTrend", 71, 67), score("strongMomentumContinuation", 81, 67)).reason);
        StrategyRoutingDecision switched = route(router, state, 69, candidates,
                score("binanceTrend", 70, 67), score("strongMomentumContinuation", 82, 67));
        Assert.assertEquals("SWITCHED", switched.reason);
        Assert.assertEquals("strongMomentumContinuation", switched.strategyName);
    }

    @Test
    public void candidateSetChangeDoesNotClearStillLegalActiveStrategy() throws Exception {
        DeterministicStrategyRouter router = router(1, 0, 5, 10, 3);
        StrategyRoutingState state = router.newState();
        route(router, state, 60, candidates("binanceTrend"), score("binanceTrend", 75, 67));
        StrategyRoutingDecision retained = route(router, state, 61,
                candidates("binanceTrend", "strongMomentumContinuation"),
                score("binanceTrend", 74, 67), score("strongMomentumContinuation", 70, 67));
        Assert.assertEquals("ACTIVE_RETAIN", retained.reason);
        Assert.assertEquals("binanceTrend", retained.strategyName);
    }

    @Test
    public void hardInvalidImmediatelyRemovesActiveStrategy() throws Exception {
        DeterministicStrategyRouter router = router(1, 4, 5, 10, 3);
        StrategyRoutingState state = router.newState();
        route(router, state, 60, candidates("binanceTrend"), score("binanceTrend", 75, 67));
        StrategyRoutingDecision invalid = route(router, state, 61,
                candidates("binanceRange"), score("binanceRange", 60, 65));
        Assert.assertEquals("HARD_INVALID", invalid.reason);
        Assert.assertNull(invalid.strategyName);
    }

    @Test
    public void exitsAfterConsecutiveScoresBelowRetentionThreshold() throws Exception {
        DeterministicStrategyRouter router = router(3, 0, 5, 10, 3);
        StrategyRoutingState state = router.newState();
        CandidateSelectionResult candidates = candidates("binanceTrend");
        route(router, state, 60, candidates, score("binanceTrend", 70, 67));
        route(router, state, 61, candidates, score("binanceTrend", 70, 67));
        route(router, state, 62, candidates, score("binanceTrend", 70, 67));
        Assert.assertEquals("LOW_SCORE_PENDING",
                route(router, state, 63, candidates, score("binanceTrend", 50, 67)).reason);
        Assert.assertEquals("LOW_SCORE_PENDING",
                route(router, state, 64, candidates, score("binanceTrend", 50, 67)).reason);
        StrategyRoutingDecision exited =
                route(router, state, 65, candidates, score("binanceTrend", 50, 67));
        Assert.assertEquals("LOW_SCORE_EXIT", exited.reason);
        Assert.assertNull(exited.strategyName);
    }

    @Test
    public void breakoutEventActivatesWithoutThreeBarDelay() throws Exception {
        DeterministicStrategyRouter router = router(3, 0, 5, 10, 3);
        StrategyRoutingState state = router.newState();
        DeterministicScoreCard breakout = score("compressionBreak", 82, 70);
        breakout.family = "BREAKOUT";

        StrategyRoutingDecision decision = route(router, state, 60,
                candidates("compressionBreak"), breakout);

        Assert.assertEquals("ACTIVATED", decision.reason);
        Assert.assertEquals("compressionBreak", decision.strategyName);
    }

    @Test
    public void lifecycleTriggerPriorityPreemptsHigherScoredHoldStrategyImmediately() throws Exception {
        DeterministicStrategyRouter router=router(3,4,5,10,3);
        StrategyRoutingState state=router.newState();
        CandidateSelectionResult candidates=candidates("atrChannelBiasReversion","ethStructuralBullTrend");
        routeWithPriority(router,state,60,candidates,null,
                score("atrChannelBiasReversion",90,65));
        routeWithPriority(router,state,61,candidates,null,
                score("atrChannelBiasReversion",90,65));
        routeWithPriority(router,state,62,candidates,null,
                score("atrChannelBiasReversion",90,65));
        StrategyRoutingDecision priority=routeWithPriority(router,state,63,candidates,
                "ethStructuralBullTrend",score("atrChannelBiasReversion",90,65),
                score("ethStructuralBullTrend",60,70));
        Assert.assertEquals("LIFECYCLE_TRIGGER_PRIORITY",priority.reason);
        Assert.assertEquals("ethStructuralBullTrend",priority.strategyName);
    }

    private StrategyRoutingDecision route(DeterministicStrategyRouter router, StrategyRoutingState state,
                                          int index, CandidateSelectionResult candidates,
                                          DeterministicScoreCard... scores) {
        MarketRegime regime = new MarketRegime();
        regime.tradeable = true;
        regime.trend = "UP";
        regime.volatility = "NORMAL";
        regime.confidence = .8;
        regime.barTime = index * 900000L;
        StrategyEvaluationContext context = new StrategyEvaluationContext(
                "ETHUSDT", "15M", index, null, null, regime, null);
        return router.route(state, context, candidates, Arrays.asList(scores));
    }

    private StrategyRoutingDecision routeWithPriority(DeterministicStrategyRouter router,StrategyRoutingState state,
                                                       int index,CandidateSelectionResult candidates,String priority,
                                                       DeterministicScoreCard... scores){
        MarketRegime regime=new MarketRegime();regime.tradeable=true;regime.trend="UP";
        regime.volatility="NORMAL";regime.confidence=.8;regime.barTime=index*900000L;
        StrategyEvaluationContext context=new StrategyEvaluationContext(
                "ETHUSDT","15M",index,null,null,regime,null);
        return router.route(state,context,candidates,Arrays.asList(scores),priority);
    }

    private CandidateSelectionResult candidates(String... names) {
        CandidateSelectionResult result = new CandidateSelectionResult();
        for (String name : names) {
            DynamicStrategyMeta meta = new DynamicStrategyMeta();
            meta.strategyName = name;
            result.candidates.add(meta);
        }
        return result;
    }

    private DeterministicScoreCard score(String name, double value, double threshold) {
        DeterministicScoreCard card = new DeterministicScoreCard();
        card.strategyName = name;
        card.family = "TREND";
        card.score = value;
        card.minimumScore = threshold;
        card.regimeScore = 16;
        return card;
    }

    private DeterministicStrategyRouter router(int confirmation, int hold, double margin,
                                               double retentionDelta, int lowScoreBars) throws Exception {
        DeterministicStrategyRouter router = new DeterministicStrategyRouter();
        set(router, "confirmationBars", confirmation);
        set(router, "minimumHoldBars", hold);
        set(router, "switchMargin", margin);
        set(router, "retentionScoreDelta", retentionDelta);
        set(router, "lowScoreConfirmationBars", lowScoreBars);
        return router;
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
