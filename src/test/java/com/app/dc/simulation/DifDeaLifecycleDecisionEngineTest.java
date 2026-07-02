package com.app.dc.simulation;

import com.app.dc.service.simulation.strategy.lifecycle.DifDeaLifecycleDecisionEngine;
import com.app.dc.service.simulation.strategy.lifecycle.DifDeaLifecycleBacktestStrategy;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleConfig;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleConfigProvider;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleContext;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleDecision;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleDecisionType;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleDirection;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleIndicatorSample;
import com.app.dc.service.simulation.strategy.lifecycle.LifecyclePhase;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleState;
import com.app.dc.service.simulation.strategy.lifecycle.PendingSource;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 验证生命周期策略在交叉密度过滤和低MACD蓄势观察下的决策行为。
 */
public class DifDeaLifecycleDecisionEngineTest {

    private final DifDeaLifecycleDecisionEngine engine = new DifDeaLifecycleDecisionEngine();

    /**
     * 验证金叉时最近3根里有2根MACD偏低，交叉进入观察态。
     */
    @Test
    public void shouldEnterPendingLongWhenMacdIsFlatBeforeCross() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.80, -0.20, 0.30, 100.0, 0));
        samples.add(sample(1, -0.70, -0.18, 0.25, 100.5, 0));
        samples.add(sample(2, -0.55, -0.12, 0.15, 101.0, 0));
        samples.add(sample(3, -0.40, -0.08, 0.10, 101.5, 0));
        samples.add(sample(4, 0.20, -0.20, 0.10, 102.0, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_low_macd", decision.getReason());
    }

    /**
     * 验证最近12根交叉次数达到3次时，进入观察态等待MACD突破。
     */
    @Test
    public void shouldEnterPendingWhenCrossDensityIsHigh() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.50, -0.20, 0.20, 100.0, 2));
        samples.add(sample(1, -0.40, -0.18, 0.10, 100.5, 2));
        samples.add(sample(2, -0.30, -0.12, 0.30, 101.0, 2));
        samples.add(sample(3, -0.20, -0.08, 0.40, 101.5, 2));
        samples.add(sample(4, 0.05, 0.00, 0.45, 102.0, 3));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_cross_density", decision.getReason());
    }

    /**
     * 验证金叉开多候选遇到最近K线净走低时进入观察态。
     */
    @Test
    public void shouldBlockLongEntryWhenRecentCloseMovesDown() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.50, -0.20, -0.30, 104.0, 0));
        samples.add(sample(1, -0.40, -0.15, -0.25, 103.0, 0));
        samples.add(sample(2, -0.20, 0.00, 0.22, 102.0, 0));
        samples.add(sample(3, 0.10, 0.00, 0.10, 101.0, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_counter_trend", decision.getReason());
    }

    /**
     * 验证金叉成立但 MA5 向上、MA10 连续 3 根向下时，不直接开多。
     */
    @Test
    public void shouldBlockLongEntryWhenMa5RisesButMa10StillFalls() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, -0.60, -0.20, -0.60, 100.0, 99.8, 101.2, 99.5, 0, 99.0, 104.0));
        samples.add(sampleWithMa(1, -0.50, -0.18, -0.30, 99.7, 99.9, 100.1, 99.4, 0, 98.8, 103.5));
        samples.add(sampleWithMa(2, -0.30, -0.10, -0.10, 99.9, 99.7, 100.2, 99.5, 0, 99.0, 103.0));
        samples.add(sampleWithMa(3, 0.10, 0.00, 0.20, 100.1, 99.9, 100.3, 99.8, 1, 99.2, 102.5));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_ma_counter_trend", decision.getReason());
    }

    /**
     * 验证金叉开多候选未遇到最近K线净走低时仍可开多。
     */
    @Test
    public void shouldEnterLongWhenRecentCloseDoesNotMoveDown() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, -0.50, -0.20, -0.30, 100.0, 100.2, 100.4, 99.8, 0, 100.0, 101.5));
        samples.add(sampleWithMa(1, -0.60, -0.15, -0.25, 101.0, 101.2, 101.4, 100.8, 0, 100.1, 101.4));
        samples.add(sampleWithMa(2, -0.20, 0.00, 0.22, 102.0, 101.8, 102.2, 101.6, 0, 100.3, 101.5));
        samples.add(sampleWithMa(3, 0.20, 0.00, 0.19, 103.0, 102.8, 103.2, 102.6, 1, 100.5, 101.6));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.ENTER_LONG, decision.getType());
        Assert.assertEquals("dif_dea_cross_up", decision.getReason());
    }

    /**
     * 验证金叉成立且最近3根里至少2根MACD偏低时不会直接开多。
     */
    @Test
    public void shouldBlockLongEntryWhenCrossIsWeak() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.60, -0.20, -0.60, 99.5, 0));
        samples.add(sample(1, -0.40, -0.20, -0.20, 100.0, 0));
        samples.add(sample(2, -0.20, -0.10, -0.10, 100.5, 0));
        samples.add(sample(3, -0.05, 0.00, 0.10, 101.0, 0));
        samples.add(sample(4, 0.20, 0.00, 0.17, 101.5, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_low_macd", decision.getReason());
    }

    /**
     * 验证空仓金叉开多遇到单根K线大波幅时进入观察态。
     */
    @Test
    public void shouldBlockLongEntryWhenCurrentBarRangeIsLarge() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.50, -0.20, -0.30, 100.0, 0));
        samples.add(sample(1, -0.40, -0.15, -0.25, 101.0, 0));
        samples.add(sample(2, -0.20, 0.00, 0.22, 102.0, 0));
        samples.add(sampleWithRange(3, 0.10, 0.00, 0.60, 103.0, 100.0, 102.0, 100.4, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_large_bar_range", decision.getReason());
    }

    /**
     * 验证空仓金叉开多遇到低波幅K线时不被大波幅过滤拦截。
     */
    @Test
    public void shouldEnterLongWhenCurrentBarRangeIsSmall() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.50, -0.20, -0.30, 100.0, 0));
        samples.add(sample(1, -0.40, -0.15, -0.25, 101.0, 0));
        samples.add(sample(2, -0.20, 0.00, 0.22, 102.0, 0));
        samples.add(sampleWithRange(3, 0.20, 0.00, 0.60, 103.0, 100.0, 100.8, 99.6, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.ENTER_LONG, decision.getType());
        Assert.assertEquals("dif_dea_cross_up", decision.getReason());
    }

    /**
     * 验证金叉若属于大幅拉升后硬拽出来的突发交叉，则进入观察态而不是直接开多。
     */
    @Test
    public void shouldBlockLongEntryWhenCrossIsCreatedBySurgeUp() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.20, -0.10, -0.20, 100.0, 0));
        samples.add(sample(1, -0.22, -0.08, -0.30, 99.8, 0));
        samples.add(sample(2, -0.26, -0.06, -0.70, 99.5, 0));
        samples.add(sampleWithRange(3, -0.10, -0.05, -0.60, 100.8, 100.0, 101.0, 100.0, 0));
        samples.add(sample(4, 0.10, 0.00, 0.60, 101.2, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_surge_cross", decision.getReason());
    }

    /**
     * 验证金叉前4根 MACD 在后两根突然放大时，不直接开多。
     */
    @Test
    public void shouldBlockLongEntryWhenMacdSuddenlyExpandsInLastTwoBars() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.60, -0.30, -0.30, 100.0, 0));
        samples.add(sample(1, -0.45, -0.25, -0.15, 100.6, 0));
        samples.add(sample(2, -0.10, -0.15, 0.55, 101.8, 0));
        samples.add(sample(3, 0.20, 0.00, 0.95, 102.6, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_sudden_macd_expansion", decision.getReason());
    }

    /**
     * 验证死叉开空候选遇到最近K线净走高时进入观察态。
     */
    @Test
    public void shouldBlockShortEntryWhenRecentCloseMovesUp() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.50, 0.20, 0.30, 100.0, 0));
        samples.add(sample(1, 0.40, 0.15, 0.25, 101.0, 0));
        samples.add(sample(2, 0.20, 0.00, 0.20, 102.0, 0));
        samples.add(sample(3, -0.10, 0.00, -0.10, 103.0, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_counter_trend", decision.getReason());
    }

    /**
     * 验证死叉成立但 MA5 向下、MA10 连续 3 根向上时，不直接开空。
     */
    @Test
    public void shouldBlockShortEntryWhenMa5FallsButMa10StillRises() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.60, 0.20, 0.60, 105.0, 105.2, 105.4, 104.8, 0, 104.0, 100.0));
        samples.add(sampleWithMa(1, 0.50, 0.18, 0.30, 104.7, 104.9, 105.1, 104.5, 0, 103.8, 100.5));
        samples.add(sampleWithMa(2, 0.30, 0.10, 0.10, 104.5, 104.7, 104.9, 104.3, 0, 103.6, 101.0));
        samples.add(sampleWithMa(3, -0.10, 0.00, -0.20, 104.3, 104.5, 104.6, 104.1, 1, 103.4, 101.5));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_ma_counter_trend", decision.getReason());
    }

    /**
     * 验证空仓死叉开空遇到单根K线大波幅时进入观察态。
     */
    @Test
    public void shouldBlockShortEntryWhenCurrentBarRangeIsLarge() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.50, 0.20, 0.30, 104.0, 0));
        samples.add(sample(1, 0.40, 0.15, 0.25, 103.0, 0));
        samples.add(sample(2, 0.20, 0.00, 0.20, 102.0, 0));
        samples.add(sampleWithRange(3, -0.10, 0.00, -0.60, 101.0, 100.0, 102.0, 100.4, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_large_bar_range", decision.getReason());
    }

    /**
     * 验证死叉开空候选未遇到最近K线净走高时仍可开空。
     */
    /**
     * 验证观察态切换到新的空头周期时，大波幅K线也会拦截开空。
     */
    @Test
    public void shouldBlockShortEntryWhenReplacingPendingAndCurrentBarRangeIsLarge() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_LONG_LAUNCH);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.50, 0.20, 0.30, 104.0, 0));
        samples.add(sample(1, 0.40, 0.15, 0.25, 103.0, 0));
        samples.add(sample(2, 0.20, 0.00, 0.20, 102.0, 0));
        samples.add(sampleWithRange(3, -0.10, 0.00, -0.60, 101.0, 100.0, 102.0, 100.4, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_large_bar_range", decision.getReason());
    }

    /**
     * 验证 pending 被新多头周期替代时，也会命中 MA 反向结构过滤。
     */
    @Test
    public void shouldBlockLongEntryByMaCounterTrendWhenReplacingPending() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_SHORT_LAUNCH);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, -0.60, -0.20, -0.60, 100.0, 99.8, 101.2, 99.5, 0, 99.0, 104.0));
        samples.add(sampleWithMa(1, -0.50, -0.18, -0.30, 99.7, 99.9, 100.1, 99.4, 0, 98.8, 103.5));
        samples.add(sampleWithMa(2, -0.30, -0.10, -0.10, 99.9, 99.7, 100.2, 99.5, 0, 99.0, 103.0));
        samples.add(sampleWithMa(3, 0.10, 0.00, 0.20, 100.1, 99.9, 100.3, 99.8, 1, 99.2, 102.5));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_replaced_by_new_long_cycle", decision.getReason());
    }

    @Test
    public void shouldEnterShortWhenRecentCloseDoesNotMoveUp() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.50, 0.20, 0.30, 104.0, 104.2, 104.4, 103.8, 0, 104.0, 102.0));
        samples.add(sampleWithMa(1, 0.40, 0.15, 0.25, 103.0, 103.2, 103.4, 102.8, 0, 103.8, 101.9));
        samples.add(sampleWithMa(2, 0.20, 0.00, -0.22, 102.0, 102.2, 102.4, 101.8, 0, 103.6, 101.8));
        samples.add(sampleWithMa(3, -0.20, 0.00, -0.19, 101.0, 101.2, 101.4, 100.8, 1, 103.4, 101.7));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.ENTER_SHORT, decision.getType());
        Assert.assertEquals("dif_dea_cross_down", decision.getReason());
    }

    /**
     * 验证 pending 被新空头周期替代时，也会命中 MA 反向结构过滤。
     */
    @Test
    public void shouldBlockShortEntryByMaCounterTrendWhenReplacingPending() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_LONG_LAUNCH);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.60, 0.20, 0.60, 105.0, 105.2, 105.4, 104.8, 0, 104.0, 100.0));
        samples.add(sampleWithMa(1, 0.50, 0.18, 0.30, 104.7, 104.9, 105.1, 104.5, 0, 103.8, 100.5));
        samples.add(sampleWithMa(2, 0.30, 0.10, 0.10, 104.5, 104.7, 104.9, 104.3, 0, 103.6, 101.0));
        samples.add(sampleWithMa(3, -0.10, 0.00, -0.20, 104.3, 104.5, 104.6, 104.1, 1, 103.4, 101.5));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_replaced_by_new_short_cycle", decision.getReason());
    }

    /**
     * 验证死叉成立且最近3根里至少2根MACD偏低时不会直接开空。
     */
    @Test
    public void shouldBlockShortEntryWhenCrossIsWeak() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.60, 0.20, 0.60, 104.5, 0));
        samples.add(sample(1, 0.40, 0.20, 0.20, 104.0, 0));
        samples.add(sample(2, 0.20, 0.10, 0.10, 103.5, 0));
        samples.add(sample(3, 0.05, 0.00, -0.10, 103.0, 0));
        samples.add(sample(4, -0.20, 0.00, -0.17, 102.5, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_low_macd", decision.getReason());
    }

    /**
     * 验证死叉若属于大幅下杀后硬拽出来的突发交叉，则进入观察态而不是直接开空。
     */
    @Test
    public void shouldBlockShortEntryWhenCrossIsCreatedBySurgeDown() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.20, 0.10, 0.20, 100.0, 0));
        samples.add(sample(1, 0.22, 0.08, 0.30, 100.2, 0));
        samples.add(sample(2, 0.26, 0.06, 0.70, 100.5, 0));
        samples.add(sampleWithRange(3, 0.10, 0.05, 0.60, 99.2, 100.0, 100.0, 99.0, 0));
        samples.add(sample(4, -0.10, 0.00, -0.60, 98.8, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_surge_cross", decision.getReason());
    }

    /**
     * 验证死叉前4根 MACD 在后两根突然放大时，不直接开空。
     */
    @Test
    public void shouldBlockShortEntryWhenMacdSuddenlyExpandsInLastTwoBars() {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.60, 0.30, 0.30, 100.0, 0));
        samples.add(sample(1, 0.45, 0.25, 0.15, 99.4, 0));
        samples.add(sample(2, 0.10, 0.15, -0.55, 98.2, 0));
        samples.add(sample(3, -0.20, 0.00, -0.95, 97.4, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("entry_blocked_by_sudden_macd_expansion", decision.getReason());
    }

    /**
     * 验证多头观察态命中启动条件但未突破 pending 高点时继续观察。
     */
    @Test
    public void shouldKeepPendingLongWhenLaunchReadyButNoPendingHighBreakout() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_LONG_LAUNCH);
        state.setPendingHighClose(103.0);
        state.setPendingLowClose(100.0);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.10, -0.20, 0.20, 100.0, 0));
        samples.add(sample(1, 0.05, -0.10, 0.30, 101.0, 0));
        samples.add(sample(2, 0.20, 0.00, 0.51, 102.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证多头观察态未严格突破但在容差内且为阳线时，可以提前补开仓。
     */
    @Test
    public void shouldEnterLongWhenLaunchReadyWithinToleranceAndBullish() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_LONG_LAUNCH, 102.05, 100.0);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, -0.10, -0.20, 0.20, 100.0, 100.0, 100.2, 99.8, 0, 99.8, 100.0));
        samples.add(sampleWithMa(1, 0.05, -0.10, 0.30, 101.0, 100.8, 101.2, 100.7, 0, 100.0, 100.2));
        samples.add(sampleWithMa(2, 0.20, 0.00, 0.51, 102.0, 101.8, 102.2, 101.7, 0, 100.3, 100.5));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证多头观察态即使最近close未满足两次走强，只要MACD确认且进入容差突破区也可补开仓。
     */
    @Test
    public void shouldEnterLongWhenMacdConfirmsAndToleranceBreakoutAppears() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_LONG_LAUNCH, 102.05, 100.0);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, -0.10, -0.20, 0.20, 100.0, 100.0, 100.2, 99.8, 0, 99.8, 100.0));
        samples.add(sampleWithMa(1, 0.05, -0.10, 0.30, 102.10, 101.8, 102.2, 101.7, 0, 100.0, 100.2));
        samples.add(sampleWithMa(2, 0.20, 0.00, 0.51, 102.00, 101.8, 102.1, 101.7, 0, 100.3, 100.5));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证多头观察态在容差内但当前K线不是阳线时，仍继续观察。
     */
    @Test
    public void shouldKeepPendingLongWhenWithinToleranceButNotBullish() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_LONG_LAUNCH, 102.05, 100.0);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.10, -0.20, 0.20, 100.0, 0));
        samples.add(sample(1, 0.05, -0.10, 0.30, 101.0, 0));
        samples.add(sample(2, 0.20, 0.00, 0.51, 102.0, 102.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证多头观察态命中启动条件并突破 pending 高点时补开仓。
     */
    @Test
    public void shouldEnterLongWhenLaunchReadyAndBreaksPendingHigh() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_LONG_LAUNCH, 101.5, 100.0);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, -0.10, -0.20, 0.20, 100.0, 100.0, 100.2, 99.8, 0, 99.8, 100.0));
        samples.add(sampleWithMa(1, 0.05, -0.10, 0.30, 101.0, 100.8, 101.2, 100.7, 0, 100.0, 100.2));
        samples.add(sampleWithMa(2, 0.20, 0.00, 0.51, 102.0, 101.8, 102.2, 101.7, 0, 100.3, 100.5));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.ENTER_LONG, decision.getType());
        Assert.assertEquals("launch_entry_after_macd_expand", decision.getReason());
    }

    /**
     * 验证多头补开仓当根波幅过大时继续观察，不立即补开仓。
     */
    @Test
    public void shouldKeepPendingLongWhenMa10TrendDoesNotConfirm() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_LONG_LAUNCH, 101.5, 100.0);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, -0.10, -0.20, 0.20, 100.0, 100.0, 100.2, 99.8, 0, 99.8, 100.0));
        samples.add(sampleWithMa(1, 0.05, -0.10, 0.30, 101.0, 100.8, 101.2, 100.7, 0, 100.0, 100.2));
        samples.add(sampleWithMa(2, 0.20, 0.00, 0.51, 102.0, 101.8, 102.2, 101.7, 0, 100.3, 100.1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("launch_blocked_by_ma10_trend", decision.getReason());
    }

    @Test
    public void shouldKeepPendingLongWhenLaunchBarRangeIsLarge() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_LONG_LAUNCH, 101.5, 100.0);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, -0.10, -0.20, 0.20, 100.0, 100.0, 100.2, 99.8, 0, 99.8, 100.0));
        samples.add(sampleWithMa(1, 0.05, -0.10, 0.30, 101.0, 100.8, 101.2, 100.7, 0, 100.0, 100.2));
        samples.add(sampleWithMa(2, 0.20, 0.00, 0.51, 102.0, 100.0, 101.2, 100.1, 0, 100.3, 100.5));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("launch_blocked_by_large_bar_range", decision.getReason());
    }

    /**
     * 验证关闭补开仓开关后，多头 pending 即使满足启动条件也继续观察。
     */
    @Test
    public void shouldKeepPendingLongWhenLaunchEntryDisabled() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_LONG_LAUNCH, 101.5, 100.0);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, -0.10, -0.20, 0.20, 100.0, 100.0, 100.2, 99.8, 0, 99.8, 99.9));
        samples.add(sampleWithMa(1, 0.05, -0.10, 0.30, 101.0, 100.8, 101.2, 100.7, 0, 100.0, 100.0));
        samples.add(sampleWithMa(2, 0.20, 0.00, 0.51, 102.0, 101.8, 102.2, 101.7, 0, 100.3, 100.2));

        LifecycleDecision decision = decide(samples, disabledLaunchConfig(), state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("launch_entry_disabled_by_config", decision.getReason());
    }

    /**
     * 验证空头观察态命中启动条件但未跌破 pending 低点时继续观察。
     */
    @Test
    public void shouldKeepPendingShortWhenLaunchReadyButNoPendingLowBreakout() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_SHORT_LAUNCH, 100.0, 97.0);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.10, 0.20, -0.20, 100.0, 0));
        samples.add(sample(1, -0.05, 0.10, -0.30, 99.0, 0));
        samples.add(sample(2, -0.20, 0.00, -0.51, 98.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证空头观察态未严格跌破但在容差内且为阴线时，可以提前补开仓。
     */
    @Test
    public void shouldEnterShortWhenLaunchReadyWithinToleranceAndBearish() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_SHORT_LAUNCH, 100.0, 97.95);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.10, 0.20, -0.20, 100.0, 100.1, 100.2, 99.8, 0, 100.2, 100.5));
        samples.add(sampleWithMa(1, -0.05, 0.10, -0.30, 99.0, 99.2, 99.3, 98.8, 0, 100.0, 100.3));
        samples.add(sampleWithMa(2, -0.20, 0.00, -0.51, 98.0, 98.2, 98.3, 97.8, 0, 99.7, 100.0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证空头观察态即使最近close未满足两次走弱，只要MACD确认且进入容差突破区也可补开仓。
     */
    @Test
    public void shouldEnterShortWhenMacdConfirmsAndToleranceBreakoutAppears() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_SHORT_LAUNCH, 100.0, 97.95);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.10, 0.20, -0.20, 100.0, 100.1, 100.2, 99.8, 0, 100.2, 100.5));
        samples.add(sampleWithMa(1, -0.05, 0.10, -0.30, 97.80, 98.0, 98.1, 97.6, 0, 100.0, 100.3));
        samples.add(sampleWithMa(2, -0.20, 0.00, -0.51, 98.00, 98.2, 98.3, 97.8, 0, 99.7, 100.0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证空头观察态在MACD仅有一次严格走弱、另一段回弹不超过容差时仍可补开仓。
     */
    @Test
    public void shouldEnterShortWhenMacdSecondConfirmationIsWithinTolerance() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_SHORT_LAUNCH, 100.0, 97.95);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.10, 0.20, -0.5687, 100.0, 100.1, 100.2, 99.8, 0, 100.2, 100.5));
        samples.add(sampleWithMa(1, -0.05, 0.10, -0.4936, 99.0, 99.2, 99.3, 98.8, 0, 100.0, 100.3));
        samples.add(sampleWithMa(2, -0.20, 0.00, -0.5787, 98.00, 98.2, 98.3, 97.8, 0, 99.7, 100.0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证空头观察态在容差内但当前K线不是阴线时，仍继续观察。
     */
    @Test
    public void shouldKeepPendingShortWhenWithinToleranceButNotBearish() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_SHORT_LAUNCH, 100.0, 97.95);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.10, 0.20, -0.20, 100.0, 0));
        samples.add(sample(1, -0.05, 0.10, -0.30, 99.0, 0));
        samples.add(sample(2, -0.20, 0.00, -0.51, 98.0, 98.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证空头观察态命中启动条件并跌破 pending 低点时补开仓。
     */
    @Test
    public void shouldEnterShortWhenLaunchReadyAndBreaksPendingLow() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_SHORT_LAUNCH, 100.0, 98.5);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.10, 0.20, -0.20, 100.0, 100.1, 100.2, 99.8, 0, 100.2, 100.5));
        samples.add(sampleWithMa(1, -0.05, 0.10, -0.30, 99.0, 99.2, 99.3, 98.8, 0, 100.0, 100.3));
        samples.add(sampleWithMa(2, -0.20, 0.00, -0.51, 98.0, 98.2, 98.3, 97.8, 0, 99.7, 100.0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.ENTER_SHORT, decision.getType());
        Assert.assertEquals("launch_entry_after_macd_expand", decision.getReason());
    }

    /**
     * 验证空头补开仓当根波幅过大时继续观察，不立即补开仓。
     */
    @Test
    public void shouldKeepPendingShortWhenMa10TrendDoesNotConfirm() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_SHORT_LAUNCH, 100.0, 98.5);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.10, 0.20, -0.20, 100.0, 100.1, 100.2, 99.8, 0, 100.2, 100.5));
        samples.add(sampleWithMa(1, -0.05, 0.10, -0.30, 99.0, 99.2, 99.3, 98.8, 0, 100.0, 100.3));
        samples.add(sampleWithMa(2, -0.20, 0.00, -0.51, 98.0, 98.2, 98.3, 97.8, 0, 99.7, 100.4));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("launch_blocked_by_ma10_trend", decision.getReason());
    }

    @Test
    public void shouldKeepPendingShortWhenLaunchBarRangeIsLarge() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_SHORT_LAUNCH, 100.0, 98.5);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.10, 0.20, -0.20, 100.0, 100.1, 100.2, 99.8, 0, 100.2, 100.5));
        samples.add(sampleWithMa(1, -0.05, 0.10, -0.30, 99.0, 99.2, 99.3, 98.8, 0, 100.0, 100.3));
        samples.add(sampleWithMa(2, -0.20, 0.00, -0.51, 98.0, 100.0, 100.0, 98.9, 0, 99.7, 100.0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("launch_blocked_by_large_bar_range", decision.getReason());
    }

    /**
     * 验证关闭补开仓开关后，空头 pending 即使满足启动条件也继续观察。
     */
    @Test
    public void shouldKeepPendingShortWhenLaunchEntryDisabled() {
        LifecycleState state = readyPendingState(LifecyclePhase.PENDING_SHORT_LAUNCH, 100.0, 98.5);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithMa(0, 0.10, 0.20, -0.20, 100.0, 100.1, 100.2, 99.8, 0, 100.2, 100.5));
        samples.add(sampleWithMa(1, -0.05, 0.10, -0.30, 99.0, 99.2, 99.3, 98.8, 0, 100.0, 100.3));
        samples.add(sampleWithMa(2, -0.20, 0.00, -0.51, 98.0, 98.2, 98.3, 97.8, 0, 99.7, 100.0));

        LifecycleDecision decision = decide(samples, disabledLaunchConfig(), state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("launch_entry_disabled_by_config", decision.getReason());
    }

    /**
     * 验证观察态下MACD未突破时继续跟踪。
     */
    @Test
    public void shouldKeepPendingWhenMacdDoesNotExpand() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_LONG_LAUNCH);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.10, -0.20, 0.20, 100.0, 0));
        samples.add(sample(1, 0.05, -0.10, 0.30, 101.0, 0));
        samples.add(sample(2, 0.20, 0.00, 0.49, 102.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证多头观察态下当前MACD绝对值没有继续放大时不补开仓。
     */
    @Test
    public void shouldKeepPendingLongWhenMacdAbsDoesNotExpand() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_LONG_LAUNCH);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.10, -0.20, 0.20, 100.0, 0));
        samples.add(sample(1, 0.05, -0.10, 0.60, 101.0, 0));
        samples.add(sample(2, 0.20, 0.00, 0.55, 102.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证空头观察态下当前MACD绝对值没有继续放大时不补开仓。
     */
    @Test
    public void shouldKeepPendingShortWhenMacdAbsDoesNotExpand() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_SHORT_LAUNCH);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.10, 0.20, -0.20, 100.0, 0));
        samples.add(sample(1, -0.05, 0.10, -0.60, 99.0, 0));
        samples.add(sample(2, -0.20, 0.00, -0.55, 98.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证多头观察态下当前K线不是阳线时不补开仓。
     */
    @Test
    public void shouldKeepPendingLongWhenCurrentBarIsNotBullish() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_LONG_LAUNCH);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.10, -0.20, 0.20, 100.0, 99.8, 0));
        samples.add(sample(1, 0.05, -0.10, 0.30, 101.0, 100.8, 0));
        samples.add(sample(2, 0.20, 0.00, 0.51, 102.0, 102.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证空头观察态下当前K线不是阴线时不补开仓。
     */
    @Test
    public void shouldKeepPendingShortWhenCurrentBarIsNotBearish() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_SHORT_LAUNCH);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.10, 0.20, -0.20, 100.0, 100.2, 0));
        samples.add(sample(1, -0.05, 0.10, -0.30, 99.0, 99.2, 0));
        samples.add(sample(2, -0.20, 0.00, -0.51, 98.0, 98.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证多头观察态超过原固定根数后不会因为 pendingBars 失效。
     */
    @Test
    public void shouldKeepPendingLongWhenPendingBarsExceedsOldLimit() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_LONG_LAUNCH);
        state.setPendingBars(20);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.10, 0.00, 0.10, 100.0, 0));
        samples.add(sample(1, 0.12, 0.00, 0.20, 100.5, 0));
        samples.add(sample(2, 0.14, 0.00, 0.30, 101.0, 0));
        samples.add(sample(3, 0.16, 0.00, 0.40, 101.5, 0));
        samples.add(sample(4, 0.18, 0.00, 0.45, 102.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证空头观察态超过原固定根数后不会因为 pendingBars 失效。
     */
    @Test
    public void shouldKeepPendingShortWhenPendingBarsExceedsOldLimit() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_SHORT_LAUNCH);
        state.setPendingBars(20);
        state.setPendingSource(PendingSource.OTHER);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.10, 0.00, -0.10, 102.0, 0));
        samples.add(sample(1, -0.12, 0.00, -0.20, 101.5, 0));
        samples.add(sample(2, -0.14, 0.00, -0.30, 101.0, 0));
        samples.add(sample(3, -0.16, 0.00, -0.40, 100.5, 0));
        samples.add(sample(4, -0.18, 0.00, -0.45, 100.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证低MACD来源的空头观察态不再因为等待根数过多而超时。
     */
    @Test
    public void shouldKeepPendingShortWhenLowMacdSourceHitsMaxBars() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_SHORT_LAUNCH);
        state.setPendingBars(10);
        state.setPendingSource(PendingSource.LOW_MACD_ENTRY);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.10, 0.00, -0.10, 102.0, 0));
        samples.add(sample(1, -0.12, 0.00, -0.20, 101.5, 0));
        samples.add(sample(2, -0.14, 0.00, -0.30, 101.0, 0));
        samples.add(sample(3, -0.16, 0.00, -0.40, 100.5, 0));
        samples.add(sample(4, -0.18, 0.00, -0.45, 100.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证低MACD来源的多头观察态不再因为等待根数过多而超时。
     */
    @Test
    public void shouldKeepPendingLongWhenLowMacdSourceHitsMaxBars() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_LONG_LAUNCH);
        state.setPendingBars(10);
        state.setPendingSource(PendingSource.LOW_MACD_ENTRY);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.10, 0.00, 0.10, 100.0, 0));
        samples.add(sample(1, 0.12, 0.00, 0.20, 100.5, 0));
        samples.add(sample(2, 0.14, 0.00, 0.30, 101.0, 0));
        samples.add(sample(3, 0.16, 0.00, 0.40, 101.5, 0));
        samples.add(sample(4, 0.18, 0.00, 0.45, 102.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_LONG_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证观察态遇到反向交叉后立即失效。
     */
    @Test
    public void shouldInvalidatePendingLaunchWhenReverseCrossAppears() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_SHORT_LAUNCH);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.40, 0.20, 0.20, 100.8, 0));
        samples.add(sample(1, 0.25, 0.10, 0.15, 100.7, 0));
        samples.add(sample(2, 0.12, 0.08, 0.04, 100.6, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.NONE, decision.getType());
        Assert.assertEquals("pending_launch_invalidated_by_reverse_cross", decision.getReason());
    }

    /**
     * 验证多头观察态方向约束被破坏后仍立即失效。
     */
    @Test
    public void shouldInvalidatePendingLongWhenDirectionBreaks() {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_LONG_LAUNCH);
        state.setPendingBars(20);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.10, 0.20, -0.20, 100.0, 0));
        samples.add(sample(1, -0.12, 0.18, -0.30, 99.8, 0));
        samples.add(sample(2, -0.14, 0.16, -0.40, 99.6, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.NONE, decision.getType());
        Assert.assertEquals("pending_launch_invalidated_by_reverse_cross", decision.getReason());
    }

    /**
     * 验证多头持仓时使用MACD走弱叠加close走弱触发策略离场。
     */
    @Test
    public void shouldLeaveLongWhenMacdShrinksAndCloseWeakens() {
        LifecycleState state = activeState(LifecyclePhase.LONG_ACTIVE);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 1.00, 0.50, 1.20, 110.0, 110.2, 109.0, 0));
        samples.add(sample(1, 1.00, 0.50, 1.10, 109.0, 109.2, 108.0, 0));
        samples.add(sample(2, 1.00, 0.50, 1.00, 108.0, 108.2, 107.0, 0));
        samples.add(sample(3, 1.00, 0.50, 0.90, 107.0, 107.2, 106.0, 0));
        samples.add(sample(4, 1.00, 0.50, 0.80, 106.0, 106.2, 105.0, 0));
        samples.add(sample(5, 1.00, 0.50, 0.70, 105.0, 105.2, 104.0, 0));
        samples.add(sample(6, 1.00, 0.50, 0.60, 104.0, 104.2, 103.0, 0));
        samples.add(sample(7, 1.00, 0.50, 0.50, 103.0, 103.2, 102.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.LEAVE_LONG, decision.getType());
        Assert.assertEquals("macd_shrinking_and_close_weakening", decision.getReason());
    }

    /**
     * 验证多头持仓时仅MACD和close走弱但low未同步走弱不会策略离场。
     */
    @Test
    public void shouldKeepLongWhenCloseWeakensButLowDoesNotWeaken() {
        LifecycleState state = activeState(LifecyclePhase.LONG_ACTIVE);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 1.00, 0.50, 1.20, 110.0, 109.8, 109.0, 0));
        samples.add(sample(1, 1.00, 0.50, 1.10, 109.0, 108.8, 109.0, 0));
        samples.add(sample(2, 1.00, 0.50, 1.00, 108.0, 107.8, 109.0, 0));
        samples.add(sample(3, 1.00, 0.50, 0.90, 107.0, 106.8, 109.0, 0));
        samples.add(sample(4, 1.00, 0.50, 0.80, 106.0, 105.8, 109.0, 0));
        samples.add(sample(5, 1.00, 0.50, 0.70, 105.0, 104.8, 109.0, 0));
        samples.add(sample(6, 1.00, 0.50, 0.60, 104.0, 103.8, 109.0, 0));
        samples.add(sample(7, 1.00, 0.50, 0.50, 103.0, 102.8, 109.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.NONE, decision.getType());
        Assert.assertEquals("long_active_no_exit", decision.getReason());
    }

    /**
     * 验证多头策略离场若未跌破前3根最低close，则继续持仓。
     */
    @Test
    public void shouldKeepLongWhenNoCloseBreakoutBelowRecentThreeBars() {
        LifecycleState state = activeState(LifecyclePhase.LONG_ACTIVE);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 1.00, 0.50, 1.20, 110.0, 110.2, 109.0, 0));
        samples.add(sample(1, 1.00, 0.50, 1.10, 109.0, 109.2, 108.0, 0));
        samples.add(sample(2, 1.00, 0.50, 1.00, 108.0, 108.2, 107.0, 0));
        samples.add(sample(3, 1.00, 0.50, 0.90, 107.0, 107.2, 106.0, 0));
        samples.add(sample(4, 1.00, 0.50, 0.80, 106.0, 106.2, 105.0, 0));
        samples.add(sample(5, 1.00, 0.50, 0.70, 105.0, 105.2, 104.0, 0));
        samples.add(sample(6, 1.00, 0.50, 0.60, 104.2, 104.4, 103.0, 0));
        samples.add(sample(7, 1.00, 0.50, 0.50, 104.3, 104.5, 102.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.NONE, decision.getType());
        Assert.assertEquals("long_active_no_exit", decision.getReason());
    }

    /**
     * 验证空头持仓时使用MACD恢复叠加close走强触发策略离场。
     */
    @Test
    public void shouldLeaveShortWhenMacdRecoversAndCloseStrengthens() {
        LifecycleState state = activeState(LifecyclePhase.SHORT_ACTIVE);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -1.00, -0.50, -1.20, 100.0, 100.2, 98.0, 0));
        samples.add(sample(1, -1.00, -0.50, -1.10, 101.0, 101.2, 99.0, 0));
        samples.add(sample(2, -1.00, -0.50, -1.00, 102.0, 102.2, 100.0, 0));
        samples.add(sample(3, -1.00, -0.50, -0.90, 103.0, 103.2, 101.0, 0));
        samples.add(sample(4, -1.00, -0.50, -0.80, 104.0, 104.2, 102.0, 0));
        samples.add(sample(5, -1.00, -0.50, -0.70, 105.0, 105.2, 103.0, 0));
        samples.add(sample(6, -1.00, -0.50, -0.60, 106.0, 106.2, 104.0, 0));
        samples.add(sample(7, -1.00, -0.50, -0.50, 107.0, 107.2, 105.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.LEAVE_SHORT, decision.getType());
        Assert.assertEquals("macd_recovering_and_close_strengthening", decision.getReason());
    }

    /**
     * 验证空头持仓时仅MACD和close走强但high未同步走强不会策略离场。
     */
    @Test
    public void shouldKeepShortWhenCloseStrengthensButLowDoesNotStrengthen() {
        LifecycleState state = activeState(LifecyclePhase.SHORT_ACTIVE);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -1.00, -0.50, -1.20, 100.0, 100.2, 96.0, 0));
        samples.add(sample(1, -1.00, -0.50, -1.10, 101.0, 101.2, 95.5, 0));
        samples.add(sample(2, -1.00, -0.50, -1.00, 102.0, 102.2, 95.0, 0));
        samples.add(sample(3, -1.00, -0.50, -0.90, 103.0, 103.2, 94.5, 0));
        samples.add(sample(4, -1.00, -0.50, -0.80, 104.0, 104.2, 94.0, 0));
        samples.add(sample(5, -1.00, -0.50, -0.70, 105.0, 105.2, 93.5, 0));
        samples.add(sample(6, -1.00, -0.50, -0.60, 106.0, 106.2, 93.0, 0));
        samples.add(sample(7, -1.00, -0.50, -0.50, 107.0, 107.2, 92.5, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.NONE, decision.getType());
        Assert.assertEquals("short_active_no_exit", decision.getReason());
    }

    /**
     * 验证空头策略离场若未突破前3根最高close，则继续持仓。
     */
    @Test
    public void shouldKeepShortWhenNoCloseBreakoutAboveRecentThreeBars() {
        LifecycleState state = activeState(LifecyclePhase.SHORT_ACTIVE);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -1.00, -0.50, -1.20, 100.0, 100.2, 98.0, 0));
        samples.add(sample(1, -1.00, -0.50, -1.10, 101.0, 101.2, 99.0, 0));
        samples.add(sample(2, -1.00, -0.50, -1.00, 102.0, 102.2, 100.0, 0));
        samples.add(sample(3, -1.00, -0.50, -0.90, 103.0, 103.2, 101.0, 0));
        samples.add(sample(4, -1.00, -0.50, -0.80, 104.0, 104.2, 102.0, 0));
        samples.add(sample(5, -1.00, -0.50, -0.70, 105.0, 105.2, 103.0, 0));
        samples.add(sample(6, -1.00, -0.50, -0.60, 104.8, 105.0, 104.0, 0));
        samples.add(sample(7, -1.00, -0.50, -0.50, 104.9, 105.1, 105.0, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.NONE, decision.getType());
        Assert.assertEquals("short_active_no_exit", decision.getReason());
    }

    /**
     * 验证多头策略离场后进入原方向观察态。
     */
    @Test
    public void shouldMoveToPendingLongAfterLongStrategyExit() throws Exception {
        LifecycleState state = activeState(LifecyclePhase.LONG_ACTIVE);
        LifecycleDecision decision = LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                "macd_shrinking_and_close_weakening");

        updateBacktestState(state, decision);

        Assert.assertEquals(LifecyclePhase.PENDING_LONG_LAUNCH, state.getPhase());
        Assert.assertEquals(LifecycleDirection.LONG, state.getDirection());
    }

    /**
     * 验证空头策略离场后进入原方向观察态。
     */
    @Test
    public void shouldMoveToPendingShortAfterShortStrategyExit() throws Exception {
        LifecycleState state = activeState(LifecyclePhase.SHORT_ACTIVE);
        LifecycleDecision decision = LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                "macd_recovering_and_close_strengthening");

        updateBacktestState(state, decision);

        Assert.assertEquals(LifecyclePhase.PENDING_SHORT_LAUNCH, state.getPhase());
        Assert.assertEquals(LifecycleDirection.SHORT, state.getDirection());
    }

    /**
     * 验证低MACD空仓过滤触发的空头观察态会记录正确来源。
     */
    @Test
    public void shouldMarkPendingSourceAsLowMacdEntry() throws Exception {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_SHORT_LAUNCH);
        state.setDirection(LifecycleDirection.SHORT);
        LifecycleDecision decision = LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH,
                "entry_blocked_by_low_macd");

        updateBacktestState(state, decision);

        Assert.assertEquals(LifecyclePhase.PENDING_SHORT_LAUNCH, state.getPhase());
        Assert.assertEquals(LifecycleDirection.SHORT, state.getDirection());
        Assert.assertEquals(PendingSource.LOW_MACD_ENTRY, state.getPendingSource());
    }

    /**
     * 验证低MACD来源的观察态即使pendingBars较大，下一根没有新交叉时也不会超时回空闲。
     */
    @Test
    public void shouldKeepPendingWithoutExpiryOnNextBarWithoutNewCross() throws Exception {
        LifecycleState state = pendingState(LifecyclePhase.PENDING_SHORT_LAUNCH);
        state.setDirection(LifecycleDirection.SHORT);
        state.setPendingBars(10);
        state.setPendingSource(PendingSource.LOW_MACD_ENTRY);

        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -1.20, -0.80, -0.40, 100.0, 0));
        samples.add(sample(1, -1.10, -0.85, -0.25, 99.9, 0));
        samples.add(sample(2, -1.00, -0.88, -0.12, 99.8, 0));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.PENDING_SHORT_LAUNCH, decision.getType());
        Assert.assertEquals("pending_launch_tracking", decision.getReason());
    }

    /**
     * 验证多头反向死叉离场仍进入空头活跃态。
     */
    @Test
    public void shouldKeepReverseCrossLongExitBehavior() throws Exception {
        LifecycleState state = activeState(LifecyclePhase.LONG_ACTIVE);
        LifecycleDecision decision = LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG, "dif_dea_cross_down");

        updateBacktestState(state, decision);

        Assert.assertEquals(LifecyclePhase.SHORT_ACTIVE, state.getPhase());
        Assert.assertEquals(LifecycleDirection.SHORT, state.getDirection());
    }

    /**
     * 验证持仓反向死叉离场不被当前K线大波幅过滤阻止。
     */
    @Test
    public void shouldLeaveLongOnReverseCrossEvenWhenCurrentBarRangeIsLarge() {
        LifecycleState state = activeState(LifecyclePhase.LONG_ACTIVE);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sampleWithRange(0, 0.50, 0.20, 0.30, 104.0, 100.0, 101.0, 100.0, 0));
        samples.add(sampleWithRange(1, 0.40, 0.15, 0.25, 103.0, 100.0, 101.0, 100.0, 0));
        samples.add(sampleWithRange(2, 0.20, 0.00, 0.20, 102.0, 100.0, 101.0, 100.0, 0));
        samples.add(sampleWithRange(3, -0.10, 0.00, -0.60, 101.0, 100.0, 102.0, 100.4, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.LEAVE_LONG, decision.getType());
        Assert.assertEquals("dif_dea_cross_down", decision.getReason());
    }

    /**
     * 验证多头持仓遇到突发下杀形成的反向死叉时，只平仓不立即反手开空。
     */
    @Test
    public void shouldLeaveLongWithoutReverseEntryWhenReverseCrossIsCreatedBySurgeDown() {
        LifecycleState state = activeState(LifecyclePhase.LONG_ACTIVE);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, 0.20, 0.10, 0.20, 100.0, 0));
        samples.add(sample(1, 0.22, 0.08, 0.30, 100.2, 0));
        samples.add(sample(2, 0.26, 0.06, 0.70, 100.5, 0));
        samples.add(sampleWithRange(3, 0.10, 0.05, 0.60, 99.2, 100.0, 100.0, 99.0, 0));
        samples.add(sample(4, -0.10, 0.00, -0.60, 98.8, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.LEAVE_LONG, decision.getType());
        Assert.assertEquals("dif_dea_cross_down", decision.getReason());
        Assert.assertFalse(decision.isReverseEntryAllowed());
    }

    /**
     * 验证空头反向金叉离场仍进入多头活跃态。
     */
    @Test
    public void shouldKeepReverseCrossShortExitBehavior() throws Exception {
        LifecycleState state = activeState(LifecyclePhase.SHORT_ACTIVE);
        LifecycleDecision decision = LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT, "dif_dea_cross_up");

        updateBacktestState(state, decision);

        Assert.assertEquals(LifecyclePhase.LONG_ACTIVE, state.getPhase());
        Assert.assertEquals(LifecycleDirection.LONG, state.getDirection());
    }

    /**
     * 验证空头持仓遇到突发拉升形成的反向金叉时，只平仓不立即反手开多。
     */
    @Test
    public void shouldLeaveShortWithoutReverseEntryWhenReverseCrossIsCreatedBySurgeUp() {
        LifecycleState state = activeState(LifecyclePhase.SHORT_ACTIVE);
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        samples.add(sample(0, -0.20, -0.10, -0.20, 100.0, 0));
        samples.add(sample(1, -0.22, -0.08, -0.30, 99.8, 0));
        samples.add(sample(2, -0.26, -0.06, -0.70, 99.5, 0));
        samples.add(sampleWithRange(3, -0.10, -0.05, -0.60, 100.8, 100.0, 101.0, 100.0, 0));
        samples.add(sample(4, 0.10, 0.00, 0.60, 101.2, 1));

        LifecycleDecision decision = decide(samples, state);

        Assert.assertEquals(LifecycleDecisionType.LEAVE_SHORT, decision.getType());
        Assert.assertEquals("dif_dea_cross_up", decision.getReason());
        Assert.assertFalse(decision.isReverseEntryAllowed());
    }

    /**
     * 统一执行决策，避免测试重复构造上下文。
     */
    private LifecycleDecision decide(List<LifecycleIndicatorSample> samples, LifecycleState state) {
        return engine.decide(new LifecycleContext("difDeaLifecycle", "ETHUSDT", "5M",
                samples, defaultConfig(), state));
    }

    /**
     * 使用指定配置执行一次决策，便于验证配置开关类场景。
     */
    private LifecycleDecision decide(List<LifecycleIndicatorSample> samples, LifecycleConfig config,
                                     LifecycleState state) {
        return engine.decide(new LifecycleContext("difDeaLifecycle", "ETHUSDT", "5M",
                samples, config, state));
    }

    /**
     * 调用回测适配器的状态推进逻辑，验证决策结果如何落到生命周期状态。
     */
    private void updateBacktestState(LifecycleState state, LifecycleDecision decision) throws Exception {
        DifDeaLifecycleBacktestStrategy strategy = new DifDeaLifecycleBacktestStrategy(
                new DifDeaLifecycleDecisionEngine(), new LifecycleConfigProvider());
        Method method = DifDeaLifecycleBacktestStrategy.class.getDeclaredMethod("updateState",
                LifecycleState.class, LifecycleIndicatorSample.class, LifecycleDecision.class);
        method.setAccessible(true);
        method.invoke(strategy, state, sample(99, 1.0, 0.5, 0.6, 100.0, 0), decision);
    }

    /**
     * 构造指定观察态状态。
     */
    private LifecycleState pendingState(LifecyclePhase phase) {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        state.setPhase(phase);
        return state;
    }

    /**
     * 构造已经完成 pending 建区并冻结边界的观察态。
     */
    private LifecycleState readyPendingState(LifecyclePhase phase, double upperBodyBound, double lowerBodyBound) {
        LifecycleState state = pendingState(phase);
        state.setPendingBars(4);
        state.setPendingRangeReady(true);
        state.setPendingUpperBodyBound(upperBodyBound);
        state.setPendingLowerBodyBound(lowerBodyBound);
        state.setPendingHighClose(upperBodyBound);
        state.setPendingLowClose(lowerBodyBound);
        return state;
    }

    /**
     * 构造指定持仓方向的生命周期状态。
     */
    private LifecycleState activeState(LifecyclePhase phase) {
        LifecycleState state = new LifecycleState("difDeaLifecycle", "ETHUSDT", "5M");
        state.setPhase(phase);
        return state;
    }

    /**
     * 构造测试使用的默认生命周期配置。
     */
    private LifecycleConfig defaultConfig() {
        return new LifecycleConfig("difDeaLifecycle", "ETHUSDT", "5M",
                0.30, 0.0, 5, 4, 3, 2, true,
                5, 0.5, 24, 0.0, 12, 3, 3, 5, 3, true);
    }

    /**
     * 构造关闭补开仓能力的测试配置。
     */
    private LifecycleConfig disabledLaunchConfig() {
        return new LifecycleConfig("difDeaLifecycle", "ETHUSDT", "5M",
                0.30, 0.0, 5, 4, 3, 2, true,
                5, 0.5, 24, 0.0, 12, 3, 3, 5, 3, false);
    }

    /**
     * 构造一条测试指标样本。
     */
    private LifecycleIndicatorSample sample(int index, double dif, double dea, double macd, double close,
                                            int recentCrossCount) {
        double open = macd >= 0 ? close - 0.1 : close + 0.1;
        return sample(index, dif, dea, macd, close, open, recentCrossCount);
    }

    /**
     * 构造可指定开盘价的测试指标样本。
     */
    private LifecycleIndicatorSample sample(int index, double dif, double dea, double macd, double close,
                                            double open, int recentCrossCount) {
        return new LifecycleIndicatorSample(index, "2026-06-25T00:" + String.format("%02d", index),
                dif, dea, macd, open, Math.max(open, close) + 0.2, Math.min(open, close) - 0.2, close, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, recentCrossCount, Double.NaN);
    }

    /**
     * 构造可指定开盘价和最低价的测试指标样本。
     */
    private LifecycleIndicatorSample sample(int index, double dif, double dea, double macd, double close,
                                            double open, double low, int recentCrossCount) {
        return new LifecycleIndicatorSample(index, "2026-06-25T00:" + String.format("%02d", index),
                dif, dea, macd, open, Math.max(open, close) + 1.0, low, close, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, recentCrossCount, Double.NaN);
    }

    /**
     * 构造可指定开盘价和最高价的测试指标样本。
     */
    private LifecycleIndicatorSample sampleWithHigh(int index, double dif, double dea, double macd, double close,
                                                    double open, double high, int recentCrossCount) {
        return new LifecycleIndicatorSample(index, "2026-06-25T00:" + String.format("%02d", index),
                dif, dea, macd, open, high, Math.min(open, close) - 1.0, close, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, recentCrossCount, Double.NaN);
    }

    /**
     * 构造可指定完整OHLC的测试指标样本。
     */
    private LifecycleIndicatorSample sampleWithRange(int index, double dif, double dea, double macd, double close,
                                                     double open, double high, double low, int recentCrossCount) {
        return new LifecycleIndicatorSample(index, "2026-06-25T00:" + String.format("%02d", index),
                dif, dea, macd, open, high, low, close, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, recentCrossCount, Double.NaN);
    }

    /**
     * 构造可指定 OHLC 与 MA5/MA10 的测试指标样本。
     */
    private LifecycleIndicatorSample sampleWithMa(int index, double dif, double dea, double macd, double close,
                                                  double open, double high, double low, int recentCrossCount,
                                                  double ma5, double ma10) {
        return new LifecycleIndicatorSample(index, "2026-06-25T00:" + String.format("%02d", index),
                dif, dea, macd, open, high, low, close, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, recentCrossCount, Double.NaN, ma5, ma10);
    }
}
