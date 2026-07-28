package com.app.dc.simulation;

import com.app.dc.service.simulation.deterministic.DeterministicScoreCard;
import com.app.dc.service.simulation.deterministic.StrategyEvaluationContext;
import com.app.dc.service.simulation.deterministic.StructuralTrendScoreModifier;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.deterministic.TechnicalSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.dynamic.DynamicStrategyMeta;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;

public class StructuralTrendScoreModifierTest {

    @Test
    public void alignedTrendGetsBonusAndConflictGetsPenalty() throws Exception {
        StructuralTrendScoreModifier modifier = modifier();
        DynamicStrategyMeta trend = meta("binanceTrend", "TREND");

        DeterministicScoreCard aligned = new DeterministicScoreCard();
        modifier.apply(context("UP", .8, StructuralTrendSnapshot.BULL, "ESTABLISHED", 1, 1), trend, aligned);
        Assert.assertEquals(2, aligned.structuralAdjustment, .000001);
        Assert.assertEquals("STRUCTURAL_DIRECTION_ALIGNED", aligned.structuralAdjustmentReason);

        DeterministicScoreCard conflict = new DeterministicScoreCard();
        modifier.apply(context("DOWN", .8, StructuralTrendSnapshot.BULL, "ESTABLISHED", 1, 1), trend, conflict);
        Assert.assertEquals(-3, conflict.structuralAdjustment, .000001);
        Assert.assertEquals("STRUCTURAL_DIRECTION_CONFLICT", conflict.structuralAdjustmentReason);
    }

    @Test
    public void pullbackAndConfidenceBoundTheAdjustment() throws Exception {
        StructuralTrendScoreModifier modifier = modifier();
        DeterministicScoreCard card = new DeterministicScoreCard();
        modifier.apply(context("UP", .8, StructuralTrendSnapshot.BULL, "PULLBACK", .75, 1),
                meta("binanceTrend", "TREND"), card);
        Assert.assertEquals(.75, card.structuralAdjustment, .000001);
    }

    @Test
    public void meanReversionUsesContrarianSetupDirectionWithoutBecomingHardRule() throws Exception {
        StructuralTrendScoreModifier modifier = modifier();
        DynamicStrategyMeta meanReversion = meta("bollingerMeanReversion", "MEAN_REVERSION");

        DeterministicScoreCard buyDip = new DeterministicScoreCard();
        modifier.apply(context("NONE", -1.2, StructuralTrendSnapshot.BULL, "ESTABLISHED", 1, 1),
                meanReversion, buyDip);
        Assert.assertEquals(.5, buyDip.structuralAdjustment, .000001);

        DeterministicScoreCard sellRally = new DeterministicScoreCard();
        modifier.apply(context("NONE", 1.2, StructuralTrendSnapshot.BULL, "ESTABLISHED", 1, 1),
                meanReversion, sellRally);
        Assert.assertEquals(-1, sellRally.structuralAdjustment, .000001);
    }

    @Test
    public void warmupContextDoesNotChangeScore() throws Exception {
        StructuralTrendScoreModifier modifier = modifier();
        DeterministicScoreCard card = new DeterministicScoreCard();
        modifier.apply(context("UP", .8, StructuralTrendSnapshot.NEUTRAL, "WARMUP", 0, 1),
                meta("binanceTrend", "TREND"), card);
        Assert.assertEquals(0, card.structuralAdjustment, 0);
        Assert.assertEquals("STRUCTURAL_CONTEXT_NEUTRAL", card.structuralAdjustmentReason);
    }

    private StructuralTrendScoreModifier modifier() throws Exception {
        StructuralTrendScoreModifier modifier = new StructuralTrendScoreModifier();
        set(modifier, "enabled", true);
        set(modifier, "trendAlignmentBonus", 2d);
        set(modifier, "trendConflictPenalty", 3d);
        set(modifier, "breakoutAlignmentBonus", 1.5d);
        set(modifier, "breakoutConflictPenalty", 2.5d);
        set(modifier, "meanReversionAlignmentBonus", .5d);
        set(modifier, "meanReversionConflictPenalty", 1d);
        set(modifier, "pullbackFactor", .5d);
        return modifier;
    }

    private DynamicStrategyMeta meta(String name, String family) {
        DynamicStrategyMeta meta = new DynamicStrategyMeta();
        meta.strategyName = name;
        meta.family = family;
        return meta;
    }

    private StrategyEvaluationContext context(String regimeTrend, double zScore,
                                              String structuralDirection, String phase,
                                              double confidence, double closeVsEma20) {
        BacktestRegime regime = new BacktestRegime();
        regime.trend = regimeTrend;
        regime.volatility = "NORMAL";
        regime.tradeable = true;
        TechnicalSnapshot technical = new TechnicalSnapshot(
                100, 102, 98, closeVsEma20 >= 0 ? 101 : 99, 100,
                1, .5, 25, 100, 100, 100, 100, 100,
                .001, 1, .02, .02, zScore, 50, 100, 100,
                105, 95, 104, 96, 102, 98,
                .5, .5, .5, .5, .5);
        StructuralTrendSnapshot structural = new StructuralTrendSnapshot(
                structuralDirection, structuralDirection, phase, confidence, 100, 0, true);
        return new StrategyEvaluationContext("ETHUSDT", "15m", 100,
                null, null, regime, technical, structural);
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
