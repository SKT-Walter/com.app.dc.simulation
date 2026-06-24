package com.app.dc.simulation;

import com.app.dc.service.simulation.strategy.lifecycle.DifDeaLifecycleDecisionEngine;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleConfig;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleContext;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleDecision;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleDecisionType;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleDirection;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleIndicatorSample;
import com.app.dc.service.simulation.strategy.lifecycle.LifecyclePhase;
import com.app.dc.service.simulation.strategy.lifecycle.LifecycleState;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * DIF/DEA生命周期决策引擎的规则单元测试。
 */
public class DifDeaLifecycleDecisionEngineTest {

    private final DifDeaLifecycleDecisionEngine engine = new DifDeaLifecycleDecisionEngine();

    @Test
    /**
     * 验证DIF上穿DEA时产生多头进入信号。
     */
    public void enterLongOnSensitiveCrossUp() {
        LifecycleDecision decision = decide(neutral(), config(0.01), samples(
                row(-0.50, 0.00, -0.50, 100),
                row(-0.40, 0.00, -0.40, 101),
                row(-0.30, 0.00, -0.30, 102),
                row(-0.20, 0.00, -0.20, 103),
                row(0.10, 0.00, 0.10, 104)
        ));

        Assert.assertEquals(LifecycleDecisionType.ENTER_LONG, decision.getType());
    }

    @Test
    /**
     * 验证DIF下穿DEA时产生空头进入信号。
     */
    public void enterShortOnSensitiveCrossDown() {
        LifecycleDecision decision = decide(neutral(), config(0.01), samples(
                row(0.50, 0.00, 0.50, 104),
                row(0.40, 0.00, 0.40, 103),
                row(0.30, 0.00, 0.30, 102),
                row(0.20, 0.00, 0.20, 101),
                row(-0.10, 0.00, -0.10, 100)
        ));

        Assert.assertEquals(LifecycleDecisionType.ENTER_SHORT, decision.getType());
    }

    @Test
    /**
     * 验证DIF/DEA处于粘合段时阻止进场。
     */
    public void bondingSegmentBlocksEntry() {
        LifecycleDecision decision = decide(neutral(), config(0.30), samples(
                row(-0.10, 0.00, -0.10, 100),
                row(-0.08, 0.00, -0.08, 101),
                row(-0.06, 0.00, -0.06, 102),
                row(-0.04, 0.00, -0.04, 103),
                row(0.02, 0.00, 0.02, 104)
        ));

        Assert.assertEquals(LifecycleDecisionType.NONE, decision.getType());
        Assert.assertEquals("entry_blocked_by_dif_dea_bonding", decision.getReason());
    }

    @Test
    /**
     * 验证多头生命周期中死叉触发离场。
     */
    public void leaveLongOnCrossDown() {
        LifecycleDecision decision = decide(active(LifecyclePhase.LONG_ACTIVE, LifecycleDirection.LONG),
                config(0.01), samples(
                        row(0.50, 0.00, 0.50, 100),
                        row(0.40, 0.00, 0.40, 101),
                        row(0.30, 0.00, 0.30, 102),
                        row(0.20, 0.00, 0.20, 103),
                        row(-0.10, 0.00, -0.10, 104)
                ));

        Assert.assertEquals(LifecycleDecisionType.LEAVE_LONG, decision.getType());
        Assert.assertEquals("dif_dea_cross_down", decision.getReason());
    }

    @Test
    /**
     * 验证多头生命周期中粘合段内的反向死叉不会立刻离场。
     */
    public void keepLongWhenCrossDownStillBonding() {
        LifecycleDecision decision = decide(active(LifecyclePhase.LONG_ACTIVE, LifecycleDirection.LONG),
                config(0.30), samples(
                        row(0.20, 0.00, 0.20, 100),
                        row(0.18, 0.00, 0.18, 101),
                        row(0.16, 0.00, 0.16, 102),
                        row(0.12, 0.00, 0.12, 103),
                        row(-0.02, 0.00, -0.02, 104)
                ));

        Assert.assertEquals(LifecycleDecisionType.NONE, decision.getType());
        Assert.assertEquals("reverse_cross_blocked_by_dif_dea_bonding", decision.getReason());
    }

    @Test
    /**
     * 验证多头生命周期中MACD缩小且close走弱触发离场。
     */
    public void leaveLongOnMacdShrinkingAndCloseWeakening() {
        LifecycleDecision decision = decide(active(LifecyclePhase.LONG_ACTIVE, LifecycleDirection.LONG),
                config(0.01), samples(
                        row(0.60, 0.00, 0.60, 103),
                        row(0.50, 0.00, 0.50, 102),
                        row(0.40, 0.00, 0.40, 101),
                        row(0.30, 0.00, 0.30, 100)
                ));

        Assert.assertEquals(LifecycleDecisionType.LEAVE_LONG, decision.getType());
        Assert.assertEquals("macd_shrinking_and_close_weakening", decision.getReason());
    }

    @Test
    /**
     * 验证多头生命周期中窗口计数达标即可离场，允许中间一次反抽。
     */
    public void leaveLongOnWeaknessCountWithOneBounce() {
        LifecycleDecision decision = decide(active(LifecyclePhase.LONG_ACTIVE, LifecycleDirection.LONG),
                config(0.01), samples(
                        row(0.60, 0.00, 0.60, 103),
                        row(0.50, 0.00, 0.50, 102),
                        row(0.55, 0.00, 0.55, 103),
                        row(0.40, 0.00, 0.40, 101)
                ));

        Assert.assertEquals(LifecycleDecisionType.LEAVE_LONG, decision.getType());
        Assert.assertEquals("macd_shrinking_and_close_weakening", decision.getReason());
    }

    @Test
    /**
     * 验证空头生命周期中MACD恢复且close走强触发离场。
     */
    public void leaveShortOnMacdRecoveringAndCloseStrengthening() {
        LifecycleDecision decision = decide(active(LifecyclePhase.SHORT_ACTIVE, LifecycleDirection.SHORT),
                config(0.01), samples(
                        row(-0.60, 0.00, -0.60, 100),
                        row(-0.50, 0.00, -0.50, 101),
                        row(-0.40, 0.00, -0.40, 102),
                        row(-0.30, 0.00, -0.30, 103)
                ));

        Assert.assertEquals(LifecycleDecisionType.LEAVE_SHORT, decision.getType());
        Assert.assertEquals("macd_recovering_and_close_strengthening", decision.getReason());
    }

    @Test
    /**
     * 验证空头生命周期中粘合段内的反向金叉不会立刻离场。
     */
    public void keepShortWhenCrossUpStillBonding() {
        LifecycleDecision decision = decide(active(LifecyclePhase.SHORT_ACTIVE, LifecycleDirection.SHORT),
                config(0.30), samples(
                        row(-0.20, 0.00, -0.20, 104),
                        row(-0.18, 0.00, -0.18, 103),
                        row(-0.16, 0.00, -0.16, 102),
                        row(-0.12, 0.00, -0.12, 101),
                        row(0.02, 0.00, 0.02, 100)
                ));

        Assert.assertEquals(LifecycleDecisionType.NONE, decision.getType());
        Assert.assertEquals("reverse_cross_blocked_by_dif_dea_bonding", decision.getReason());
    }

    @Test
    /**
     * 验证空头生命周期中窗口计数达标即可离场，允许中间一次回落。
     */
    public void leaveShortOnRecoveryCountWithOnePullback() {
        LifecycleDecision decision = decide(active(LifecyclePhase.SHORT_ACTIVE, LifecycleDirection.SHORT),
                config(0.01), samples(
                        row(-0.60, 0.00, -0.60, 100),
                        row(-0.50, 0.00, -0.50, 101),
                        row(-0.55, 0.00, -0.55, 100),
                        row(-0.40, 0.00, -0.40, 102)
                ));

        Assert.assertEquals(LifecycleDecisionType.LEAVE_SHORT, decision.getType());
        Assert.assertEquals("macd_recovering_and_close_strengthening", decision.getReason());
    }

    /**
     * 执行一次决策引擎判断。
     */
    private LifecycleDecision decide(LifecycleState state, LifecycleConfig config, List<LifecycleIndicatorSample> samples) {
        return engine.decide(new LifecycleContext(DifDeaLifecycleDecisionEngine.STRATEGY_NAME,
                "ETHUSDT", "5M", samples, config, state));
    }

    /**
     * 构造中性生命周期状态。
     */
    private LifecycleState neutral() {
        return new LifecycleState(DifDeaLifecycleDecisionEngine.STRATEGY_NAME, "ETHUSDT", "5M");
    }

    /**
     * 构造指定方向的活跃生命周期状态。
     */
    private LifecycleState active(LifecyclePhase phase, LifecycleDirection direction) {
        LifecycleState state = neutral();
        state.setPhase(phase);
        state.setDirection(direction);
        return state;
    }

    /**
     * 构造测试用生命周期配置。
     */
    private LifecycleConfig config(double threshold) {
        return new LifecycleConfig(DifDeaLifecycleDecisionEngine.STRATEGY_NAME, "ETHUSDT", "5M",
                threshold, 5, 4, 3, 2);
    }

    /**
     * 将测试数组转换为指标样本列表。
     */
    private List<LifecycleIndicatorSample> samples(double[]... rows) {
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        for (int i = 0; i < rows.length; i++) {
            samples.add(new LifecycleIndicatorSample(i, "bar-" + i, rows[i][0], rows[i][1], rows[i][2], rows[i][3]));
        }
        return samples;
    }

    /**
     * 构造一行指标测试数据。
     */
    private double[] row(double dif, double dea, double macd, double close) {
        return new double[]{dif, dea, macd, close};
    }
}
