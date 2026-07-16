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
            if (state.isProfitExtensionActive()) {
                return decideActiveProfitExtension(context);
            }
            if (crossDown) {
                double reverseGap = calculateReverseGap(state, cur);
                if (shouldStartProfitExtension(context, reverseGap)) {
                    state.startProfitExtension();
                    return LifecycleDecision.none("profit_extension_started_long");
                }
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "dif_dea_cross_down", false);
            }
            if (isEarlyTrendFailure(state, cur, context.getConfig())) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "early_trend_failure_long", false);
            }
            if (isEarlyNonLaunchTrendFailure(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "trend_fifth_bar_failure_long", false);
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
            if (isEarlyProfitRoundTrip(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "early_profit_round_trip_long", false);
            }
            if (isWeakMatureProfitGiveback(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "weak_mature_profit_giveback_long", false);
            }
            if (isMatureProfitGiveback(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "mature_profit_giveback_long", false);
            }
            return LifecycleDecision.none("long_active_no_exit");
        }
        if (state.inShort()) {
            if (state.isProfitExtensionActive()) {
                return decideActiveProfitExtension(context);
            }
            if (crossUp) {
                double reverseGap = calculateReverseGap(state, cur);
                if (shouldStartProfitExtension(context, reverseGap)) {
                    state.startProfitExtension();
                    return LifecycleDecision.none("profit_extension_started_short");
                }
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "dif_dea_cross_up", false);
            }
            if (isEarlyTrendFailure(state, cur, context.getConfig())) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "early_trend_failure_short", false);
            }
            if (isEarlyNonLaunchTrendFailure(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "trend_fifth_bar_failure_short", false);
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
            if (isEarlyProfitRoundTrip(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "early_profit_round_trip_short", false);
            }
            if (isWeakMatureProfitGiveback(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "weak_mature_profit_giveback_short", false);
            }
            if (isMatureProfitGiveback(context)) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                        "mature_profit_giveback_short", false);
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
     * 处理成熟盈利仓进入延续保护后的恢复、利润底线和结构反转确认。
     */
    private LifecycleDecision decideActiveProfitExtension(LifecycleContext context) {
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        LifecycleConfig config = context.getConfig();
        double currentProgressPct = calculateCurrentProgressPct(state, cur);
        double reverseGap = calculateReverseGap(state, cur);

        if (state.inLong()) {
            if (cur.getDif() >= cur.getDea()) {
                state.clearProfitExtension();
                return LifecycleDecision.none("profit_extension_recovered_long");
            }
            if (currentProgressPct <= config.getProfitableReverseFilterMinProfitPct()) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "profit_extension_floor_long", false);
            }
            if (reverseGap >= config.getProfitableReverseDifDeaGapMin()
                    && cur.getClose() < cur.getMa10() && cur.getClose() < cur.getMa20()) {
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG,
                        "profit_extension_reversal_confirmed_long", false);
            }
            return LifecycleDecision.none("profit_extension_active_long");
        }

        if (cur.getDif() <= cur.getDea()) {
            state.clearProfitExtension();
            return LifecycleDecision.none("profit_extension_recovered_short");
        }
        if (currentProgressPct <= config.getProfitableReverseFilterMinProfitPct()) {
            return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                    "profit_extension_floor_short", false);
        }
        if (reverseGap >= config.getProfitableReverseDifDeaGapMin()
                && cur.getClose() > cur.getMa10() && cur.getClose() > cur.getMa20()) {
            return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT,
                    "profit_extension_reversal_confirmed_short", false);
        }
        return LifecycleDecision.none("profit_extension_active_short");
    }

    /**
     * 判断弱反向交叉是否满足成熟盈利延续保护条件。
     */
    private boolean shouldStartProfitExtension(LifecycleContext context, double reverseGap) {
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        LifecycleConfig config = context.getConfig();
        int holdBars = Math.max(0, cur.getIndex() - state.getEntryIndex());
        double maxFavorableProgressPct = calculateMaxFavorableProgressPct(context);
        double currentProgressPct = calculateCurrentProgressPct(state, cur);
        return holdBars >= config.getProfitableReverseFilterHoldBars()
                && Double.isFinite(maxFavorableProgressPct)
                && maxFavorableProgressPct >= config.getProfitGivebackActivationPct()
                && Double.isFinite(currentProgressPct)
                && currentProgressPct >= config.getProfitableReverseFilterMinProfitPct()
                && Double.isFinite(reverseGap)
                && reverseGap >= 0.0d
                && reverseGap < config.getProfitableReverseDifDeaGapMin();
    }

    /**
     * 计算当前持仓方向对应的反向DIF/DEA张口。
     */
    public double calculateReverseGap(LifecycleState state, LifecycleIndicatorSample sample) {
        if (state == null || sample == null) {
            return Double.NaN;
        }
        if (state.inLong()) {
            return sample.getDea() - sample.getDif();
        }
        if (state.inShort()) {
            return sample.getDif() - sample.getDea();
        }
        return Double.NaN;
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
        if (state.isLaunchRecoverySecondConfirmationPending()) {
            return decideLaunchRecoverySecondConfirmation(context);
        }
        if (state.isWeakCounterTrendSecondConfirmationPending()) {
            return decideWeakCounterTrendSecondConfirmation(context);
        }
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
            if (isExtremeCounterTrendConfirmationOverextended(context, LifecycleDirection.LONG,
                    state.getPendingEntryHigh(), state.getPendingEntryLow())) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_confirmation_extreme_countertrend_overextended");
            }
            if (isExtremeCounterTrendStructureMissing(context, LifecycleDirection.LONG)) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_confirmation_extreme_countertrend_structure_missing");
            }
            if (!isBreakoutConfirmRatioReached(LifecycleDirection.LONG,
                    state.getPendingEntryHigh(), state.getPendingEntryLow(), cur.getClose(), context.getConfig())) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_pending_breakout_too_shallow");
            }
            if (isEntryBlockedByRecentLaunchFailure(context, LifecycleDirection.LONG)) {
                state.clearPendingEntry();
                state.startLaunchRecovery(context.getConfig().getLaunchRecoveryConfirmAttempts());
                return LifecycleDecision.none("entry_blocked_by_recent_launch_failure");
            }
            if (state.getLaunchRecoveryConfirmAttemptsRemaining() > 0) {
                state.startLaunchRecoverySecondConfirmation(LifecycleDirection.LONG, cur);
                return LifecycleDecision.none("launch_recovery_first_confirmed");
            }
            if (shouldStartWeakCounterTrendSecondConfirmation(context, LifecycleDirection.LONG,
                    state.getPendingEntryHigh(), state.getPendingEntryLow())) {
                state.startWeakCounterTrendSecondConfirmation(LifecycleDirection.LONG, cur);
                return LifecycleDecision.none("weak_countertrend_first_confirmed");
            }
            String ma20DistanceRejection = ma20EntryDistanceRejection(context, LifecycleDirection.LONG);
            if (!ma20DistanceRejection.isEmpty()) {
                state.clearPendingEntry();
                return LifecycleDecision.none(ma20DistanceRejection);
            }
            String steepTrendRejection = steepMa20WeakMacdRejection(context);
            if (!steepTrendRejection.isEmpty()) {
                state.clearPendingEntry();
                return LifecycleDecision.none(steepTrendRejection);
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
            if (isExtremeCounterTrendConfirmationOverextended(context, LifecycleDirection.SHORT,
                    state.getPendingEntryHigh(), state.getPendingEntryLow())) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_confirmation_extreme_countertrend_overextended");
            }
            if (isExtremeCounterTrendStructureMissing(context, LifecycleDirection.SHORT)) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_confirmation_extreme_countertrend_structure_missing");
            }
            if (!isBreakoutConfirmRatioReached(LifecycleDirection.SHORT,
                    state.getPendingEntryHigh(), state.getPendingEntryLow(), cur.getClose(), context.getConfig())) {
                state.clearPendingEntry();
                return LifecycleDecision.none("entry_pending_breakout_too_shallow");
            }
            if (isEntryBlockedByRecentLaunchFailure(context, LifecycleDirection.SHORT)) {
                state.clearPendingEntry();
                state.startLaunchRecovery(context.getConfig().getLaunchRecoveryConfirmAttempts());
                return LifecycleDecision.none("entry_blocked_by_recent_launch_failure");
            }
            if (state.getLaunchRecoveryConfirmAttemptsRemaining() > 0) {
                state.startLaunchRecoverySecondConfirmation(LifecycleDirection.SHORT, cur);
                return LifecycleDecision.none("launch_recovery_first_confirmed");
            }
            if (shouldStartWeakCounterTrendSecondConfirmation(context, LifecycleDirection.SHORT,
                    state.getPendingEntryHigh(), state.getPendingEntryLow())) {
                state.startWeakCounterTrendSecondConfirmation(LifecycleDirection.SHORT, cur);
                return LifecycleDecision.none("weak_countertrend_first_confirmed");
            }
            String ma20DistanceRejection = ma20EntryDistanceRejection(context, LifecycleDirection.SHORT);
            if (!ma20DistanceRejection.isEmpty()) {
                state.clearPendingEntry();
                return LifecycleDecision.none(ma20DistanceRejection);
            }
            String steepTrendRejection = steepMa20WeakMacdRejection(context);
            if (!steepTrendRejection.isEmpty()) {
                state.clearPendingEntry();
                return LifecycleDecision.none(steepTrendRejection);
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
     * 判断恢复期第一根确认后的下一根K线是否继续沿原方向推进。
     */
    private LifecycleDecision decideLaunchRecoverySecondConfirmation(LifecycleContext context) {
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        int pendingAge = pendingEntryAge(state, cur);
        if (pendingAge < 1) {
            return LifecycleDecision.none("launch_recovery_first_confirmed");
        }
        if (pendingAge > 1) {
            state.clearPendingEntry();
            return LifecycleDecision.none("launch_recovery_not_confirmed");
        }
        if (isLargeBar(cur, context.getConfig())) {
            state.clearPendingEntry();
            return LifecycleDecision.none("launch_recovery_blocked_by_large_bar");
        }

        LifecycleDirection direction = state.getPendingEntryDirection();
        double signalHigh = state.getPendingEntryHigh();
        double signalLow = state.getPendingEntryLow();
        boolean confirmed = isLaunchRecoverySecondConfirmed(state, cur, direction);
        if (!confirmed) {
            state.clearPendingEntry();
            return LifecycleDecision.none("launch_recovery_not_confirmed");
        }
        if (!isBreakoutConfirmRatioReached(direction, signalHigh, signalLow,
                cur.getClose(), context.getConfig())) {
            state.clearPendingEntry();
            return LifecycleDecision.none("launch_recovery_breakout_too_shallow");
        }
        String ma20DistanceRejection = ma20EntryDistanceRejection(context, direction);
        if (!ma20DistanceRejection.isEmpty()) {
            state.clearPendingEntry();
            return LifecycleDecision.none(ma20DistanceRejection);
        }
        String steepTrendRejection = steepMa20WeakMacdRejection(context);
        if (!steepTrendRejection.isEmpty()) {
            state.clearPendingEntry();
            return LifecycleDecision.none(steepTrendRejection);
        }

        state.armEarlyFailureGuard(direction, signalHigh, signalLow, cur.getMacdBar());
        state.clearPendingEntry();
        if (direction == LifecycleDirection.LONG) {
            return LifecycleDecision.entry(LifecycleDecisionType.ENTER_LONG,
                    "dif_dea_cross_up", "launch_recovery_confirmed_dif_dea_cross_up");
        }
        return LifecycleDecision.entry(LifecycleDecisionType.ENTER_SHORT,
                "dif_dea_cross_down", "launch_recovery_confirmed_dif_dea_cross_down");
    }

    /**
     * 检查恢复期第二根K线的方向、MACD和收盘是否延续。
     */
    private boolean isLaunchRecoverySecondConfirmed(LifecycleState state,
                                                    LifecycleIndicatorSample cur,
                                                    LifecycleDirection direction) {
        if (direction == LifecycleDirection.LONG) {
            return cur.getDif() >= cur.getDea()
                    && cur.getMacdBar() > 0.0d
                    && cur.getClose() >= state.getPendingEntryClose();
        }
        if (direction == LifecycleDirection.SHORT) {
            return cur.getDif() <= cur.getDea()
                    && cur.getMacdBar() < 0.0d
                    && cur.getClose() <= state.getPendingEntryClose();
        }
        return false;
    }

    /**
     * 判断普通确认是否处于轻度逆势且突破强度失真的区间。
     */
    private boolean shouldStartWeakCounterTrendSecondConfirmation(LifecycleContext context,
                                                                  LifecycleDirection direction,
                                                                  double signalHigh,
                                                                  double signalLow) {
        if (context == null || context.getConfig() == null || direction == null
                || direction == LifecycleDirection.NONE) {
            return false;
        }
        LifecycleConfig config = context.getConfig();
        double ma20TrendPct = calculateMa20TrendPct(context);
        double weakThreshold = config.getMa20TrendThresholdPct();
        double moderateThreshold = config.getModerateCounterTrendThresholdPct();
        boolean weakCounterTrend = direction == LifecycleDirection.LONG
                ? ma20TrendPct <= -weakThreshold && ma20TrendPct > -moderateThreshold
                : ma20TrendPct >= weakThreshold && ma20TrendPct < moderateThreshold;
        if (!weakCounterTrend) {
            return false;
        }
        double breakoutRatio = calculateBreakoutConfirmRatio(direction,
                signalHigh, signalLow, context.current().getClose());
        return Double.isFinite(breakoutRatio)
                && (breakoutRatio < config.getWeakCounterTrendBreakoutMinRatio()
                || breakoutRatio >= config.getWeakCounterTrendBreakoutMaxRatio());
    }

    /**
     * 处理轻度逆势第一根确认后的下一根K线。
     */
    private LifecycleDecision decideWeakCounterTrendSecondConfirmation(LifecycleContext context) {
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        int pendingAge = pendingEntryAge(state, cur);
        if (pendingAge < 1) {
            return LifecycleDecision.none("weak_countertrend_first_confirmed");
        }
        if (pendingAge > 1) {
            state.clearPendingEntry();
            return LifecycleDecision.none("weak_countertrend_not_confirmed");
        }
        if (isLargeBar(cur, context.getConfig())) {
            state.clearPendingEntry();
            return LifecycleDecision.none("weak_countertrend_blocked_by_large_bar");
        }

        LifecycleDirection direction = state.getPendingEntryDirection();
        double signalHigh = state.getPendingEntryHigh();
        double signalLow = state.getPendingEntryLow();
        if (!isWeakCounterTrendSecondConfirmed(state, cur, direction, context.getConfig())) {
            state.clearPendingEntry();
            return LifecycleDecision.none("weak_countertrend_not_confirmed");
        }
        String ma20DistanceRejection = ma20EntryDistanceRejection(context, direction);
        if (!ma20DistanceRejection.isEmpty()) {
            state.clearPendingEntry();
            return LifecycleDecision.none(ma20DistanceRejection);
        }
        String steepTrendRejection = steepMa20WeakMacdRejection(context);
        if (!steepTrendRejection.isEmpty()) {
            state.clearPendingEntry();
            return LifecycleDecision.none(steepTrendRejection);
        }

        state.armEarlyFailureGuard(direction, signalHigh, signalLow, cur.getMacdBar());
        state.clearPendingEntry();
        if (direction == LifecycleDirection.LONG) {
            return LifecycleDecision.entry(LifecycleDecisionType.ENTER_LONG,
                    "dif_dea_cross_up", "weak_countertrend_confirmed_dif_dea_cross_up");
        }
        return LifecycleDecision.entry(LifecycleDecisionType.ENTER_SHORT,
                "dif_dea_cross_down", "weak_countertrend_confirmed_dif_dea_cross_down");
    }

    /**
     * 检查轻度逆势第二根K线是否保持方向、价格和张口延续。
     */
    private boolean isWeakCounterTrendSecondConfirmed(LifecycleState state,
                                                      LifecycleIndicatorSample cur,
                                                      LifecycleDirection direction,
                                                      LifecycleConfig config) {
        double tolerance = config.getWeakCounterTrendConfirmPullbackTolerancePct() / 100.0d;
        if (direction == LifecycleDirection.LONG) {
            double currentGap = cur.getDif() - cur.getDea();
            return cur.getDif() >= cur.getDea()
                    && cur.getMacdBar() > 0.0d
                    && cur.getClose() >= state.getPendingEntryClose() * (1.0d - tolerance)
                    && (cur.getClose() > state.getPendingEntryHigh()
                    || currentGap > state.getPendingEntryDifDeaGap());
        }
        if (direction == LifecycleDirection.SHORT) {
            double currentGap = cur.getDea() - cur.getDif();
            return cur.getDif() <= cur.getDea()
                    && cur.getMacdBar() < 0.0d
                    && cur.getClose() <= state.getPendingEntryClose() * (1.0d + tolerance)
                    && (cur.getClose() < state.getPendingEntryLow()
                    || currentGap > state.getPendingEntryDifDeaGap());
        }
        return false;
    }

    /**
     * 连续趋势未启动后，仅拦截下一次逆MA20的普通确认入场。
     */
    private boolean isEntryBlockedByRecentLaunchFailure(LifecycleContext context,
                                                        LifecycleDirection direction) {
        if (context == null || context.getState() == null || context.getConfig() == null
                || !context.getState().isLaunchCautionMode()) {
            return false;
        }
        double ma20TrendPct = calculateMa20TrendPct(context);
        if (!Double.isFinite(ma20TrendPct)) {
            return false;
        }
        double threshold = context.getConfig().getMa20TrendThresholdPct();
        return direction == LifecycleDirection.LONG
                ? ma20TrendPct <= -threshold
                : direction == LifecycleDirection.SHORT && ma20TrendPct >= threshold;
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
            String ma20DistanceRejection = ma20EntryDistanceRejection(context, LifecycleDirection.LONG);
            if (!ma20DistanceRejection.isEmpty()) {
                state.clearPendingReverse();
                return LifecycleDecision.none(ma20DistanceRejection);
            }
            String steepTrendRejection = steepMa20WeakMacdRejection(context);
            if (!steepTrendRejection.isEmpty()) {
                state.clearPendingReverse();
                return LifecycleDecision.none(steepTrendRejection);
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
            String ma20DistanceRejection = ma20EntryDistanceRejection(context, LifecycleDirection.SHORT);
            if (!ma20DistanceRejection.isEmpty()) {
                state.clearPendingReverse();
                return LifecycleDecision.none(ma20DistanceRejection);
            }
            String steepTrendRejection = steepMa20WeakMacdRejection(context);
            if (!steepTrendRejection.isEmpty()) {
                state.clearPendingReverse();
                return LifecycleDecision.none(steepTrendRejection);
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
     * 返回价格距离5M MA20过近或过远的开仓拒绝原因。
     */
    private String ma20EntryDistanceRejection(LifecycleContext context, LifecycleDirection direction) {
        double distancePct = calculateMa20EntryDistancePct(context, direction);
        if (!Double.isFinite(distancePct)
                || distancePct < context.getConfig().getMa20EntryDistanceMinPct()) {
            return "entry_blocked_by_5m_ma20_distance_too_close";
        }
        if (distancePct > context.getConfig().getMa20EntryDistanceMaxPct()) {
            return "entry_blocked_by_5m_ma20_overextended";
        }
        return "";
    }

    /**
     * MA20已经明显陡峭但当前交叉动能偏弱时拒绝开仓。
     */
    private String steepMa20WeakMacdRejection(LifecycleContext context) {
        if (context == null || context.getConfig() == null || context.current() == null) {
            return "";
        }
        double ma20TrendPct = calculateMa20TrendPct(context);
        double macdStrengthPct = calculateEntryMacdStrengthPct(context.current());
        if (Double.isFinite(ma20TrendPct)
                && Double.isFinite(macdStrengthPct)
                && Math.abs(ma20TrendPct) >= context.getConfig().getSteepMa20TrendThresholdPct()
                && macdStrengthPct < context.getConfig().getWeakEntryMacdStrengthPct()) {
            return "entry_blocked_by_steep_ma20_weak_macd";
        }
        return "";
    }

    /**
     * 计算MACD柱相对当前收盘价的归一化强度百分比。
     */
    public double calculateEntryMacdStrengthPct(LifecycleIndicatorSample sample) {
        if (sample == null || !Double.isFinite(sample.getClose()) || sample.getClose() <= 0.0d
                || !Double.isFinite(sample.getMacdBar())) {
            return Double.NaN;
        }
        return Math.abs(sample.getMacdBar()) / sample.getClose() * 100.0d;
    }

    /**
     * 计算当前收盘价相对5M MA20的方向性距离百分比。
     */
    public double calculateMa20EntryDistancePct(LifecycleContext context, LifecycleDirection direction) {
        if (context == null || context.current() == null || direction == null
                || direction == LifecycleDirection.NONE) {
            return Double.NaN;
        }
        LifecycleIndicatorSample current = context.current();
        double close = current.getClose();
        double ma20 = current.getMa20();
        if (!Double.isFinite(close) || close <= 0.0d || !Double.isFinite(ma20) || ma20 <= 0.0d) {
            return Double.NaN;
        }
        return direction == LifecycleDirection.LONG
                ? (close - ma20) / close * 100.0d
                : (ma20 - close) / close * 100.0d;
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
     * 判断普通确认入场是否在极端反向趋势中追入过深。
     */
    private boolean isExtremeCounterTrendConfirmationOverextended(LifecycleContext context,
                                                                    LifecycleDirection direction,
                                                                    double signalHigh,
                                                                    double signalLow) {
        if (context == null || context.getConfig() == null || context.current() == null
                || direction == null || direction == LifecycleDirection.NONE) {
            return false;
        }
        LifecycleConfig config = context.getConfig();
        LifecycleIndicatorSample cur = context.current();
        double ma20TrendPct = calculateMa20TrendPct(context);
        double threshold = config.getExtremeCounterTrendThresholdPct();
        boolean extremeCounterTrend = direction == LifecycleDirection.LONG
                ? ma20TrendPct <= -threshold && cur.getMa10() < cur.getMa20()
                : ma20TrendPct >= threshold && cur.getMa10() > cur.getMa20();
        if (!extremeCounterTrend) {
            return false;
        }
        double breakoutRatio = calculateBreakoutConfirmRatio(direction,
                signalHigh, signalLow, cur.getClose());
        return calculateBarRangePct(cur) >= config.getExtremeCounterTrendOverextendedBarRangePct()
                && Double.isFinite(breakoutRatio)
                && breakoutRatio >= config.getExtremeCounterTrendOverextendedBreakoutRatio();
    }

    /**
     * 判断极端逆势普通入场是否尚未完成MA20价格结构反转。
     */
    private boolean isExtremeCounterTrendStructureMissing(LifecycleContext context,
                                                           LifecycleDirection direction) {
        if (context == null || context.getConfig() == null || context.current() == null
                || direction == null || direction == LifecycleDirection.NONE) {
            return false;
        }
        LifecycleIndicatorSample cur = context.current();
        double ma20TrendPct = calculateMa20TrendPct(context);
        double threshold = context.getConfig().getExtremeCounterTrendThresholdPct();
        if (direction == LifecycleDirection.LONG) {
            return ma20TrendPct <= -threshold
                    && cur.getMa10() < cur.getMa20()
                    && cur.getClose() <= cur.getMa20();
        }
        return ma20TrendPct >= threshold
                && cur.getMa10() > cur.getMa20()
                && cur.getClose() >= cur.getMa20();
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
     * 计算MA20在配置窗口内的归一化趋势斜率百分比。
     */
    public double calculateMa20TrendPct(LifecycleContext context) {
        if (context == null || context.getConfig() == null || context.getSamples() == null
                || context.getSamples().isEmpty() || context.current().getClose() <= 0.0d) {
            return Double.NaN;
        }
        LifecycleIndicatorSample current = context.current();
        int targetIndex = current.getIndex() - context.getConfig().getMa20TrendLookbackBars();
        for (int i = context.getSamples().size() - 1; i >= 0; i--) {
            LifecycleIndicatorSample sample = context.getSamples().get(i);
            if (sample.getIndex() == targetIndex) {
                return (current.getMa20() - sample.getMa20()) / current.getClose() * 100.0d;
            }
        }
        return Double.NaN;
    }

    /**
     * 根据MA20趋势斜率识别明确方向，走平时返回NONE。
     */
    public LifecycleDirection detectMa20TrendDirection(LifecycleContext context) {
        double trendPct = calculateMa20TrendPct(context);
        if (Double.isNaN(trendPct) || context == null || context.getConfig() == null) {
            return LifecycleDirection.NONE;
        }
        if (trendPct >= context.getConfig().getMa20TrendThresholdPct()) {
            return LifecycleDirection.LONG;
        }
        if (trendPct <= -context.getConfig().getMa20TrendThresholdPct()) {
            return LifecycleDirection.SHORT;
        }
        return LifecycleDirection.NONE;
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
     * 判断持仓第5-7根是否仍未启动且MACD动能已经明显衰减。
     */
    public boolean isEarlyNonLaunchTrendFailure(LifecycleContext context) {
        if (!hasActivePositionContext(context)) {
            return false;
        }
        LifecycleState state = context.getState();
        LifecycleIndicatorSample cur = context.current();
        int holdBars = Math.max(0, cur.getIndex() - state.getEntryIndex());
        if (holdBars < context.getConfig().getEarlyNonLaunchCheckHoldBars()
                || holdBars > context.getConfig().getEarlyNonLaunchCheckMaxHoldBars()) {
            return false;
        }
        double maxFavorableProgressPct = calculateMaxFavorableProgressPct(context);
        double currentProgressPct = calculateCurrentProgressPct(state, cur);
        double macdRetentionRatio = calculateCurrentMacdRetentionRatio(state, cur);
        return Double.isFinite(maxFavorableProgressPct)
                && Double.isFinite(currentProgressPct)
                && Double.isFinite(macdRetentionRatio)
                && maxFavorableProgressPct < context.getConfig().getNonLaunchZeroProgressPct()
                && currentProgressPct <= 0.0d
                && macdRetentionRatio <= context.getConfig().getEarlyFailureMacdRetentionRatio();
    }

    /**
     * 判断前八根内已激活的趋势是否将浮盈全部回吐至成本线外。
     */
    public boolean isEarlyProfitRoundTrip(LifecycleContext context) {
        if (!hasActivePositionContext(context)) {
            return false;
        }
        LifecycleState state = context.getState();
        int holdBars = Math.max(0, context.current().getIndex() - state.getEntryIndex());
        if (holdBars < 1 || holdBars > context.getConfig().getEarlyProfitRoundTripMaxHoldBars()) {
            return false;
        }
        double maxFavorableProgressPct = calculateMaxFavorableProgressPct(context);
        double currentProgressPct = calculateCurrentProgressPct(state, context.current());
        return !Double.isNaN(maxFavorableProgressPct)
                && !Double.isNaN(currentProgressPct)
                && maxFavorableProgressPct >= context.getConfig().getEarlyProfitRoundTripActivationPct()
                && currentProgressPct <= 0.0d;
    }

    /**
     * 判断第九根后的小趋势是否已将有限浮盈基本回吐。
     */
    public boolean isWeakMatureProfitGiveback(LifecycleContext context) {
        if (!hasActivePositionContext(context)) {
            return false;
        }
        LifecycleConfig config = context.getConfig();
        LifecycleState state = context.getState();
        int holdBars = Math.max(0, context.current().getIndex() - state.getEntryIndex());
        if (holdBars < config.getMatureProfitGivebackMinHoldBars()) {
            return false;
        }
        double maxFavorableProgressPct = calculateMaxFavorableProgressPct(context);
        double currentProgressPct = calculateCurrentProgressPct(state, context.current());
        double macdRetentionRatio = calculateCurrentMacdRetentionRatio(state, context.current());
        return Double.isFinite(maxFavorableProgressPct)
                && Double.isFinite(currentProgressPct)
                && Double.isFinite(macdRetentionRatio)
                && maxFavorableProgressPct >= config.getWeakMatureProfitActivationPct()
                && maxFavorableProgressPct < config.getProfitGivebackActivationPct()
                && currentProgressPct <= config.getWeakMatureProfitRetainedPct()
                && macdRetentionRatio <= config.getMatureProfitMacdRetentionRatio();
    }

    /**
     * 判断第九根后的趋势是否在MACD衰减时仅剩少量浮盈。
     */
    public boolean isMatureProfitGiveback(LifecycleContext context) {
        if (!hasActivePositionContext(context)) {
            return false;
        }
        LifecycleState state = context.getState();
        int holdBars = Math.max(0, context.current().getIndex() - state.getEntryIndex());
        if (holdBars < context.getConfig().getMatureProfitGivebackMinHoldBars()) {
            return false;
        }
        double maxFavorableProgressPct = calculateMaxFavorableProgressPct(context);
        double currentProgressPct = calculateCurrentProgressPct(state, context.current());
        double macdRetentionRatio = calculateCurrentMacdRetentionRatio(state, context.current());
        return !Double.isNaN(maxFavorableProgressPct)
                && !Double.isNaN(currentProgressPct)
                && !Double.isNaN(macdRetentionRatio)
                && maxFavorableProgressPct >= context.getConfig().getProfitGivebackActivationPct()
                && currentProgressPct <= context.getConfig().getMatureProfitRetainedPct()
                && macdRetentionRatio <= context.getConfig().getMatureProfitMacdRetentionRatio();
    }

    /**
     * 计算当前持仓按收盘价计的方向性收益百分比。
     */
    public double calculateCurrentProgressPct(LifecycleState state, LifecycleIndicatorSample sample) {
        if (state == null || sample == null || Double.isNaN(state.getEntryPrice())
                || state.getEntryPrice() <= 0.0d || (!state.inLong() && !state.inShort())) {
            return Double.NaN;
        }
        return state.inLong()
                ? (sample.getClose() - state.getEntryPrice()) / state.getEntryPrice() * 100.0d
                : (state.getEntryPrice() - sample.getClose()) / state.getEntryPrice() * 100.0d;
    }

    /**
     * 判断上下文是否包含可计算收益的活动仓位。
     */
    private boolean hasActivePositionContext(LifecycleContext context) {
        return context != null
                && context.getState() != null
                && context.getConfig() != null
                && context.getSamples() != null
                && !context.getSamples().isEmpty()
                && context.getState().getEntryIndex() >= 0
                && !Double.isNaN(context.getState().getEntryPrice())
                && context.getState().getEntryPrice() > 0.0d
                && (context.getState().inLong() || context.getState().inShort());
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
