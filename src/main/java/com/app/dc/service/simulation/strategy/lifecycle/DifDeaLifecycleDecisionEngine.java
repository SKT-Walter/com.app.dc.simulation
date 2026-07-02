package com.app.dc.service.simulation.strategy.lifecycle;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DIF/DEA鐡掑濞嶉悽鐔锋嚒閸涖劍婀￠惃鍕壋韫囧啫鍠呯粵鏍х穿閹垮簺鈧? */
@Service
public class DifDeaLifecycleDecisionEngine {

    public static final String STRATEGY_NAME = "difDeaLifecycle";
    private static final int LAUNCH_CONFIRM_COMPARISONS = 2;
    private static final int ENTRY_COUNTER_TREND_WINDOW = 4;
    private static final int LOW_MACD_ENTRY_WINDOW = 5;
    private static final int LOW_MACD_ENTRY_MIN_HITS = 4;
    private static final double LOW_MACD_ENTRY_THRESHOLD = 0.5d;
    private static final int PENDING_RANGE_BUILD_BARS = 4;
    private static final int EXIT_BODY_BREAK_WINDOW = 3;
    private static final double LARGE_BAR_RANGE_THRESHOLD_PCT = 1.5d;
    private static final double LAUNCH_BAR_RANGE_THRESHOLD_PCT = 0.8d;
    private static final double SURGE_CROSS_PREV_BAR_RANGE_THRESHOLD_PCT = 0.7d;
    private static final int SUDDEN_MACD_EXPANSION_WINDOW = 4;
    private static final double SUDDEN_MACD_EXPANSION_RATIO = 1.8d;
    private static final double PENDING_BREAKOUT_TOLERANCE = 0.1d;
    private static final double MACD_CONFIRM_TOLERANCE = 0.15d;
    private static final double WEAK_CROSS_MACD_THRESHOLD = 0.12d;
    private static final double WEAK_CROSS_GAP_THRESHOLD = 0.12d;
    private static final int LOW_MACD_PENDING_MAX_BARS = 10;
    private static final int MA_TREND_CONFIRM_WINDOW = 3;
    private final ThreadLocal<String> decisionTraceHolder = new ThreadLocal<String>();

    /**
     * 閺嶈宓佽ぐ鎾冲閹稿洦鐖ｉ弽閿嬫拱閵嗕線鍘ょ純顔兼嫲閻樿埖鈧浇绶崙铏规晸閸涜棄鎳嗛張鐔峰З娴ｆ嚎鈧?     */
    public LifecycleDecision decide(LifecycleContext context) {
        decisionTraceHolder.remove();
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
        boolean longMacdWeakening = macdDecreasing(context.getSamples(), weaknessWindow, weaknessMinCount);
        boolean longCloseWeakening = closeDecreasing(context.getSamples(), weaknessWindow, weaknessMinCount);
        boolean longLowWeakening = lowDecreasing(context.getSamples(), weaknessWindow, weaknessMinCount);
        boolean longExitBodyBreakout = isLongExitBodyBreakout(context.getSamples());
        boolean leaveLongByWeakness = longMacdWeakening && longCloseWeakening && longLowWeakening
                && longExitBodyBreakout;
        boolean shortMacdRecovering = macdIncreasing(context.getSamples(), weaknessWindow, weaknessMinCount);
        boolean shortCloseStrengthening = closeIncreasing(context.getSamples(), weaknessWindow, weaknessMinCount);
        boolean shortLowStrengthening = lowIncreasing(context.getSamples(), weaknessWindow, weaknessMinCount);
        boolean shortExitBodyBreakout = isShortExitBodyBreakout(context.getSamples());
        boolean leaveShortByWeakness = shortMacdRecovering && shortCloseStrengthening && shortLowStrengthening
                && shortExitBodyBreakout;

        if (state.inLong()) {
            if (leaveLongByCross && !reverseCrossBlocked) {
                setPositionDecisionTrace("longActive", context, cur,
                        leaveLongByCross, reverseCrossBlocked,
                        leaveLongByWeakness, longMacdWeakening, longCloseWeakening, longLowWeakening,
                        longExitBodyBreakout, getRecentMaxBodyTop(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        getRecentMinBodyBottom(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        "cross_exit");
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG, "dif_dea_cross_down",
                        !isShortEntryBlockedBySurgeCross(context.getSamples()));
            }
            if (leaveLongByCross) {
                setPositionDecisionTrace("longActive", context, cur,
                        leaveLongByCross, reverseCrossBlocked,
                        leaveLongByWeakness, longMacdWeakening, longCloseWeakening, longLowWeakening,
                        longExitBodyBreakout, getRecentMaxBodyTop(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        getRecentMinBodyBottom(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        "reverse_cross_blocked_by_dif_dea_bonding");
                return LifecycleDecision.none("reverse_cross_blocked_by_dif_dea_bonding");
            }
            if (leaveLongByWeakness) {
                setPositionDecisionTrace("longActive", context, cur,
                        leaveLongByCross, reverseCrossBlocked,
                        leaveLongByWeakness, longMacdWeakening, longCloseWeakening, longLowWeakening,
                        longExitBodyBreakout, getRecentMaxBodyTop(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        getRecentMinBodyBottom(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        "weakness_exit");
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_LONG, "macd_shrinking_and_close_weakening");
            }
            setPositionDecisionTrace("longActive", context, cur,
                    leaveLongByCross, reverseCrossBlocked,
                    leaveLongByWeakness, longMacdWeakening, longCloseWeakening, longLowWeakening,
                    longExitBodyBreakout, getRecentMaxBodyTop(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                    getRecentMinBodyBottom(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                    "hold_long");
            return LifecycleDecision.none("long_active_no_exit");
        }

        if (state.inShort()) {
            if (leaveShortByCross && !reverseCrossBlocked) {
                setPositionDecisionTrace("shortActive", context, cur,
                        leaveShortByCross, reverseCrossBlocked,
                        leaveShortByWeakness, shortMacdRecovering, shortCloseStrengthening, shortLowStrengthening,
                        shortExitBodyBreakout, getRecentMaxBodyTop(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        getRecentMinBodyBottom(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        "cross_exit");
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT, "dif_dea_cross_up",
                        !isLongEntryBlockedBySurgeCross(context.getSamples()));
            }
            if (leaveShortByCross) {
                setPositionDecisionTrace("shortActive", context, cur,
                        leaveShortByCross, reverseCrossBlocked,
                        leaveShortByWeakness, shortMacdRecovering, shortCloseStrengthening, shortLowStrengthening,
                        shortExitBodyBreakout, getRecentMaxBodyTop(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        getRecentMinBodyBottom(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        "reverse_cross_blocked_by_dif_dea_bonding");
                return LifecycleDecision.none("reverse_cross_blocked_by_dif_dea_bonding");
            }
            if (leaveShortByWeakness) {
                setPositionDecisionTrace("shortActive", context, cur,
                        leaveShortByCross, reverseCrossBlocked,
                        leaveShortByWeakness, shortMacdRecovering, shortCloseStrengthening, shortLowStrengthening,
                        shortExitBodyBreakout, getRecentMaxBodyTop(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        getRecentMinBodyBottom(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                        "weakness_exit");
                return LifecycleDecision.of(LifecycleDecisionType.LEAVE_SHORT, "macd_recovering_and_close_strengthening");
            }
            setPositionDecisionTrace("shortActive", context, cur,
                    leaveShortByCross, reverseCrossBlocked,
                    leaveShortByWeakness, shortMacdRecovering, shortCloseStrengthening, shortLowStrengthening,
                    shortExitBodyBreakout, getRecentMaxBodyTop(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                    getRecentMinBodyBottom(context.getSamples(), EXIT_BODY_BREAK_WINDOW),
                    "hold_short");
            return LifecycleDecision.none("short_active_no_exit");
        }

        if (state.inPendingLong()) {
            return decidePendingLong(context, enterShortRaw, bondingSegment);
        }
        if (state.inPendingShort()) {
            return decidePendingShort(context, enterLongRaw, bondingSegment);
        }

        if (!context.getConfig().validForEntry()) {
            return LifecycleDecision.none("missing_or_invalid_symbol_config");
        }
        if (enterLongRaw) {
            return evaluateLongCycle(context, bondingSegment, false);
        }
        if (enterShortRaw) {
            return evaluateShortCycle(context, bondingSegment, false);
        }
        return LifecycleDecision.none("no_cross");
    }

    /**
     * 閼惧嘲褰囬張鈧潻鎴滅濞嗏€冲枀缁涙牜鏁撻幋鎰畱鐠虹喕閲滄穱鈩冧紖閵?     */
    public String getLastDecisionTrace() {
        return decisionTraceHolder.get();
    }

    /**
     * 返回低MACD压缩观察态允许等待的最大K线数。
     */
    public int getLowMacdPendingMaxBars() {
        return LOW_MACD_PENDING_MAX_BARS;
    }

    /**
     * 返回 pending 固定建区窗口需要的 K 线根数。
     */
    public int getPendingRangeBuildBars() {
        return PENDING_RANGE_BUILD_BARS;
    }

    /**
     * 婢跺嫮鎮婃径姘仈娴ｅ懂ACD鐟欏倸鐧傞崥搴ｆ畱鐞涖儱绱戞禒鎾烩偓鏄忕帆閵?     */
    private LifecycleDecision decidePendingLong(LifecycleContext context, boolean newShortCycle, boolean bondingSegment) {
        LifecycleIndicatorSample current = context.current();
        if (newShortCycle) {
            setDecisionTrace("pendingLong", context, current, false, false, false,
                    false, false, false, false, false,
                    "new_short_cycle");
            return evaluateShortCycle(context, bondingSegment, true);
        }

        if (current.getDif() < current.getDea()) {
            setDecisionTrace("pendingLong", context, current, false, false, false,
                    false, false, false, false, false,
                    "reverse_cross_invalidated");
            return LifecycleDecision.none("pending_launch_invalidated_by_reverse_cross");
        }

        boolean counterTrendBlocked = isLongEntryBlockedByCounterTrend(context.getSamples());
        if (counterTrendBlocked) {
            setDecisionTrace("pendingLong", context, current, counterTrendBlocked, false, false,
                    false, false, false, false, false,
                    "blocked_by_counter_trend");
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, "pending_launch_tracking_by_counter_trend");
        }
        boolean lowMacdCompressionBlocked = false;

        boolean launchReady = isLongLaunchReady(context);
        boolean pendingRangeReady = context.getState().isPendingRangeReady();
        boolean pendingRangeBreakout = pendingRangeReady
                && isLongPendingRangeBreakout(current, context.getState());
        boolean ma10Up2 = isLaunchMa10Up(context.getSamples());
        boolean macdAbsExpanding = macdAbsExpanding(context.getSamples());
        boolean strictMacdConfirm = macdIncreasing(context.getSamples(), LAUNCH_CONFIRM_COMPARISONS, LAUNCH_CONFIRM_COMPARISONS);
        boolean toleranceMacdConfirm = hasMacdToleranceConfirmation(context.getSamples(), true);
        boolean priceConfirm = hasLongLaunchPriceConfirmation(context.getSamples(), current);
        boolean barRangeBlocked = false;

        if (launchReady && pendingRangeBreakout) {
            if (!context.getConfig().isLaunchEntryAfterMacdExpandEnabled()) {
                setDecisionTrace("pendingLong", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                        launchReady, pendingRangeReady, pendingRangeBreakout,
                        macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                        "launch_entry_disabled_by_config, priceConfirm=" + priceConfirm);
                return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, "launch_entry_disabled_by_config");
            }
            if (!ma10Up2) {
                setDecisionTrace("pendingLong", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                        launchReady, pendingRangeReady, pendingRangeBreakout,
                        macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                        "launch_blocked_by_ma10_trend, priceConfirm=" + priceConfirm);
                return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, "launch_blocked_by_ma10_trend");
            }
            barRangeBlocked = isLaunchBarRangeBlocked(current);
            if (barRangeBlocked) {
                setDecisionTrace("pendingLong", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                        launchReady, pendingRangeReady, pendingRangeBreakout,
                        macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                        "launch_blocked_by_large_bar_range, priceConfirm=" + priceConfirm);
                return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, "launch_blocked_by_large_bar_range");
            }
            setDecisionTrace("pendingLong", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                    launchReady, pendingRangeReady, pendingRangeBreakout,
                    macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                    "launch_ready, priceConfirm=" + priceConfirm + ", barRangeBlocked=" + barRangeBlocked);
            return LifecycleDecision.of(LifecycleDecisionType.ENTER_LONG, "launch_entry_after_macd_expand");
        }
        setDecisionTrace("pendingLong", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                launchReady, pendingRangeReady, pendingRangeBreakout,
                macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                "pending_tracking, priceConfirm=" + priceConfirm + ", barRangeBlocked=" + barRangeBlocked);
        return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, "pending_launch_tracking");
    }

    /**
     * 婢跺嫮鎮婄粚鍝勩仈娴ｅ懂ACD鐟欏倸鐧傞崥搴ｆ畱鐞涖儱绱戞禒鎾烩偓鏄忕帆閵?     */
    private LifecycleDecision decidePendingShort(LifecycleContext context, boolean newLongCycle, boolean bondingSegment) {
        LifecycleIndicatorSample current = context.current();
        if (newLongCycle) {
            setDecisionTrace("pendingShort", context, current, false, false, false,
                    false, false, false, false, false,
                    "new_long_cycle");
            return evaluateLongCycle(context, bondingSegment, true);
        }

        if (current.getDif() > current.getDea()) {
            setDecisionTrace("pendingShort", context, current, false, false, false,
                    false, false, false, false, false,
                    "reverse_cross_invalidated");
            return LifecycleDecision.none("pending_launch_invalidated_by_reverse_cross");
        }

        boolean counterTrendBlocked = isShortEntryBlockedByCounterTrend(context.getSamples());
        boolean lowMacdCompressionBlocked = false;

        boolean launchReady = isShortLaunchReady(context);
        boolean pendingRangeReady = context.getState().isPendingRangeReady();
        boolean pendingRangeBreakout = pendingRangeReady
                && isShortPendingRangeBreakout(current, context.getState());
        boolean ma10Down2 = isLaunchMa10Down(context.getSamples());
        boolean macdAbsExpanding = macdAbsExpanding(context.getSamples());
        boolean strictMacdConfirm = macdDecreasing(context.getSamples(), LAUNCH_CONFIRM_COMPARISONS, LAUNCH_CONFIRM_COMPARISONS);
        boolean toleranceMacdConfirm = hasMacdToleranceConfirmation(context.getSamples(), false);
        boolean priceConfirm = hasShortLaunchPriceConfirmation(context.getSamples(), current);
        boolean barRangeBlocked = false;

        if (launchReady && pendingRangeBreakout) {
            if (!context.getConfig().isLaunchEntryAfterMacdExpandEnabled()) {
                setDecisionTrace("pendingShort", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                        launchReady, pendingRangeReady, pendingRangeBreakout,
                        macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                        "launch_entry_disabled_by_config, priceConfirm=" + priceConfirm);
                return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, "launch_entry_disabled_by_config");
            }
            if (!ma10Down2) {
                setDecisionTrace("pendingShort", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                        launchReady, pendingRangeReady, pendingRangeBreakout,
                        macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                        "launch_blocked_by_ma10_trend, priceConfirm=" + priceConfirm);
                return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, "launch_blocked_by_ma10_trend");
            }
            barRangeBlocked = isLaunchBarRangeBlocked(current);
            if (barRangeBlocked) {
                setDecisionTrace("pendingShort", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                        launchReady, pendingRangeReady, pendingRangeBreakout,
                        macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                        "launch_blocked_by_large_bar_range, priceConfirm=" + priceConfirm);
                return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, "launch_blocked_by_large_bar_range");
            }
            setDecisionTrace("pendingShort", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                    launchReady, pendingRangeReady, pendingRangeBreakout,
                    macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                    "launch_ready, priceConfirm=" + priceConfirm + ", barRangeBlocked=" + barRangeBlocked);
            return LifecycleDecision.of(LifecycleDecisionType.ENTER_SHORT, "launch_entry_after_macd_expand");
        }
        setDecisionTrace("pendingShort", context, current, counterTrendBlocked, lowMacdCompressionBlocked,
                launchReady, pendingRangeReady, pendingRangeBreakout,
                macdAbsExpanding, strictMacdConfirm, toleranceMacdConfirm,
                "pending_tracking, priceConfirm=" + priceConfirm + ", barRangeBlocked=" + barRangeBlocked);
        return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, "pending_launch_tracking");
    }

    /**
     * 鐠佹澘缍?pending 閸愬磭鐡ラ柧鎹愮熅閻ㄥ嫬鍙ч柨顔肩鐏忔柨鈧》绱濋弬閫涚┒闁?K 閹烘帗鐓￠妴?     */
    private void setDecisionTrace(String stage,
                                  LifecycleContext context,
                                  LifecycleIndicatorSample current,
                                  boolean counterTrendBlocked,
                                  boolean lowMacdCompressionBlocked,
                                  boolean launchReady,
                                  boolean pendingRangeReady,
                                  boolean pendingRangeBreakout,
                                  boolean macdAbsExpanding,
                                  boolean strictMacdConfirm,
                                  boolean toleranceMacdConfirm,
                                  String tail) {
        StringBuilder builder = new StringBuilder();
        builder.append("stage=").append(stage)
                .append(", counterTrendBlocked=").append(counterTrendBlocked)
                .append(", lowMacdCompressionBlocked=").append(lowMacdCompressionBlocked)
                .append(", launchReady=").append(launchReady)
                .append(", pendingRangeReady=").append(pendingRangeReady)
                .append(", pendingRangeBreakout=").append(pendingRangeBreakout)
                .append(", macdAbsExpanding=").append(macdAbsExpanding)
                .append(", strictMacdConfirm=").append(strictMacdConfirm)
                .append(", toleranceMacdConfirm=").append(toleranceMacdConfirm);
        if (current != null) {
            builder.append(", currentOpen=").append(current.getOpen())
                    .append(", currentHigh=").append(current.getHigh())
                    .append(", currentLow=").append(current.getLow())
                    .append(", currentClose=").append(current.getClose())
                    .append(", currentDif=").append(current.getDif())
                    .append(", currentDea=").append(current.getDea())
                    .append(", currentMacd=").append(current.getMacdBar())
                    .append(", currentMa10=").append(current.getMa10())
                    .append(", barRangePct=").append(calculateBarRangePct(current));
        }
        appendPreviousSampleTrace(builder, context);
        builder.append(", ma10Up2=").append(isLaunchMa10Up(context == null ? null : context.getSamples()))
                .append(", ma10Down2=").append(isLaunchMa10Down(context == null ? null : context.getSamples()));
        if (context != null && context.getState() != null) {
            builder.append(", pendingBars=").append(context.getState().getPendingBars())
                    .append(", pendingBuildBars=").append(PENDING_RANGE_BUILD_BARS)
                    .append(", pendingRangeReady=").append(context.getState().isPendingRangeReady())
                    .append(", pendingSource=").append(context.getState().getPendingSource())
                    .append(", pendingHighClose=").append(context.getState().getPendingHighClose())
                    .append(", pendingLowClose=").append(context.getState().getPendingLowClose())
                    .append(", pendingUpperBodyBound=").append(context.getState().getPendingUpperBodyBound())
                    .append(", pendingLowerBodyBound=").append(context.getState().getPendingLowerBodyBound());
        }
        if (tail != null) {
            builder.append(", trace=").append(tail);
        }
        decisionTraceHolder.set(builder.toString());
    }

    /**
     * 鐠佹澘缍嶅鍙樻唉閸欏绻冨銈囨畱閸忔娊鏁弫鏉库偓纭风礉娓氬じ绨崶鐐寸ゴ閺冦儱绻旈惄瀛樺复閸掋倖鏌囬弰顖氭儊閸ョ姾鍒涙潻?鏉炵顫﹂幏锔藉焻閵?     */
    private void setWeakCrossTrace(String stage,
                                   LifecycleContext context,
                                   LifecycleIndicatorSample current,
                                   boolean replacingPending,
                                   String tail) {
        StringBuilder builder = new StringBuilder();
        double macdAbs = current == null ? Double.NaN : Math.abs(current.getMacdBar());
        double difDeaGapAbs = current == null ? Double.NaN : Math.abs(current.getDif() - current.getDea());
        builder.append("stage=").append(stage)
                .append(", replacingPending=").append(replacingPending)
                .append(", weakCross=").append(isWeakCross(current))
                .append(", weakCrossByMacd=").append(isWeakCrossByMacd(current))
                .append(", weakCrossByGap=").append(isWeakCrossByGap(current))
                .append(", macdAbs=").append(macdAbs)
                .append(", difDeaGapAbs=").append(difDeaGapAbs)
                .append(", weakCrossMacdThreshold=").append(WEAK_CROSS_MACD_THRESHOLD)
                .append(", weakCrossGapThreshold=").append(WEAK_CROSS_GAP_THRESHOLD);
        if (current != null) {
            builder.append(", currentOpen=").append(current.getOpen())
                    .append(", currentHigh=").append(current.getHigh())
                    .append(", currentLow=").append(current.getLow())
                    .append(", currentClose=").append(current.getClose())
                    .append(", currentDif=").append(current.getDif())
                    .append(", currentDea=").append(current.getDea())
                    .append(", currentMacd=").append(current.getMacdBar());
        }
        appendPreviousSampleTrace(builder, context);
        if (tail != null) {
            builder.append(", trace=").append(tail);
        }
        decisionTraceHolder.set(builder.toString());
    }

    /**
     * 鐠佹澘缍嶉幐浣风波閹胶顬囬崷鍝勫灲閺傤厾娈戦崗鎶芥暛鐢啫鐨甸崐纭风礉閺傞€涚┒鐎规矮缍呴弰顖氭儊鐞氼偆鐡ラ悾銉ь瀲閸﹂缚绻冮弮鈺勑曢崣鎴欌偓?     */
    private void setPositionDecisionTrace(String stage,
                                          LifecycleContext context,
                                          LifecycleIndicatorSample current,
                                          boolean leaveByCross,
                                          boolean reverseCrossBlocked,
                                          boolean leaveByWeakness,
                                          boolean macdCondition,
                                          boolean closeCondition,
                                          boolean priceStructureCondition,
                                          boolean exitBodyBreakout,
                                          double recentMaxBodyTop,
                                          double recentMinBodyBottom,
                                          String tail) {
        StringBuilder builder = new StringBuilder();
        builder.append("stage=").append(stage)
                .append(", leaveByCross=").append(leaveByCross)
                .append(", reverseCrossBlocked=").append(reverseCrossBlocked)
                .append(", leaveByWeakness=").append(leaveByWeakness)
                .append(", macdCondition=").append(macdCondition)
                .append(", closeCondition=").append(closeCondition)
                .append(", priceStructureCondition=").append(priceStructureCondition)
                .append(", exitBodyBreakout=").append(exitBodyBreakout)
                .append(", recentMaxBodyTop=").append(recentMaxBodyTop)
                .append(", recentMinBodyBottom=").append(recentMinBodyBottom);
        if (current != null) {
            builder.append(", currentOpen=").append(current.getOpen())
                    .append(", currentHigh=").append(current.getHigh())
                    .append(", currentLow=").append(current.getLow())
                    .append(", currentClose=").append(current.getClose())
                    .append(", currentDif=").append(current.getDif())
                    .append(", currentDea=").append(current.getDea())
                    .append(", currentMacd=").append(current.getMacdBar())
                    .append(", barRangePct=").append(calculateBarRangePct(current));
        }
        appendPreviousSampleTrace(builder, context);
        if (tail != null) {
            builder.append(", trace=").append(tail);
        }
        decisionTraceHolder.set(builder.toString());
    }

    /**
     * 閸掋倖鏌囨径姘仈缁涙牜鏆愮粋璇叉簚閺冭绱濊ぐ鎾冲close閺勵垰鎯佺捄宀€鐗崜?閺嶈娓舵担宸唋ose閵?     */
    private boolean isLongExitBodyBreakout(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() <= EXIT_BODY_BREAK_WINDOW) {
            return false;
        }
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        double recentMinBodyBottom = getRecentMinBodyBottom(samples, EXIT_BODY_BREAK_WINDOW);
        return current != null && !Double.isNaN(recentMinBodyBottom) && current.getClose() < recentMinBodyBottom;
    }

    /**
     * 閸掋倖鏌囩粚鍝勩仈缁涙牜鏆愮粋璇叉簚閺冭绱濊ぐ鎾冲close閺勵垰鎯佺粣浣虹壃閸?閺嶈娓舵姒條ose閵?     */
    private boolean isShortExitBodyBreakout(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() <= EXIT_BODY_BREAK_WINDOW) {
            return false;
        }
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        double recentMaxBodyTop = getRecentMaxBodyTop(samples, EXIT_BODY_BREAK_WINDOW);
        return current != null && !Double.isNaN(recentMaxBodyTop) && current.getClose() > recentMaxBodyTop;
    }

    /**
     * 鐠囪褰囪ぐ鎾冲bar娑斿澧犻張鈧潻鎱涢弽绛﹍ose閻ㄥ嫭娓舵妯衡偓绗衡偓?     */
    private double getRecentMaxBodyTop(List<LifecycleIndicatorSample> samples, int window) {
        if (samples == null || samples.size() <= window || window <= 0) {
            return Double.NaN;
        }
        int end = samples.size() - 1;
        int start = end - window;
        double maxBodyTop = Double.NEGATIVE_INFINITY;
        for (int i = start; i < end; i++) {
            LifecycleIndicatorSample sample = samples.get(i);
            maxBodyTop = Math.max(maxBodyTop, Math.max(sample.getOpen(), sample.getClose()));
        }
        return maxBodyTop == Double.NEGATIVE_INFINITY ? Double.NaN : maxBodyTop;
    }

    /**
     * 鐠囪褰囪ぐ鎾冲bar娑斿澧犻張鈧潻鎱涢弽绛﹍ose閻ㄥ嫭娓舵担搴♀偓绗衡偓?     */
    private double getRecentMinBodyBottom(List<LifecycleIndicatorSample> samples, int window) {
        if (samples == null || samples.size() <= window || window <= 0) {
            return Double.NaN;
        }
        int end = samples.size() - 1;
        int start = end - window;
        double minBodyBottom = Double.POSITIVE_INFINITY;
        for (int i = start; i < end; i++) {
            LifecycleIndicatorSample sample = samples.get(i);
            minBodyBottom = Math.min(minBodyBottom, Math.min(sample.getOpen(), sample.getClose()));
        }
        return minBodyBottom == Double.POSITIVE_INFINITY ? Double.NaN : minBodyBottom;
    }

    /**
     * 鏉╄棄濮為崜宥勮⒈閺嶈鐗遍張顒傛畱閸忔娊鏁穱鈩冧紖閿涘奔绌舵禍搴㈢壋鐎?MACD 鐎圭懓妯婄涵顔款吇閵?     */
    private void appendPreviousSampleTrace(StringBuilder builder, LifecycleContext context) {
        if (builder == null || context == null || context.getSamples() == null || context.getSamples().isEmpty()) {
            return;
        }
        List<LifecycleIndicatorSample> samples = context.getSamples();
        LifecycleIndicatorSample previous = samples.size() >= 2 ? samples.get(samples.size() - 2) : null;
        LifecycleIndicatorSample older = samples.size() >= 3 ? samples.get(samples.size() - 3) : null;
        if (previous != null) {
            builder.append(", prevClose=").append(previous.getClose())
                    .append(", prevMacd=").append(previous.getMacdBar())
                    .append(", prevDif=").append(previous.getDif())
                    .append(", prevDea=").append(previous.getDea())
                    .append(", prevMa10=").append(previous.getMa10());
        }
        if (older != null) {
            builder.append(", olderClose=").append(older.getClose())
                    .append(", olderMacd=").append(older.getMacdBar())
                    .append(", prev2Ma10=").append(older.getMa10());
        }
    }

    /**
     * 鐠囧嫪鍙婃稉鈧潪顔芥煀閻ㄥ嫬顦挎径缈犱繆閸欏嘲鎳嗛張鐔粹偓?     */
    private LifecycleDecision evaluateLongCycle(LifecycleContext context, boolean bondingSegment, boolean replacingPending) {
        LifecycleIndicatorSample current = context.current();
        if (isCrossDensityBlocked(context.current(), context.getConfig())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_long_cycle_blocked_by_cross_density"
                    : "entry_blocked_by_cross_density");
        }
        if (isLargeBarRange(current)) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, "entry_blocked_by_large_bar_range");
        }
        if (!replacingPending && isLongEntryBlockedBySurgeCross(context.getSamples())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, "entry_blocked_by_surge_cross");
        }
        if (isLongEntryBlockedBySuddenMacdExpansion(context.getSamples())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_long_cycle"
                    : "entry_blocked_by_sudden_macd_expansion");
        }
        if (isLongEntryBlockedByCounterTrend(context.getSamples())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_long_cycle"
                    : "entry_blocked_by_counter_trend");
        }
        if (isLongEntryBlockedByMaCounterTrend(context.getSamples())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_long_cycle"
                    : "entry_blocked_by_ma_counter_trend");
        }
        if (isLowMacdEntryBlocked(context.getSamples(), context.getConfig())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_long_cycle"
                    : "entry_blocked_by_low_macd");
        }
        if (bondingSegment) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_long_cycle"
                    : "entry_blocked_by_dif_dea_bonding");
        }
        if (isWeakCross(current)) {
            setWeakCrossTrace("longCycle", context, current, replacingPending, "blocked_by_weak_cross");
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_LONG_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_long_cycle"
                    : "entry_blocked_by_weak_cross");
        }
        return LifecycleDecision.of(LifecycleDecisionType.ENTER_LONG, "dif_dea_cross_up");
    }

    /**
     * 鐠囧嫪鍙婃稉鈧潪顔芥煀閻ㄥ嫮鈹栨径缈犱繆閸欏嘲鎳嗛張鐔粹偓?     */
    private LifecycleDecision evaluateShortCycle(LifecycleContext context, boolean bondingSegment, boolean replacingPending) {
        LifecycleIndicatorSample current = context.current();
        if (isCrossDensityBlocked(context.current(), context.getConfig())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_short_cycle_blocked_by_cross_density"
                    : "entry_blocked_by_cross_density");
        }
        if (isLargeBarRange(current)) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, "entry_blocked_by_large_bar_range");
        }
        if (!replacingPending && isShortEntryBlockedBySurgeCross(context.getSamples())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, "entry_blocked_by_surge_cross");
        }
        if (isShortEntryBlockedBySuddenMacdExpansion(context.getSamples())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_short_cycle"
                    : "entry_blocked_by_sudden_macd_expansion");
        }
        if (isShortEntryBlockedByCounterTrend(context.getSamples())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_short_cycle"
                    : "entry_blocked_by_counter_trend");
        }
        if (isShortEntryBlockedByMaCounterTrend(context.getSamples())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_short_cycle"
                    : "entry_blocked_by_ma_counter_trend");
        }
        if (isLowMacdEntryBlocked(context.getSamples(), context.getConfig())) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_short_cycle"
                    : "entry_blocked_by_low_macd");
        }
        if (bondingSegment) {
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_short_cycle"
                    : "entry_blocked_by_dif_dea_bonding");
        }
        if (isWeakCross(current)) {
            setWeakCrossTrace("shortCycle", context, current, replacingPending, "blocked_by_weak_cross");
            return LifecycleDecision.of(LifecycleDecisionType.PENDING_SHORT_LAUNCH, replacingPending
                    ? "pending_launch_replaced_by_new_short_cycle"
                    : "entry_blocked_by_weak_cross");
        }
        return LifecycleDecision.of(LifecycleDecisionType.ENTER_SHORT, "dif_dea_cross_down");
    }

    /**
     * 閸掋倖鏌囨径姘仈娴溿倕寮跺鈧禒鎾存Ц閸氾箒顫﹂張鈧潻鎱樼痪鍨冀閸氭垵鍣ｇ挧鏉垮◢鏉╁洦鎶ら妴?     */
    private boolean isLongEntryBlockedByCounterTrend(List<LifecycleIndicatorSample> samples) {
        return isCounterTrend(samples, true);
    }

    /**
     * 閸掋倖鏌囩粚鍝勩仈娴溿倕寮跺鈧禒鎾存Ц閸氾箒顫﹂張鈧潻鎱樼痪鍨冀閸氭垵鍣ｇ挧鏉垮◢鏉╁洦鎶ら妴?     */
    private boolean isShortEntryBlockedByCounterTrend(List<LifecycleIndicatorSample> samples) {
        return isCounterTrend(samples, false);
    }

    /**
     * 判断金叉开多时是否仍处于 MA5 反弹、MA10 未翻多的高一级空头结构中。
     */
    boolean isLongEntryBlockedByMaCounterTrend(List<LifecycleIndicatorSample> samples) {
        return isMaCounterTrend(samples, true);
    }

    /**
     * 判断死叉开空时是否仍处于 MA5 回踩、MA10 未翻空的高一级多头结构中。
     */
    boolean isShortEntryBlockedByMaCounterTrend(List<LifecycleIndicatorSample> samples) {
        return isMaCounterTrend(samples, false);
    }

    /**
     * 閸掋倖鏌囬柌鎴濆级閺勵垰鎯佺仦鐐扮艾婢堆冪畽閹峰宕岄崥搴ｂ€栭幏钘夊毉閺夈儳娈戠粣浣稿絺娴溿倕寮堕敍宀勪缉閸忓秶鈹栨禒鎾规嫹閸︺劍鈧儲濯洪張顐ゎ伂閵?     */
    private boolean isLongEntryBlockedBySurgeCross(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 5) {
            return false;
        }
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        LifecycleIndicatorSample prev1 = samples.get(samples.size() - 2);
        LifecycleIndicatorSample prev2 = samples.get(samples.size() - 3);
        LifecycleIndicatorSample prev3 = samples.get(samples.size() - 4);
        LifecycleIndicatorSample prev4 = samples.get(samples.size() - 5);
        return current.getMacdBar() > 0.0
                && prev1.getMacdBar() < 0.0
                && prev2.getMacdBar() < 0.0
                && prev3.getMacdBar() > prev2.getMacdBar()
                && prev4.getMacdBar() > prev3.getMacdBar()
                && calculateBarRangePct(prev1) > SURGE_CROSS_PREV_BAR_RANGE_THRESHOLD_PCT;
    }

    /**
     * 閸掋倖鏌囧璇插级閺勵垰鎯佺仦鐐扮艾婢堆冪畽娑撳娼冮崥搴ｂ€栭幏钘夊毉閺夈儳娈戠粣浣稿絺娴溿倕寮堕敍宀勪缉閸忓秶鈹栨禒鎾规嫹閸︺劍鈧儴绌奸張顐ゎ伂閵?     */
    private boolean isShortEntryBlockedBySurgeCross(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 5) {
            return false;
        }
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        LifecycleIndicatorSample prev1 = samples.get(samples.size() - 2);
        LifecycleIndicatorSample prev2 = samples.get(samples.size() - 3);
        LifecycleIndicatorSample prev3 = samples.get(samples.size() - 4);
        LifecycleIndicatorSample prev4 = samples.get(samples.size() - 5);
        return current.getMacdBar() < 0.0
                && prev1.getMacdBar() > 0.0
                && prev2.getMacdBar() > 0.0
                && prev3.getMacdBar() < prev2.getMacdBar()
                && prev4.getMacdBar() < prev3.getMacdBar()
                && calculateBarRangePct(prev1) > SURGE_CROSS_PREV_BAR_RANGE_THRESHOLD_PCT;
    }

    /**
     * 判断多头交叉是否属于近4根 MACD 末端突然拉伸的假启动。
     */
    private boolean isLongEntryBlockedBySuddenMacdExpansion(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 4) {
            return false;
        }

        LifecycleIndicatorSample prev3 = samples.get(samples.size() - 4);
        LifecycleIndicatorSample prev2 = samples.get(samples.size() - 3);
        LifecycleIndicatorSample prev1 = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);

        return prev3.getMacdBar() > prev2.getMacdBar()
                && prev2.getMacdBar() < prev1.getMacdBar()
                && prev1.getMacdBar() < current.getMacdBar()
                && current.getMacdBar() > 0;
    }

    /**
     * 判断空头交叉是否属于近4根 MACD 末端突然下杀的假启动。
     */
    private boolean isShortEntryBlockedBySuddenMacdExpansion(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 4) {
            return false;
        }

        LifecycleIndicatorSample prev3 = samples.get(samples.size() - 4);
        LifecycleIndicatorSample prev2 = samples.get(samples.size() - 3);
        LifecycleIndicatorSample prev1 = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);

        return prev3.getMacdBar() < prev2.getMacdBar()
                && prev2.getMacdBar() > prev1.getMacdBar()
                && prev1.getMacdBar() > current.getMacdBar()
                && current.getMacdBar() < 0;
    }

    /**
     * 閸掋倖鏌囬張鈧潻鎴犵崶閸欙綁顩荤亸缍緇ose閸戔偓閸欐ê瀵查弰顖氭儊娑撳骸绱戞禒鎾存煙閸氭垹娴夐崣宥冣偓?     */
    private boolean isCounterTrend(List<LifecycleIndicatorSample> samples, boolean longEntry) {
        if (samples == null || samples.size() < ENTRY_COUNTER_TREND_WINDOW) {
            return false;
        }
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        LifecycleIndicatorSample beforeWindow = samples.get(samples.size() - ENTRY_COUNTER_TREND_WINDOW);
        if (longEntry) {
            return current.getClose() < beforeWindow.getClose();
        }
        return current.getClose() > beforeWindow.getClose();
    }

    /**
     * 判断当前交叉是否只是短周期顺向、但 MA10 仍保持反向的局部反抽结构。
     */
    private boolean isMaCounterTrend(List<LifecycleIndicatorSample> samples, boolean longEntry) {
        if (samples == null || samples.size() < MA_TREND_CONFIRM_WINDOW + 1) {
            return false;
        }
        if (longEntry) {
            return isMa5Up(samples) && isMa10Down(samples);
        }
        return isMa5Down(samples) && isMa10Up(samples);
    }

    /**
     * 判断 MA5 最近一根是否向上。
     */
    boolean isMa5Up(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 2) {
            return false;
        }
        LifecycleIndicatorSample previous = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        return !Double.isNaN(previous.getMa5()) && !Double.isNaN(current.getMa5())
                && current.getMa5() > previous.getMa5();
    }

    /**
     * 判断 MA5 最近一根是否向下。
     */
    boolean isMa5Down(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 2) {
            return false;
        }
        LifecycleIndicatorSample previous = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        return !Double.isNaN(previous.getMa5()) && !Double.isNaN(current.getMa5())
                && current.getMa5() < previous.getMa5();
    }

    /**
     * 判断 MA10 最近 3 次比较是否连续向下。
     */
    boolean isMa10Down(List<LifecycleIndicatorSample> samples) {
        return isMa10Directional(samples, true);
    }

    /**
     * 判断 MA10 最近 3 次比较是否连续向上。
     */
    boolean isMa10Up(List<LifecycleIndicatorSample> samples) {
        return isMa10Directional(samples, false);
    }

    /**
     * 判断 MA10 最近 3 次比较是否保持同向。
     */
    private boolean isMa10Directional(List<LifecycleIndicatorSample> samples, boolean downDirection) {
        if (samples == null || samples.size() < MA_TREND_CONFIRM_WINDOW + 1) {
            return false;
        }
        int start = samples.size() - MA_TREND_CONFIRM_WINDOW - 1;
        for (int i = start + 1; i < samples.size(); i++) {
            double prev = samples.get(i - 1).getMa10();
            double current = samples.get(i).getMa10();
            if (Double.isNaN(prev) || Double.isNaN(current)) {
                return false;
            }
            if (downDirection && current >= prev) {
                return false;
            }
            if (!downDirection && current <= prev) {
                return false;
            }
        }
        return true;
    }

    /**
     * 閸掋倖鏌囪ぐ鎾冲娴溿倕寮堕弰顖氭儊鐏炵偘绨張鈧潻鎴犵崶閸欙絽鍞存禍銈呭级鏉╁洤鐦戦惃鍕础闂囧洢鈧?     */
    /**
     * 判断补开多时 MA10 最近两次比较是否连续上移。
     */
    boolean isLaunchMa10Up(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 3) {
            return false;
        }
        LifecycleIndicatorSample prev2 = samples.get(samples.size() - 3);
        LifecycleIndicatorSample prev = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        return !Double.isNaN(prev2.getMa10())
                && !Double.isNaN(prev.getMa10())
                && !Double.isNaN(current.getMa10())
                && prev.getMa10() > prev2.getMa10()
                && current.getMa10() > prev.getMa10();
    }

    /**
     * 判断补开空时 MA10 最近两次比较是否连续下移。
     */
    boolean isLaunchMa10Down(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 3) {
            return false;
        }
        LifecycleIndicatorSample prev2 = samples.get(samples.size() - 3);
        LifecycleIndicatorSample prev = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        return !Double.isNaN(prev2.getMa10())
                && !Double.isNaN(prev.getMa10())
                && !Double.isNaN(current.getMa10())
                && prev.getMa10() < prev2.getMa10()
                && current.getMa10() < prev.getMa10();
    }

    private boolean isCrossDensityBlocked(LifecycleIndicatorSample current, LifecycleConfig config) {
        return current != null
                && config != null
                && config.isCompressionFilterEnabled()
                && current.getRecentCrossCount() >= config.getCrossCountThreshold();
    }

    /**
     * 閸掋倖鏌囪ぐ鎾冲K缁炬寧妲搁崥锕€鐫樻禍搴濈瑝闁倸鎮庣粚杞扮波鏉╄棄鍙嗛惃鍕亣濞夈垹绠橩缁捐￥鈧?     */
    private boolean isLargeBarRange(LifecycleIndicatorSample current) {
        return calculateBarRangePct(current) >= LARGE_BAR_RANGE_THRESHOLD_PCT;
    }

    /**
     * ????????????0??????
     */
    private boolean isWeakCross(LifecycleIndicatorSample current) {
        return isWeakCrossByMacd(current);
    }

    /**
     * ???????MACD????????
     */
    private boolean isWeakCrossByMacd(LifecycleIndicatorSample current) {
        return current != null && Math.abs(current.getMacdBar()) < WEAK_CROSS_MACD_THRESHOLD;
    }

    /**
     * ???????DIF/DEA???????
     */
    private boolean isWeakCrossByGap(LifecycleIndicatorSample current) {
        return current != null && Math.abs(current.getDif() - current.getDea()) < WEAK_CROSS_GAP_THRESHOLD;
    }

    /**
     * 閸掋倖鏌囩悰銉ョ磻娴犳挸缍嬮弽绛€缁炬寧妲搁崥锕€鍑＄紒蹇旀杹婢堆嗙箖婢惰揪绱濋棁鈧憰浣烘埛缂侇厾鐡戝鍛纯楠炴彃鍣ｉ惃鍕儙閸斻劎鍋ｉ妴?     */
    private boolean isLaunchBarRangeBlocked(LifecycleIndicatorSample current) {
        return calculateBarRangePct(current) >= LAUNCH_BAR_RANGE_THRESHOLD_PCT;
    }

    /**
     * 閹?high-low)/open鐠侊紕鐣昏ぐ鎾冲K缁炬寧灏濋獮鍛閸掑棙鐦妴?     */
    public double calculateBarRangePct(LifecycleIndicatorSample current) {
        if (current == null || current.getOpen() <= 0.0) {
            return Double.NaN;
        }
        double range = current.getHigh() - current.getLow();
        if (Double.isNaN(range) || range < 0.0) {
            return Double.NaN;
        }
        return range / current.getOpen() * 100.0;
    }

    /**
     * 閸掋倖鏌囬張鈧潻鎱涢弽绛侫CD缂佹繂顕崐鍏兼Ц閸氾箑銇囬棃銏⑿濇担搴濈艾鐡掑濞嶉梼鍫濃偓绗衡偓?     */
    private boolean isLowMacdEntryBlocked(List<LifecycleIndicatorSample> samples,
                                          LifecycleConfig config) {
        if (samples == null || config == null || !config.isCompressionFilterEnabled()
                || samples.size() < LOW_MACD_ENTRY_WINDOW) {
            return false;
        }
        int hitCount = 0;
        for (int i = samples.size() - LOW_MACD_ENTRY_WINDOW; i < samples.size(); i++) {
            double macd = samples.get(i).getMacdBar();
            if (!Double.isNaN(macd) && Math.abs(macd) < LOW_MACD_ENTRY_THRESHOLD) {
                hitCount++;
            }
        }
        return hitCount >= LOW_MACD_ENTRY_MIN_HITS;
    }

    /**
     * 判断多头 pending 是否满足 MACD 放大补开仓条件。
     *
     * 要求：
     * 1. DIF 仍在 DEA 上方，方向没有被破坏；
     * 2. MACD 已经翻正并放大；
     * 3. 当前 K 线是多头实体；
     * 4. 价格已经突破 pending 实体上边界；
     * 5. 没有突破后追价太远；
     * 6. MA5/MA10 至少支持多头方向；
     * 7. close 最近连续走强。
     */
    private boolean isLongLaunchReady(LifecycleContext context) {
        if (context == null || context.getSamples() == null
                || context.getConfig() == null || context.getState() == null) {
            return false;
        }

        List<LifecycleIndicatorSample> samples = context.getSamples();
        LifecycleIndicatorSample current = context.current();
        LifecycleConfig config = context.getConfig();
        LifecycleState state = context.getState();

        return current.getDif() >= current.getDea()
                && current.getMacdBar() > config.getLowMacdThreshold()
                && isBullishBar(current)
                && isLongMacdLaunchPattern(samples)
                && closeIncreasing(samples, LAUNCH_CONFIRM_COMPARISONS, LAUNCH_CONFIRM_COMPARISONS)
                && hasEnoughPendingBars(state, config)
                && isLongPendingBreakoutConfirmed(samples, state)
                && isLongPendingBreakoutPassed(current, state)
                && !isLongBreakoutChasingTooFar(current, state, config)
                && isLongLaunchMaConfirmed(samples, current);
    }


    /**
     * 判断空头 pending 是否满足 MACD 放大补开仓条件。
     *
     * 要求：
     * 1. DIF 仍在 DEA 下方，方向没有被破坏；
     * 2. MACD 已经翻负并朝空头放大；
     * 3. 当前 K 线是空头实体；
     * 4. 价格已经跌破 pending 实体下边界；
     * 5. 没有跌破后追空太远；
     * 6. MA5/MA10 至少支持空头方向；
     * 7. close 最近连续走弱。
     */
    private boolean isShortLaunchReady(LifecycleContext context) {
        if (context == null || context.getSamples() == null
                || context.getConfig() == null || context.getState() == null) {
            return false;
        }

        List<LifecycleIndicatorSample> samples = context.getSamples();
        LifecycleIndicatorSample current = context.current();
        LifecycleConfig config = context.getConfig();
        LifecycleState state = context.getState();

        return current.getDif() <= current.getDea()
                && current.getMacdBar() < -config.getLowMacdThreshold()
                && isBearishBar(current)
                && isShortMacdLaunchPattern(samples)
                && closeDecreasing(samples, LAUNCH_CONFIRM_COMPARISONS, LAUNCH_CONFIRM_COMPARISONS)
                && hasEnoughPendingBars(state, config)
                && isShortPendingBreakoutConfirmed(samples, state)
                && isShortPendingBreakoutPassed(current, state)
                && !isShortBreakoutChasingTooFar(current, state, config)
                && isShortLaunchMaConfirmed(samples, current);
    }

    /**
     * 判断 pending 观察时间是否已经达到 MACD 放大补开仓的最小要求。
     */
    private boolean hasEnoughPendingBars(LifecycleState state, LifecycleConfig config) {
        if (state == null || config == null) {
            return false;
        }

        int minBars = config.getLaunchMinPendingBars();
        if (minBars <= 0) {
            return true;
        }

        return state.getPendingBars() >= minBars;
    }

    /**
     * 判断多头是否已经连续两根 K 线站上 pending 实体上边界。
     */
    private boolean isLongPendingBreakoutConfirmed(List<LifecycleIndicatorSample> samples,
                                                   LifecycleState state) {
        if (samples == null || samples.size() < 2 || state == null
                || !state.isPendingRangeReady()
                || Double.isNaN(state.getPendingUpperBodyBound())) {
            return false;
        }

        LifecycleIndicatorSample previous = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        double upperBound = state.getPendingUpperBodyBound();

        return previous.getClose() > upperBound
                && current.getClose() > upperBound
                && (current.getClose() >= previous.getClose()
                || current.getMacdBar() > previous.getMacdBar());
    }

    /**
     * 判断空头是否已经连续两根 K 线跌破 pending 实体下边界。
     */
    private boolean isShortPendingBreakoutConfirmed(List<LifecycleIndicatorSample> samples,
                                                    LifecycleState state) {
        if (samples == null || samples.size() < 2 || state == null
                || !state.isPendingRangeReady()
                || Double.isNaN(state.getPendingLowerBodyBound())) {
            return false;
        }

        LifecycleIndicatorSample previous = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        double lowerBound = state.getPendingLowerBodyBound();

        return previous.getClose() < lowerBound
                && current.getClose() < lowerBound
                && (current.getClose() <= previous.getClose()
                || current.getMacdBar() < previous.getMacdBar());
    }

    /**
     * 判断多头突破后是否追价过远。
     */
    private boolean isLongBreakoutChasingTooFar(LifecycleIndicatorSample current,
                                                LifecycleState state,
                                                LifecycleConfig config) {
        if (current == null || state == null || config == null) {
            return false;
        }
        if (current.getClose() <= 0.0d || Double.isNaN(state.getPendingUpperBodyBound())) {
            return false;
        }
        double maxRatio = config.getLaunchMaxBreakoutDistanceRatio();
        if (maxRatio <= 0.0d) {
            return false;
        }
        return (current.getClose() - state.getPendingUpperBodyBound()) / current.getClose() > maxRatio;
    }

    /**
     * 判断空头跌破后是否追空过远。
     */
    private boolean isShortBreakoutChasingTooFar(LifecycleIndicatorSample current,
                                                 LifecycleState state,
                                                 LifecycleConfig config) {
        if (current == null || state == null || config == null) {
            return false;
        }
        if (current.getClose() <= 0.0d || Double.isNaN(state.getPendingLowerBodyBound())) {
            return false;
        }
        double maxRatio = config.getLaunchMaxBreakoutDistanceRatio();
        if (maxRatio <= 0.0d) {
            return false;
        }
        return (state.getPendingLowerBodyBound() - current.getClose()) / current.getClose() > maxRatio;
    }

    /**
     * 判断多头补开仓是否得到 MA5/MA10 趋势确认。
     */
    private boolean isLongLaunchMaConfirmed(List<LifecycleIndicatorSample> samples,
                                            LifecycleIndicatorSample current) {
        return current != null
                && isMa5Up(samples)
                && (isMa10Up(samples) || current.getClose() > current.getMa10());
    }

    /**
     * 判断空头补开仓是否得到 MA5/MA10 趋势确认。
     */
    private boolean isShortLaunchMaConfirmed(List<LifecycleIndicatorSample> samples,
                                             LifecycleIndicatorSample current) {
        return current != null
                && isMa5Down(samples)
                && (isMa10Down(samples) || current.getClose() < current.getMa10());
    }

    /**
     * 判断多头补开仓是否已经突破 pending 实体上边界。
     */
    private boolean isLongPendingBreakoutPassed(LifecycleIndicatorSample current, LifecycleState state) {
        return current != null
                && state != null
                && state.isPendingRangeReady()
                && !Double.isNaN(state.getPendingUpperBodyBound())
                && current.getClose() > state.getPendingUpperBodyBound();
    }

    /**
     * 判断空头补开仓是否已经跌破 pending 实体下边界。
     */
    private boolean isShortPendingBreakoutPassed(LifecycleIndicatorSample current, LifecycleState state) {
        return current != null
                && state != null
                && state.isPendingRangeReady()
                && !Double.isNaN(state.getPendingLowerBodyBound())
                && current.getClose() < state.getPendingLowerBodyBound();
    }

    /**
     * 判断多头方向是否出现 MACD 低位拐头后连续放大。
     *
     * 形态：
     * prev3 > prev2 < prev1 < current
     * 且 current MACD > 0。
     */
    private boolean isLongMacdLaunchPattern(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 4) {
            return false;
        }

        LifecycleIndicatorSample prev3 = samples.get(samples.size() - 4);
        LifecycleIndicatorSample prev2 = samples.get(samples.size() - 3);
        LifecycleIndicatorSample prev1 = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);

        return prev3.getMacdBar() > prev2.getMacdBar()
                && prev2.getMacdBar() < prev1.getMacdBar()
                && prev1.getMacdBar() < current.getMacdBar()
                && current.getMacdBar() > 0.0d;
    }

    /**
     * 判断空头方向是否出现 MACD 高位拐头后连续放大。
     *
     * 形态：
     * prev3 < prev2 > prev1 > current
     * 且 current MACD < 0。
     */
    private boolean isShortMacdLaunchPattern(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 4) {
            return false;
        }

        LifecycleIndicatorSample prev3 = samples.get(samples.size() - 4);
        LifecycleIndicatorSample prev2 = samples.get(samples.size() - 3);
        LifecycleIndicatorSample prev1 = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);

        return prev3.getMacdBar() < prev2.getMacdBar()
                && prev2.getMacdBar() > prev1.getMacdBar()
                && prev1.getMacdBar() > current.getMacdBar()
                && current.getMacdBar() < 0.0d;
    }


    /**
     * 閸掋倖鏌囨径姘仈鐞涖儱绱戞禒鎾存Ц閸氾妇鐛婇惍?pending 鐟欏倸鐧傞張鐔兼？瀹稿弶婀侀張鈧?close閵?     */
    private boolean isLongPendingRangeBreakout(LifecycleIndicatorSample current,
                                               LifecycleState state) {
        if (current == null || state == null || !state.isPendingRangeReady()
                || Double.isNaN(state.getPendingUpperBodyBound())) {
            return false;
        }
        return current.getClose() > state.getPendingUpperBodyBound();
    }

    /**
     * 閸掋倖鏌囩粚鍝勩仈鐞涖儱绱戞禒鎾存Ц閸氾箒绌奸惍?pending 鐟欏倸鐧傞張鐔兼？瀹稿弶婀侀張鈧担?close閵?     */
    private boolean isShortPendingRangeBreakout(LifecycleIndicatorSample current,
                                                LifecycleState state) {
        if (current == null || state == null || !state.isPendingRangeReady()
                || Double.isNaN(state.getPendingLowerBodyBound())) {
            return false;
        }
        return current.getClose() < state.getPendingLowerBodyBound();
    }

    /**
     * 閸掋倖鏌囨径姘仈鐞涖儱绱戞禒鎾存Ц閸氾箑鍑＄紒蹇庡紬閺嶅吋鏁归惍?pending 鐟欏倸鐧傛妯煎仯閵?     */
    private boolean isLongStrictPendingBreakout(LifecycleIndicatorSample current, LifecycleState state) {
        return current != null
                && state != null
                && !Double.isNaN(state.getPendingHighClose())
                && current.getClose() > state.getPendingHighClose();
    }

    /**
     * 閸掋倖鏌囩粚鍝勩仈鐞涖儱绱戞禒鎾存Ц閸氾箑鍑＄紒蹇庡紬閺嶅吋鏁归惍?pending 鐟欏倸鐧傛担搴ｅ仯閵?     */
    private boolean isShortStrictPendingBreakout(LifecycleIndicatorSample current, LifecycleState state) {
        return current != null
                && state != null
                && !Double.isNaN(state.getPendingLowClose())
                && current.getClose() < state.getPendingLowClose();
    }

    /**
     * 閸掋倖鏌囨径姘仈鐞涖儱绱戞禒鎾舵畱娴犻攱鐗哥涵顔款吇閺勵垰鎯佸陇鍐婚崢鐔奉潗閺€璺哄繁閿涘本鍨ㄥ鑼剁箻閸忋儱顔愬顔剧崐閻潙灏妴?     */
    private boolean hasLongLaunchPriceConfirmation(List<LifecycleIndicatorSample> samples,
                                                   LifecycleIndicatorSample current) {
        return closeIncreasing(samples, LAUNCH_CONFIRM_COMPARISONS, LAUNCH_CONFIRM_COMPARISONS)
                || isBullishBar(current);
    }

    /**
     * 閸掋倖鏌囩粚鍝勩仈鐞涖儱绱戞禒鎾舵畱娴犻攱鐗哥涵顔款吇閺勵垰鎯佸陇鍐婚崢鐔奉潗鐠ф澘鎬ラ敍灞惧灗瀹歌尪绻橀崗銉ヮ啇瀹割喚鐛婇惍鏉戝隘閵?     */
    private boolean hasShortLaunchPriceConfirmation(List<LifecycleIndicatorSample> samples,
                                                    LifecycleIndicatorSample current) {
        return closeDecreasing(samples, LAUNCH_CONFIRM_COMPARISONS, LAUNCH_CONFIRM_COMPARISONS)
                || isBearishBar(current);
    }

    /**
     * 閸掋倖鏌囨径姘仈鐞涖儱绱戞禒鎾舵畱MACD绾喛顓婚弰顖氭儊濠娐ゅ喕娑撱儲鐗告稉銈嗩偧閺€鎯с亣閿涘本鍨ㄩ崗浣筋啅娑撯偓濞喡や氦瀵邦喖娲栭煪鈺佹倵閻ㄥ嫮鎴风紒顓熸杹婢堆佲偓?     */
    private boolean hasLongLaunchMacdConfirmation(List<LifecycleIndicatorSample> samples) {
        return macdIncreasing(samples, LAUNCH_CONFIRM_COMPARISONS, LAUNCH_CONFIRM_COMPARISONS)
                || hasMacdToleranceConfirmation(samples, true);
    }

    /**
     * 閸掋倖鏌囩粚鍝勩仈鐞涖儱绱戞禒鎾舵畱MACD绾喛顓婚弰顖氭儊濠娐ゅ喕娑撱儲鐗告稉銈嗩偧閺€鎯с亣閿涘本鍨ㄩ崗浣筋啅娑撯偓濞喡や氦瀵邦喖娲栧鐟版倵閻ㄥ嫮鎴风紒顓熸杹婢堆佲偓?     */
    private boolean hasShortLaunchMacdConfirmation(List<LifecycleIndicatorSample> samples) {
        return macdDecreasing(samples, LAUNCH_CONFIRM_COMPARISONS, LAUNCH_CONFIRM_COMPARISONS)
                || hasMacdToleranceConfirmation(samples, false);
    }

    /**
     * 閸掋倖鏌囬張鈧潻鎴滅瑏閺嶇瓊ACD缂佹繂顕崐鍏兼Ц閸氾箑鍘戠拋绋挎躬娑撯偓濞喡や氦瀵邦喖娲栭幘銈呮倵缂佈呯敾閺€鎯с亣閵?     */
    private boolean hasMacdToleranceConfirmation(List<LifecycleIndicatorSample> samples, boolean longDirection) {
        if (samples == null || samples.size() < 3) {
            return false;
        }
        LifecycleIndicatorSample older = samples.get(samples.size() - 3);
        LifecycleIndicatorSample previous = samples.get(samples.size() - 2);
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        double olderAbs = Math.abs(older.getMacdBar());
        double previousAbs = Math.abs(previous.getMacdBar());
        double currentAbs = Math.abs(current.getMacdBar());
        if (currentAbs <= previousAbs) {
            return false;
        }
        double rollback = olderAbs - previousAbs;
        if (rollback < 0.0 || rollback > MACD_CONFIRM_TOLERANCE) {
            return false;
        }
        if (longDirection) {
            return current.getMacdBar() > 0.0 && previous.getMacdBar() > 0.0;
        }
        return current.getMacdBar() < 0.0 && previous.getMacdBar() < 0.0;
    }

    /**
     * 閸掋倖鏌囨径姘仈鐞涖儱绱戞禒鎾存Ц閸氾箑鍑℃潻娑樺弳 pending 妤傛鍋ｉ梽鍕箮閻ㄥ嫬顔愬顔剧崐閻潙灏妴?     */
    private boolean isLongToleranceBreakout(LifecycleIndicatorSample current, LifecycleState state) {
        return current != null
                && state != null
                && !Double.isNaN(state.getPendingHighClose())
                && current.getClose() >= state.getPendingHighClose() - PENDING_BREAKOUT_TOLERANCE
                && isBullishBar(current);
    }

    /**
     * 閸掋倖鏌囩粚鍝勩仈鐞涖儱绱戞禒鎾存Ц閸氾箑鍑℃潻娑樺弳 pending 娴ｅ海鍋ｉ梽鍕箮閻ㄥ嫬顔愬顔剧崐閻潙灏妴?     */
    private boolean isShortToleranceBreakout(LifecycleIndicatorSample current, LifecycleState state) {
        return current != null
                && state != null
                && !Double.isNaN(state.getPendingLowClose())
                && current.getClose() <= state.getPendingLowClose() + PENDING_BREAKOUT_TOLERANCE
                && isBearishBar(current);
    }

    /**
     * 閸掋倖鏌囪ぐ鎾冲K缁炬寧妲搁崥锔胯礋婢舵艾銇旈崥灞芥倻鐎圭偘缍嬮妴?     */
    private boolean isBullishBar(LifecycleIndicatorSample current) {
        return current != null && current.getClose() > current.getOpen();
    }

    /**
     * 閸掋倖鏌囪ぐ鎾冲K缁炬寧妲搁崥锔胯礋缁屽搫銇旈崥灞芥倻鐎圭偘缍嬮妴?     */
    private boolean isBearishBar(LifecycleIndicatorSample current) {
        return current != null && current.getClose() < current.getOpen();
    }

    /**
     * 閸掋倖鏌囪ぐ鎾冲MACD閺岃京绮风€电懓鈧吋妲搁崥锔界槷閸撳秳绔撮弽鍦埛缂侇厽鏂佹径褋鈧?     */
    private boolean macdAbsExpanding(List<LifecycleIndicatorSample> samples) {
        if (samples == null || samples.size() < 2) {
            return false;
        }
        LifecycleIndicatorSample current = samples.get(samples.size() - 1);
        LifecycleIndicatorSample previous = samples.get(samples.size() - 2);
        return Math.abs(current.getMacdBar()) > Math.abs(previous.getMacdBar());
    }

    /**
     * 閸掋倖鏌囬張鈧潻鎴犵崶閸欙絽鍞碊IF閸滃瓕EA閺勵垰鎯佹径鍕艾缁ê鎮庡▓鐐光偓?     */
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
     * 閸掋倖鏌囬崣宥呮倻娴溿倕寮堕弰顖氭儊娴犲秴鐫樻禍搴ｇ煒閸氬牆浜ｇ粣浣虹壃閵?     */
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
     * 閸掋倖鏌嘙ACD閺岃鲸妲搁崥锕€婀粣妤€褰涢崘鍛扮箾缂侇厽鏂佹径褋鈧?     */
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
     * 閸掋倖鏌嘙ACD閺岃鲸妲搁崥锕€婀粣妤€褰涢崘鍛篂缁屽搫銇旈弬鐟版倻閺€鎯с亣閵?     */
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
     * 閸掋倖鏌嘽lose閺勵垰鎯侀崷銊х崶閸欙絽鍞存潻鐐电敾鐠ф澘宸遍妴?     */
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

    /**
     * 閸掋倖鏌嘽lose閺勵垰鎯侀崷銊х崶閸欙絽鍞存潻鐐电敾鐠ф澘鎬ラ妴?     */
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
     * 閸掋倖鏌噇ow閺勵垰鎯侀崷銊х崶閸欙絽鍞存潻鐐电敾鐠ф澘鎬ラ妴?     */
    private boolean lowDecreasing(List<LifecycleIndicatorSample> samples, int window, int minCount) {
        if (samples.size() <= window || window < 1) {
            return false;
        }
        int count = 0;
        int start = samples.size() - window;
        for (int i = start; i < samples.size(); i++) {
            if (samples.get(i).getLow() < samples.get(i - 1).getLow()) {
                count++;
            }
        }
        return count >= minCount;
    }

    /**
     * 閸掋倖鏌噇ow閺勵垰鎯侀崷銊х崶閸欙絽鍞存潻鐐电敾鐠ф澘宸遍妴?     */
    private boolean lowIncreasing(List<LifecycleIndicatorSample> samples, int window, int minCount) {
        if (samples.size() <= window || window < 1) {
            return false;
        }
        int count = 0;
        int start = samples.size() - window;
        for (int i = start; i < samples.size(); i++) {
            if (samples.get(i).getLow() > samples.get(i - 1).getLow()) {
                count++;
            }
        }
        return count >= minCount;
    }

    /**
     * 閸掋倖鏌噃igh閺勵垰鎯侀崷銊х崶閸欙絽鍞存潻鐐电敾鐠ф澘宸遍妴?     */
    private boolean highIncreasing(List<LifecycleIndicatorSample> samples, int window, int minCount) {
        if (samples.size() <= window || window < 1) {
            return false;
        }
        int count = 0;
        int start = samples.size() - window;
        for (int i = start; i < samples.size(); i++) {
            if (samples.get(i).getHigh() > samples.get(i - 1).getHigh()) {
                count++;
            }
        }
        return count >= minCount;
    }

    /**
     * 閸ュ搫鐣鹃弽瑙勬殶鐡掑懏妞傞弳鍌涙閸忔娊妫撮敍瀹瞖ndingBars 娴犲懍缍旀稉楦款潎鐎电喐妫╄箛妤€鐡у▓鐐光偓?     */
    private boolean isPendingExpired(LifecycleContext context) {
        return false;
    }
}

