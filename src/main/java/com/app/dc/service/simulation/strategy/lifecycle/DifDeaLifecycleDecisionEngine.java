package com.app.dc.service.simulation.strategy.lifecycle;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DIF/DEA趋势生命周期的核心决策引擎。
 */
@Service
public class DifDeaLifecycleDecisionEngine {

    public static final String STRATEGY_NAME = "difDeaLifecycle";

    /**
     * 根据当前指标样本、配置和状态输出生命周期动作。
     */
    public LifecycleDecision decide(LifecycleContext context) {
        if (context == null || context.getSamples() == null || context.getSamples().size() < 3) {
            return LifecycleDecision.none("not_enough_samples");
        }
        if (context.getConfig() == null || context.getState() == null) {
            return LifecycleDecision.none("missing_context");
        }

        LifecycleState state = context.getState();
        LifecycleIndicatorSample prev = context.previous();
        LifecycleIndicatorSample cur = context.current();

        boolean enterLongRaw = prev.getDif() < prev.getDea() && cur.getDif() >= cur.getDea();
        boolean leaveLongByCross = prev.getDif() > prev.getDea() && cur.getDif() <= cur.getDea();
        boolean enterShortRaw = prev.getDif() > prev.getDea() && cur.getDif() <= cur.getDea();
        boolean leaveShortByCross = prev.getDif() < prev.getDea() && cur.getDif() >= cur.getDea();

        int weaknessWindow = context.getConfig().getWeaknessWindow();
        int weaknessMinCount = context.getConfig().getWeaknessMinCount();
        boolean bondingSegment = isBondingSegment(context.getSamples(), context.getConfig());
        boolean reverseCrossBlocked = isReverseCrossBlocked(prev, cur, context.getConfig());
        boolean leaveLongByWeakness = macdDecreasing(context.getSamples(), weaknessWindow, weaknessMinCount)
                && closeDecreasing(context.getSamples(), weaknessWindow, weaknessMinCount);
        boolean leaveShortByWeakness = macdIncreasing(context.getSamples(), weaknessWindow, weaknessMinCount)
                && closeIncreasing(context.getSamples(), weaknessWindow, weaknessMinCount);

        if (state.inLong()) {
            if (leaveLongByCross && !reverseCrossBlocked) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG, "dif_dea_cross_down");
            }
            if (leaveLongByCross) {
                return LifecycleDecision.none("reverse_cross_blocked_by_dif_dea_bonding");
            }
            if (leaveLongByWeakness) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG, "macd_shrinking_and_close_weakening");
            }
            return LifecycleDecision.none("long_active_no_exit");
        }

        if (state.inShort()) {
            if (leaveShortByCross && !reverseCrossBlocked) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT, "dif_dea_cross_up");
            }
            if (leaveShortByCross) {
                return LifecycleDecision.none("reverse_cross_blocked_by_dif_dea_bonding");
            }
            if (leaveShortByWeakness) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT, "macd_recovering_and_close_strengthening");
            }
            return LifecycleDecision.none("short_active_no_exit");
        }

        if (!context.getConfig().validForEntry()) {
            return LifecycleDecision.none("missing_or_invalid_symbol_config");
        }

        if (enterLongRaw && !bondingSegment) {
            return LifecycleDecision.of(LifecycleDecisionType.ENTER_LONG, "dif_dea_cross_up");
        }
        if (enterShortRaw && !bondingSegment) {
            return LifecycleDecision.of(LifecycleDecisionType.ENTER_SHORT, "dif_dea_cross_down");
        }
        if (enterLongRaw || enterShortRaw) {
            return LifecycleDecision.none("entry_blocked_by_dif_dea_bonding");
        }
        return LifecycleDecision.none("no_cross");
    }

    /**
     * 判断最近窗口内DIF和DEA是否处于粘合段。
     */
    private boolean isBondingSegment(List<LifecycleIndicatorSample> samples, LifecycleConfig config) {
        int window = config.getBondingWindow();
        if (samples.size() < window) {
            return false;
        }
        int count = 0;
        int start = samples.size() - window;
        for (int i = start; i < samples.size(); i++) {
            LifecycleIndicatorSample sample = samples.get(i);
            if (Math.abs(sample.getDif() - sample.getDea()) <= config.getDifDeaBondThreshold()) {
                count++;
            }
        }
        return count >= config.getBondingMinCount();
    }

    /**
     * 判断反向交叉是否仍属于粘合假突破，若交叉前后两根拉开不足则继续过滤。
     */
    private boolean isReverseCrossBlocked(LifecycleIndicatorSample prev,
                                          LifecycleIndicatorSample current,
                                          LifecycleConfig config) {
        double threshold = config.getReverseCrossBondThreshold();
        if (threshold <= 0.0) {
            return false;
        }
        double prevGap = Math.abs(prev.getDif() - prev.getDea());
        double currentGap = Math.abs(current.getDif() - current.getDea());
        return Math.max(prevGap, currentGap) <= threshold;
    }

    /**
     * 判断MACD柱是否在窗口内达到缩小次数。
     */
    private boolean macdDecreasing(List<LifecycleIndicatorSample> samples, int window, int minCount) {
        if (samples.size() <= window || window < 1) {
            return false;
        }
        int count = 0;
        int start = samples.size() - window;
        for (int i = start; i < samples.size(); i++) {
            if (samples.get(i).getMacdBar() < samples.get(i - 1).getMacdBar()) {
                count++;
            }
        }
        return count >= minCount;
    }

    /**
     * 判断close是否在窗口内达到走弱次数。
     */
    private boolean closeDecreasing(List<LifecycleIndicatorSample> samples, int window, int minCount) {
        if (samples.size() <= window || window < 1) {
            return false;
        }
        int count = 0;
        int start = samples.size() - window;
        for (int i = start; i < samples.size(); i++) {
            if (samples.get(i).getClose() < samples.get(i - 1).getClose()) {
                count++;
            }
        }
        return count >= minCount;
    }

    /**
     * 判断MACD柱是否在窗口内达到恢复次数。
     */
    private boolean macdIncreasing(List<LifecycleIndicatorSample> samples, int window, int minCount) {
        if (samples.size() <= window || window < 1) {
            return false;
        }
        int count = 0;
        int start = samples.size() - window;
        for (int i = start; i < samples.size(); i++) {
            if (samples.get(i).getMacdBar() > samples.get(i - 1).getMacdBar()) {
                count++;
            }
        }
        return count >= minCount;
    }

    /**
     * 判断close是否在窗口内达到走强次数。
     */
    private boolean closeIncreasing(List<LifecycleIndicatorSample> samples, int window, int minCount) {
        if (samples.size() <= window || window < 1) {
            return false;
        }
        int count = 0;
        int start = samples.size() - window;
        for (int i = start; i < samples.size(); i++) {
            if (samples.get(i).getClose() > samples.get(i - 1).getClose()) {
                count++;
            }
        }
        return count >= minCount;
    }
}
