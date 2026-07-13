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
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "dif_dea_cross_down", false);
            }
            if (isEarlyTrendFailure(state, cur, context.getConfig())) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "early_trend_failure_long", false);
            }
            if (isTrendNotLaunched(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "trend_not_launched_long", false);
            }
            if (isTrendZeroProgress(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "trend_zero_progress_long", false);
            }
            if (isTrendCheckpointGiveback(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "trend_checkpoint_giveback_long", false);
            }
            return LifecycleDecision.none("long_active_no_exit");
        }
        if (state.inShort()) {
            if (crossUp) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "dif_dea_cross_up", false);
            }
            if (isEarlyTrendFailure(state, cur, context.getConfig())) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "early_trend_failure_short", false);
            }
            if (isTrendNotLaunched(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "trend_not_launched_short", false);
            }
            if (isTrendZeroProgress(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "trend_zero_progress_short", false);
            }
            if (isTrendCheckpointGiveback(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "trend_checkpoint_giveback_short", false);
            }
            return LifecycleDecision.none("short_active_no_exit");
        }
        if (state.hasPendingReverse()) {
            return decidePendingReverse(context, crossUp, crossDown);
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
            if (isConfirmationOverextended(LifecycleDirection.LONG,
                    state.getPendingEntryHigh(), state.getPendingEntryLow(), cur, context.getConfig())) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_confirmation_overextended");
            }
            if (!isBreakoutConfirmRatioReached(LifecycleDirection.LONG,
                    state.getPendingEntryHigh(), state.getPendingEntryLow(), cur.getClose(), context.getConfig())) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_pending_breakout_too_shallow");
            }
            state.armEarlyFailureGuard(LifecycleDirection.LONG,
                    state.getPendingEntryHigh(), state.getPendingEntryLow(), cur.getMacdBar());
            state.clearPendingEntry();
            return LifecycleDecision.entry(LifecycleDecisionType.ENTER_LONG,
                    "dif_dea_cross_up", "confirmed_dif_dea_cross_up");
        }
        if (state.getPendingEntryDirection() == LifecycleDirection.SHORT) {
            if (crossUp || !isShortPendingConfirmed(state, cur)) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_pending_not_confirmed");
            }
            if (isConfirmationOverextended(LifecycleDirection.SHORT,
                    state.getPendingEntryHigh(), state.getPendingEntryLow(), cur, context.getConfig())) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_confirmation_overextended");
            }
            if (!isBreakoutConfirmRatioReached(LifecycleDirection.SHORT,
                    state.getPendingEntryHigh(), state.getPendingEntryLow(), cur.getClose(), context.getConfig())) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_pending_breakout_too_shallow");
            }
            state.armEarlyFailureGuard(LifecycleDirection.SHORT,
                    state.getPendingEntryHigh(), state.getPendingEntryLow(), cur.getMacdBar());
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
        return cur.getDif() >= cur.getDea()
                && cur.getMacdBar() > 0.0d
                && cur.getClose() >= state.getPendingEntryClose()
                && cur.getClose() > state.getPendingEntryHigh();
    }

    /**
     * 判断空头待确认入场是否延续。
     */
    private boolean isShortPendingConfirmed(LifecycleState state, LifecycleIndicatorSample cur) {
        return cur.getDif() <= cur.getDea()
                && cur.getMacdBar() < 0.0d
                && cur.getClose() <= state.getPendingEntryClose()
                && cur.getClose() < state.getPendingEntryLow();
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
     * 判断反向交叉平仓后是否允许进入待确认反手。
     */
    public boolean canStartPendingReverse(LifecycleState state, LifecycleIndicatorSample sample,
                                          LifecycleConfig config) {
        return state != null
                && sample != null
                && config != null
                && !isLargeBar(sample, config)
                && !isShortHold(state, sample, config)
                && !isReverseBlockedByProfitableWeakCross(state, sample, config);
    }

    /**
     * 判断待确认反手是否在下一根K线获得延续。
     */
    private LifecycleDecision decidePendingReverse(LifecycleContext context, boolean crossUp, boolean crossDown) {
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        int pendingAge = pendingReverseAge(state, cur);
        if (pendingAge > context.getConfig().getPendingReverseMaxBars()) {
            state.clearPendingReverse();
            return LifecycleDecision.none("reverse_pending_not_confirmed");
        }
        if (pendingAge < 1) {
            return LifecycleDecision.none("reverse_pending_started");
        }
        if (state.getPendingReverseDirection() == LifecycleDirection.LONG) {
            if (crossDown || !isLongReverseConfirmed(state, cur)) {
                state.clearPendingReverse();
                return LifecycleDecision.none("reverse_pending_not_confirmed");
            }
            if (isConfirmationOverextended(LifecycleDirection.LONG,
                    state.getPendingReverseHigh(), state.getPendingReverseLow(), cur, context.getConfig())) {
                state.clearPendingReverse();
                return LifecycleDecision.none("reverse_confirmation_overextended");
            }
            if (!isBreakoutConfirmRatioReached(LifecycleDirection.LONG,
                    state.getPendingReverseHigh(), state.getPendingReverseLow(), cur.getClose(), context.getConfig())) {
                state.clearPendingReverse();
                return LifecycleDecision.none("reverse_pending_breakout_too_shallow");
            }
            state.armEarlyFailureGuard(LifecycleDirection.LONG,
                    state.getPendingReverseHigh(), state.getPendingReverseLow(), cur.getMacdBar());
            state.clearPendingReverse();
            return LifecycleDecision.entry(LifecycleDecisionType.ENTER_LONG,
                    "dif_dea_cross_up", "confirmed_reverse_long_after_dif_dea_cross_up");
        }
        if (state.getPendingReverseDirection() == LifecycleDirection.SHORT) {
            if (crossUp || !isShortReverseConfirmed(state, cur)) {
                state.clearPendingReverse();
                return LifecycleDecision.none("reverse_pending_not_confirmed");
            }
            if (isConfirmationOverextended(LifecycleDirection.SHORT,
                    state.getPendingReverseHigh(), state.getPendingReverseLow(), cur, context.getConfig())) {
                state.clearPendingReverse();
                return LifecycleDecision.none("reverse_confirmation_overextended");
            }
            if (!isBreakoutConfirmRatioReached(LifecycleDirection.SHORT,
                    state.getPendingReverseHigh(), state.getPendingReverseLow(), cur.getClose(), context.getConfig())) {
                state.clearPendingReverse();
                return LifecycleDecision.none("reverse_pending_breakout_too_shallow");
            }
            state.armEarlyFailureGuard(LifecycleDirection.SHORT,
                    state.getPendingReverseHigh(), state.getPendingReverseLow(), cur.getMacdBar());
            state.clearPendingReverse();
            return LifecycleDecision.entry(LifecycleDecisionType.ENTER_SHORT,
                    "dif_dea_cross_down", "confirmed_reverse_short_after_dif_dea_cross_down");
        }
        state.clearPendingReverse();
        return LifecycleDecision.none("reverse_pending_not_confirmed");
    }

    /**
     * 判断反手做多确认条件是否成立。
     */
    private boolean isLongReverseConfirmed(LifecycleState state, LifecycleIndicatorSample cur) {
        return cur.getDif() >= cur.getDea()
                && cur.getMacdBar() > 0.0d
                && cur.getClose() >= state.getPendingReverseClose()
                && cur.getClose() > state.getPendingReverseHigh();
    }

    /**
     * 判断反手做空确认条件是否成立。
     */
    private boolean isShortReverseConfirmed(LifecycleState state, LifecycleIndicatorSample cur) {
        return cur.getDif() <= cur.getDea()
                && cur.getMacdBar() < 0.0d
                && cur.getClose() <= state.getPendingReverseClose()
                && cur.getClose() < cur.getMa10()
                && cur.getMa10() <= cur.getMa20()
                && cur.getClose() < state.getPendingReverseLow();
    }

    /**
     * 计算当前待确认信号的相对突破强度。
     */
    public double calculatePendingBreakoutRatio(LifecycleState state, LifecycleIndicatorSample cur) {
        if (state == null || cur == null) {
            return Double.NaN;
        }
        if (state.hasPendingReverse()) {
            return calculateBreakoutConfirmRatio(state.getPendingReverseDirection(),
                    state.getPendingReverseHigh(), state.getPendingReverseLow(), cur.getClose());
        }
        if (state.hasPendingEntry()) {
            return calculateBreakoutConfirmRatio(state.getPendingEntryDirection(),
                    state.getPendingEntryHigh(), state.getPendingEntryLow(), cur.getClose());
        }
        return Double.NaN;
    }

    /**
     * 判断相对信号K线波幅的突破强度是否达到配置阈值。
     */
    private boolean isBreakoutConfirmRatioReached(LifecycleDirection direction,
                                                   double signalHigh,
                                                   double signalLow,
                                                   double close,
                                                   LifecycleConfig config) {
        if (config == null) {
            return false;
        }
        double ratio = calculateBreakoutConfirmRatio(direction, signalHigh, signalLow, close);
        return !Double.isNaN(ratio)
                && !Double.isInfinite(ratio)
                && ratio >= config.getMinBreakoutConfirmRatio();
    }

    /**
     * 判断确认K线是否同时出现大波幅和相对信号K线的过深突破。
     */
    private boolean isConfirmationOverextended(LifecycleDirection direction,
                                               double signalHigh,
                                               double signalLow,
                                               LifecycleIndicatorSample sample,
                                               LifecycleConfig config) {
        if (sample == null || config == null) {
            return false;
        }
        double breakoutRatio = calculateBreakoutConfirmRatio(direction,
                signalHigh, signalLow, sample.getClose());
        return calculateBarRangePct(sample) >= config.getOverextendedConfirmBarRangePct()
                && !Double.isNaN(breakoutRatio)
                && !Double.isInfinite(breakoutRatio)
                && breakoutRatio >= config.getOverextendedConfirmBreakoutRatio();
    }

    /**
     * 按多空方向计算突破信号K线高低点的相对比例。
     */
    private double calculateBreakoutConfirmRatio(LifecycleDirection direction,
                                                  double signalHigh,
                                                  double signalLow,
                                                  double close) {
        double signalRange = signalHigh - signalLow;
        if (direction == null || direction == LifecycleDirection.NONE
                || Double.isNaN(signalRange) || Double.isInfinite(signalRange) || signalRange <= 0.0d
                || Double.isNaN(close) || Double.isInfinite(close)) {
            return Double.NaN;
        }
        double breakout = direction == LifecycleDirection.LONG
                ? close - signalHigh
                : signalLow - close;
        return breakout / signalRange;
    }

    /**
     * 计算待确认反手已经经过的K线数量。
     */
    public int pendingReverseAge(LifecycleState state, LifecycleIndicatorSample sample) {
        if (state == null || sample == null || state.getPendingReverseIndex() < 0) {
            return 0;
        }
        return Math.max(0, sample.getIndex() - state.getPendingReverseIndex());
    }

    /**
     * 判断新仓是否同时失去突破价格结构和MACD动能。
     */
    public boolean isEarlyTrendFailure(LifecycleState state, LifecycleIndicatorSample sample,
                                       LifecycleConfig config) {
        if (!isInEarlyFailureWindow(state, sample, config)
                || Double.isNaN(state.getEntryMacdStrength())
                || state.getEntryMacdStrength() <= 0.0d) {
            return false;
        }
        double currentStrength = state.inShort() ? -sample.getMacdBar() : sample.getMacdBar();
        boolean momentumFailed = currentStrength
                <= state.getEntryMacdStrength() * config.getEarlyFailureMacdRetentionRatio();
        boolean priceFailed = state.inLong()
                ? sample.getClose() <= state.getEntrySignalLow()
                : sample.getClose() >= state.getEntrySignalHigh();
        return priceFailed && momentumFailed;
    }

    /**
     * 判断当前K线是否处于新仓早期趋势失败观察窗口。
     */
    public boolean isInEarlyFailureWindow(LifecycleState state, LifecycleIndicatorSample sample,
                                          LifecycleConfig config) {
        if (state == null || sample == null || config == null || state.getEntryIndex() < 0
                || (!state.inLong() && !state.inShort())) {
            return false;
        }
        int holdBars = Math.max(0, sample.getIndex() - state.getEntryIndex());
        return holdBars >= 1 && holdBars <= config.getEarlyFailureMaxHoldBars();
    }

    /**
     * 计算当前方向性MACD动能相对入场时的保留比例。
     */
    public double calculateCurrentMacdRetentionRatio(LifecycleState state, LifecycleIndicatorSample sample) {
        if (state == null || sample == null || Double.isNaN(state.getEntryMacdStrength())
                || state.getEntryMacdStrength() <= 0.0d || (!state.inLong() && !state.inShort())) {
            return Double.NaN;
        }
        double currentStrength = state.inShort() ? -sample.getMacdBar() : sample.getMacdBar();
        return currentStrength / state.getEntryMacdStrength();
    }

    /**
     * 判断持仓到第八根时是否仍未形成有效方向推进。
     */
    public boolean isTrendNotLaunched(LifecycleContext context) {
        if (context == null || context.getState() == null || context.getConfig() == null
                || context.getSamples() == null || context.getSamples().isEmpty()) {
            return false;
        }
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        int holdBars = Math.max(0, cur.getIndex() - state.getEntryIndex());
        if (holdBars != context.getConfig().getNonLaunchCheckHoldBars()
                || Double.isNaN(state.getEntryPrice()) || state.getEntryPrice() <= 0.0d) {
            return false;
        }
        boolean boundaryInvalidated = false;
        double maxFavorableProgressPct = 0.0d;
        for (LifecycleIndicatorSample sample : context.getSamples()) {
            if (sample.getIndex() <= state.getEntryIndex() || sample.getIndex() > cur.getIndex()) {
                continue;
            }
            if (state.inLong()) {
                boundaryInvalidated |= sample.getClose() <= state.getEntrySignalLow();
                maxFavorableProgressPct = Math.max(maxFavorableProgressPct,
                        (sample.getClose() - state.getEntryPrice()) / state.getEntryPrice() * 100.0d);
            } else if (state.inShort()) {
                boundaryInvalidated |= sample.getClose() >= state.getEntrySignalHigh();
                maxFavorableProgressPct = Math.max(maxFavorableProgressPct,
                        (state.getEntryPrice() - sample.getClose()) / state.getEntryPrice() * 100.0d);
            }
        }
        boolean currentlyLosing = state.inLong()
                ? cur.getClose() < state.getEntryPrice()
                : cur.getClose() > state.getEntryPrice();
        return boundaryInvalidated
                && maxFavorableProgressPct < context.getConfig().getNonLaunchMinFavorableProgressPct()
                && currentlyLosing;
    }

    /**
     * 判断持仓到第八根时是否仍处于亏损且几乎没有方向推进。
     */
    public boolean isTrendZeroProgress(LifecycleContext context) {
        if (context == null || context.getState() == null || context.getConfig() == null
                || context.getSamples() == null || context.getSamples().isEmpty()) {
            return false;
        }
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        int holdBars = Math.max(0, cur.getIndex() - state.getEntryIndex());
        if (holdBars != context.getConfig().getNonLaunchCheckHoldBars()) {
            return false;
        }
        double maxFavorableProgressPct = calculateMaxFavorableProgressPct(context);
        if (Double.isNaN(maxFavorableProgressPct)
                || maxFavorableProgressPct >= context.getConfig().getNonLaunchZeroProgressPct()) {
            return false;
        }
        return state.inLong()
                ? cur.getClose() < state.getEntryPrice()
                : state.inShort() && cur.getClose() > state.getEntryPrice();
    }

    /**
     * 判断持仓第八至十二根是否已将有效浮盈全部回吐且MACD动能同步衰减。
     */
    public boolean isTrendCheckpointGiveback(LifecycleContext context) {
        if (context == null || context.getState() == null || context.getConfig() == null
                || context.getSamples() == null || context.getSamples().isEmpty()) {
            return false;
        }
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        int holdBars = Math.max(0, cur.getIndex() - state.getEntryIndex());
        if (holdBars < context.getConfig().getNonLaunchCheckHoldBars()
                || holdBars > context.getConfig().getCheckpointGivebackMaxHoldBars()) {
            return false;
        }
        double maxFavorableProgressPct = calculateMaxFavorableProgressPct(context);
        double macdRetentionRatio = calculateCurrentMacdRetentionRatio(state, cur);
        if (Double.isNaN(maxFavorableProgressPct) || Double.isNaN(macdRetentionRatio)
                || maxFavorableProgressPct < context.getConfig().getCheckpointGivebackMinFavorablePct()
                || macdRetentionRatio > context.getConfig().getCheckpointGivebackMacdRetentionRatio()) {
            return false;
        }
        return state.inLong()
                ? cur.getClose() < state.getEntryPrice()
                : state.inShort() && cur.getClose() > state.getEntryPrice();
    }

    /**
     * 计算当前持仓以来最大的方向性收盘浮盈百分比。
     */
    public double calculateMaxFavorableProgressPct(LifecycleContext context) {
        if (context == null || context.getState() == null || context.getSamples() == null
                || context.getSamples().isEmpty() || Double.isNaN(context.getState().getEntryPrice())
                || context.getState().getEntryPrice() <= 0.0d
                || (!context.getState().inLong() && !context.getState().inShort())) {
            return Double.NaN;
        }
        LifecycleState state = context.getState();
        double maxProgressPct = 0.0d;
        for (LifecycleIndicatorSample sample : context.getSamples()) {
            if (sample.getIndex() <= state.getEntryIndex() || sample.getIndex() > context.current().getIndex()) {
                continue;
            }
            double progressPct = state.inLong()
                    ? (sample.getClose() - state.getEntryPrice()) / state.getEntryPrice() * 100.0d
                    : (state.getEntryPrice() - sample.getClose()) / state.getEntryPrice() * 100.0d;
            maxProgressPct = Math.max(maxProgressPct, progressPct);
        }
        return maxProgressPct;
    }

    /**
     * 判断当前持仓期间是否曾收盘穿越信号K线反向边界。
     */
    public boolean wasEntrySignalBoundaryInvalidated(LifecycleContext context) {
        if (context == null || context.getState() == null || context.getSamples() == null
                || (!context.getState().inLong() && !context.getState().inShort())) {
            return false;
        }
        LifecycleState state = context.getState();
        for (LifecycleIndicatorSample sample : context.getSamples()) {
            if (sample.getIndex() <= state.getEntryIndex() || sample.getIndex() > context.current().getIndex()) {
                continue;
            }
            if ((state.inLong() && sample.getClose() <= state.getEntrySignalLow())
                    || (state.inShort() && sample.getClose() >= state.getEntrySignalHigh())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断盈利长持仓后的弱反向交叉是否只平不反手。
     */
    public boolean isReverseBlockedByProfitableWeakCross(LifecycleState state, LifecycleIndicatorSample sample,
                                                         LifecycleConfig config) {
        if (state == null || sample == null || config == null || state.getEntryIndex() < 0
                || Double.isNaN(state.getEntryPrice()) || state.getEntryPrice() == 0.0d) {
            return false;
        }
        int holdBars = Math.max(0, sample.getIndex() - state.getEntryIndex());
        if (holdBars < config.getProfitableReverseFilterHoldBars()) {
            return false;
        }
        if (state.inLong()) {
            double profitPct = (sample.getClose() - state.getEntryPrice()) / state.getEntryPrice() * 100.0d;
            double reverseGap = sample.getDea() - sample.getDif();
            boolean structureNotReversed = sample.getClose() >= sample.getMa10()
                    && sample.getClose() >= sample.getMa20();
            return profitPct >= config.getProfitableReverseFilterMinProfitPct()
                    && reverseGap >= 0.0d
                    && reverseGap < config.getProfitableReverseDifDeaGapMin()
                    && structureNotReversed;
        }
        if (state.inShort()) {
            double profitPct = (state.getEntryPrice() - sample.getClose()) / state.getEntryPrice() * 100.0d;
            double reverseGap = sample.getDif() - sample.getDea();
            boolean structureNotReversed = sample.getClose() <= sample.getMa10()
                    && sample.getClose() <= sample.getMa20();
            return profitPct >= config.getProfitableReverseFilterMinProfitPct()
                    && reverseGap >= 0.0d
                    && reverseGap < config.getProfitableReverseDifDeaGapMin()
                    && structureNotReversed;
        }
        return false;
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
