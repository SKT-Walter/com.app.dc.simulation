package com.app.dc.service.simulation.strategy.lifecycle;

import org.springframework.stereotype.Service;

/**
 * 只保留5M DIF/DEA金叉死叉、大波幅过滤和一根确认入场的极简决策引擎。
 */
@Service
public class DifDeaLifecycleDecisionEngine {

    public static final String STRATEGY_NAME = "difDeaLifecycle";

    /**
     * 根据当前上下文输出金叉死叉交易决策。
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
        boolean crossUp = isCrossUp(prev, cur);
        boolean crossDown = isCrossDown(prev, cur);

        if (state.inLong()) {
            if (crossDown) {
                boolean reverseAllowed = !isLargeBar(cur, context.getConfig())
                        && !isShortHold(state, cur, context.getConfig());
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "dif_dea_cross_down", reverseAllowed);
            }
            return LifecycleDecision.none("long_active_no_exit");
        }
        if (state.inShort()) {
            if (crossUp) {
                boolean reverseAllowed = !isLargeBar(cur, context.getConfig())
                        && !isShortHold(state, cur, context.getConfig());
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "dif_dea_cross_up", reverseAllowed);
            }
            return LifecycleDecision.none("short_active_no_exit");
        }
        if (state.hasPendingEntry()) {
            return decidePendingEntry(context, crossUp, crossDown);
        }
        if (crossUp) {
            return startPendingEntryIfAllowed(context, LifecycleDirection.LONG);
        }
        if (crossDown) {
            return startPendingEntryIfAllowed(context, LifecycleDirection.SHORT);
        }
        return LifecycleDecision.none("no_signal");
    }

    /**
     * 计算单根K线波幅百分比。
     */
    public double calculateBarRangePct(LifecycleIndicatorSample sample) {
        if (sample == null || sample.getOpen() == 0.0d) {
            return 0.0d;
        }
        return (sample.getHigh() - sample.getLow()) / sample.getOpen() * 100.0d;
    }

    /**
     * 统计最近窗口内DIF/DEA交叉次数。
     */
    public int calculateRecentCrossCount(java.util.List<LifecycleIndicatorSample> samples, LifecycleConfig config) {
        if (samples == null || samples.size() < 2 || config == null) {
            return 0;
        }
        int lookback = Math.max(2, config.getCrossDensityLookbackBars());
        int from = Math.max(1, samples.size() - lookback);
        int count = 0;
        for (int i = from; i < samples.size(); i++) {
            LifecycleIndicatorSample prev = samples.get(i - 1);
            LifecycleIndicatorSample cur = samples.get(i);
            if (isCrossUp(prev, cur) || isCrossDown(prev, cur)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 通过现有过滤后记录待确认入场信号。
     */
    private LifecycleDecision startPendingEntryIfAllowed(LifecycleContext context, LifecycleDirection direction) {
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        if (isInCooldown(state, cur)) {
            state.clearPendingEntry();
            return LifecycleDecision.none("entry_blocked_by_whipsaw_cooldown");
        }
        if (isCrossDensityBlocked(context)) {
            state.clearPendingEntry();
            return LifecycleDecision.none("entry_blocked_by_cross_density");
        }
        if (isLargeBar(cur, context.getConfig())) {
            state.clearPendingEntry();
            return LifecycleDecision.none("entry_blocked_by_large_bar_range");
        }
        state.startPendingEntry(direction, cur);
        return LifecycleDecision.none("entry_pending_started");
    }

    /**
     * 判断待确认交叉是否在下一根K线获得延续。
     */
    private LifecycleDecision decidePendingEntry(LifecycleContext context, boolean crossUp, boolean crossDown) {
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        int pendingAge = pendingEntryAge(state, cur);
        if (pendingAge > context.getConfig().getPendingEntryMaxBars()) {
            state.clearPendingEntry();
            return LifecycleDecision.none("entry_pending_not_confirmed");
        }
        if (pendingAge < 1) {
            return LifecycleDecision.none("entry_pending_started");
        }
        if (state.getPendingEntryDirection() == LifecycleDirection.LONG) {
            if (crossDown || !isLongPendingConfirmed(state, cur)) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_pending_not_confirmed");
            }
            state.clearPendingEntry();
            return LifecycleDecision.entry(LifecycleDecisionType.ENTER_LONG,
                    "dif_dea_cross_up", "confirmed_dif_dea_cross_up");
        }
        if (state.getPendingEntryDirection() == LifecycleDirection.SHORT) {
            if (crossUp || !isShortPendingConfirmed(state, cur)) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_pending_not_confirmed");
            }
            state.clearPendingEntry();
            return LifecycleDecision.entry(LifecycleDecisionType.ENTER_SHORT,
                    "dif_dea_cross_down", "confirmed_dif_dea_cross_down");
        }
        state.clearPendingEntry();
        return LifecycleDecision.none("entry_pending_not_confirmed");
    }

    /**
     * 判断多头待确认入场是否延续。
     */
    private boolean isLongPendingConfirmed(LifecycleState state, LifecycleIndicatorSample cur) {
        double currentGap = cur.getDif() - cur.getDea();
        return cur.getDif() >= cur.getDea()
                && cur.getMacdBar() > 0.0d
                && cur.getClose() >= state.getPendingEntryClose()
                && (cur.getClose() > state.getPendingEntryHigh()
                || currentGap > state.getPendingEntryDifDeaGap());
    }

    /**
     * 判断空头待确认入场是否延续。
     */
    private boolean isShortPendingConfirmed(LifecycleState state, LifecycleIndicatorSample cur) {
        double currentGap = cur.getDea() - cur.getDif();
        return cur.getDif() <= cur.getDea()
                && cur.getMacdBar() < 0.0d
                && cur.getClose() <= state.getPendingEntryClose()
                && (cur.getClose() < state.getPendingEntryLow()
                || currentGap > state.getPendingEntryDifDeaGap());
    }

    /**
     * 计算待确认入场已经经过的K线数量。
     */
    public int pendingEntryAge(LifecycleState state, LifecycleIndicatorSample sample) {
        if (state == null || sample == null || state.getPendingEntryIndex() < 0) {
            return 0;
        }
        return Math.max(0, sample.getIndex() - state.getPendingEntryIndex());
    }

    /**
     * 判断DIF/DEA金叉。
     */
    private boolean isCrossUp(LifecycleIndicatorSample prev, LifecycleIndicatorSample cur) {
        return prev.getDif() < prev.getDea() && cur.getDif() >= cur.getDea();
    }

    /**
     * 判断DIF/DEA死叉。
     */
    private boolean isCrossDown(LifecycleIndicatorSample prev, LifecycleIndicatorSample cur) {
        return prev.getDif() > prev.getDea() && cur.getDif() <= cur.getDea();
    }

    /**
     * 判断当前K线是否达到大波幅过滤阈值。
     */
    private boolean isLargeBar(LifecycleIndicatorSample sample, LifecycleConfig config) {
        return calculateBarRangePct(sample) >= config.getLargeBarRangePct();
    }

    /**
     * 判断空仓开仓是否被交叉密度过滤。
     */
    private boolean isCrossDensityBlocked(LifecycleContext context) {
        return calculateRecentCrossCount(context.getSamples(), context.getConfig())
                >= context.getConfig().getCrossDensityBlockCount();
    }

    /**
     * 判断当前持仓是否属于短持仓反向交叉。
     */
    private boolean isShortHold(LifecycleState state, LifecycleIndicatorSample sample, LifecycleConfig config) {
        if (state == null || sample == null || config == null || state.getEntryIndex() < 0) {
            return false;
        }
        return Math.max(0, sample.getIndex() - state.getEntryIndex()) <= config.getNoReverseHoldBars();
    }

    /**
     * 判断当前空仓开仓是否处于反复交叉冷却期。
     */
    private boolean isInCooldown(LifecycleState state, LifecycleIndicatorSample sample) {
        return state != null && sample != null && state.getCooldownUntilIndex() >= sample.getIndex();
    }
}
