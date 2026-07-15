package com.app.dc.service.simulation.strategy.lifecycle;

import com.app.dc.po.OCType;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灏嗘瀬绠€DIF/DEA閲戝弶姝诲弶閫昏緫鎺ュ叆 simulator 鍥炴祴绛栫暐鎺ュ彛銆? */
@Slf4j
@Service("difDeaLifecycle")
public class DifDeaLifecycleBacktestStrategy implements BinanceBacktestStrategy {

    private static final String TEXT_5M = "5M";
    private static final int MACD_WINDOW = 80;
    private static final String STOP_SOURCE_EXTREME_COUNTERTREND = "extreme_countertrend";
    private static final String STOP_SOURCE_FLAT_MA20 = "flat_ma20";

    private final LifecycleIndicatorCalculator indicatorCalculator = new LifecycleIndicatorCalculator(MACD_WINDOW);
    private final DifDeaLifecycleDecisionEngine decisionEngine;
    private final LifecycleConfigProvider configProvider;
    private final Map<String, LifecycleState> stateMap = new ConcurrentHashMap<String, LifecycleState>();
    private final Map<String, Map<String, Integer>> rejectStats =
            new ConcurrentHashMap<String, Map<String, Integer>>();

    /**
     * 娉ㄥ叆閲戝弶姝诲弶鍐崇瓥鍜岄粯璁ら厤缃彁渚涘櫒銆?     */
    public DifDeaLifecycleBacktestStrategy(DifDeaLifecycleDecisionEngine decisionEngine,
                                           LifecycleConfigProvider configProvider) {
        this.decisionEngine = decisionEngine;
        this.configProvider = configProvider;
    }

    @Override
    /**
     * 杩斿洖绛栫暐娉ㄥ唽鍚嶃€?     */
    public String getName() {
        return DifDeaLifecycleDecisionEngine.STRATEGY_NAME;
    }

    @Override
    /**
     * 瀵瑰綋鍓嶅洖鏀綤绾跨敓鎴愰噾鍙夋鍙変氦鏄撲俊鍙枫€?     */
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedText = normalizeText(text);
        if (!TEXT_5M.equals(normalizedText)) {
            reject(normalizedSymbol, "unsupported_text");
            return signal;
        }
        logRawKline(normalizedSymbol, normalizedText, series, currentOhlc);
        LifecycleConfig config = configProvider.getConfig(normalizedSymbol, normalizedText);
        if (series == null || series.getBarCount() < config.getWarmupBars()) {
            reject(normalizedSymbol, "not_enough_warmup_bars");
            return signal;
        }
        List<LifecycleIndicatorSample> samples = indicatorCalculator.calculate(series);
        if (samples.size() < config.getWarmupBars()) {
            reject(normalizedSymbol, "not_enough_warmup_bars");
            return signal;
        }
        LifecycleIndicatorSample latest = samples.get(samples.size() - 1);
        LifecycleState state = stateMap.computeIfAbsent(key(normalizedSymbol, normalizedText),
                item -> new LifecycleState());
        if (latest.getEndTime().equals(state.getLastBarTime())) {
            reject(normalizedSymbol, "duplicate_bar");
            return signal;
        }

        int recentCrossCount = decisionEngine.calculateRecentCrossCount(samples, config);
        LifecycleContext context = new LifecycleContext(normalizedSymbol, normalizedText, samples, config, state);
        double breakoutConfirmRatio = decisionEngine.calculatePendingBreakoutRatio(state, latest);
        double ma20TrendPct = decisionEngine.calculateMa20TrendPct(context);
        LifecycleDirection ma20TrendDirection = decisionEngine.detectMa20TrendDirection(context);
        LifecycleDecision decision = isProtectiveStopHit(state, latest)
                ? LifecycleDecision.none(protectiveStopSyncReason(state))
                : decisionEngine.decide(context);
        boolean profitExtensionActive = state.isProfitExtensionActive();
        double profitExtensionReverseGap = decisionEngine.calculateReverseGap(state, latest);
        double entrySignalHigh = state.getEntrySignalHigh();
        double entrySignalLow = state.getEntrySignalLow();
        double entryMacdStrength = state.getEntryMacdStrength();
        double currentMacdRetentionRatio = decisionEngine.calculateCurrentMacdRetentionRatio(state, latest);
        boolean earlyFailureWindow = decisionEngine.isInEarlyFailureWindow(state, latest, config);
        double maxFavorableProgressPct = decisionEngine.calculateMaxFavorableProgressPct(context);
        double currentProgressPct = decisionEngine.calculateCurrentProgressPct(state, latest);
        boolean entrySignalBoundaryInvalidated = decisionEngine.wasEntrySignalBoundaryInvalidated(context);
        boolean reverseBlockedByProfitableWeakCross =
                decisionEngine.isReverseBlockedByProfitableWeakCross(state, latest, config);
        updateState(state, latest, decision, config);
        boolean protectiveStopArmed = armProtectiveStop(
                state, latest, decision, ma20TrendPct, breakoutConfirmRatio, config);
        logDecisionKline(normalizedSymbol, normalizedText, config, latest, state, decision, recentCrossCount,
                breakoutConfirmRatio, reverseBlockedByProfitableWeakCross, entrySignalHigh, entrySignalLow,
                entryMacdStrength, currentMacdRetentionRatio, earlyFailureWindow,
                maxFavorableProgressPct, currentProgressPct, entrySignalBoundaryInvalidated,
                ma20TrendPct, ma20TrendDirection, profitExtensionActive, profitExtensionReverseGap);
        if (!decision.hasAction()) {
            reject(normalizedSymbol, decision.getReason());
            return signal;
        }
        applyDecision(signal, decision);
        if (protectiveStopArmed) {
            signal.stopPrice = BinanceStrategyMath.scale(state.getProtectiveStopPrice());
        }
        signal.algoName = getName();
        signal.remark = buildRemark(decision, latest, state, config, recentCrossCount,
                reverseBlockedByProfitableWeakCross, entrySignalHigh, entrySignalLow, entryMacdStrength,
                currentMacdRetentionRatio, earlyFailureWindow,
                maxFavorableProgressPct, currentProgressPct, entrySignalBoundaryInvalidated,
                ma20TrendPct, ma20TrendDirection, profitExtensionActive, profitExtensionReverseGap);
        return signal;
    }

    @Override
    /**
     * 閲嶇疆鎸囧畾鍝佺鐨勭姸鎬佸拰鎷掔粷缁熻銆?     */
    public void resetRejectStats(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        rejectStats.remove(normalizedSymbol);
        stateMap.keySet().removeIf(key -> key.startsWith(normalizedSymbol + "|"));
    }

    @Override
    /**
     * 鑾峰彇鎷掔粷缁熻蹇収銆?     */
    public Map<String, Integer> snapshotRejectStats(String symbol) {
        Map<String, Integer> stats = rejectStats.get(normalizeSymbol(symbol));
        if (stats == null) {
            return new LinkedHashMap<String, Integer>();
        }
        return new LinkedHashMap<String, Integer>(stats);
    }

    /**
     * 灏嗗喅绛栧姩浣滄槧灏勪负鍥炴祴淇″彿銆?     */
    private void applyDecision(Signal signal, LifecycleDecision decision) {
        if (decision.getType() == LifecycleDecisionType.ENTER_LONG) {
            signal.side = Side.BUY;
            signal.ocType = OCType.OPEN;
        } else if (decision.getType() == LifecycleDecisionType.ENTER_SHORT) {
            signal.side = Side.SELL;
            signal.ocType = OCType.OPEN;
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_LONG) {
            signal.side = Side.SELL;
            signal.ocType = OCType.ClOSE;
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_SHORT) {
            signal.side = Side.BUY;
            signal.ocType = OCType.ClOSE;
        }
    }

    /**
     * 鏍规嵁浜ゅ弶鍐崇瓥鎺ㄨ繘鍐呭瓨浠撲綅鐘舵€併€?     */
    private void updateState(LifecycleState state, LifecycleIndicatorSample latest, LifecycleDecision decision,
                             LifecycleConfig config) {
        int holdBars = currentHoldBars(state, latest);
        state.setLastBarTime(latest.getEndTime());
        state.setLastDecision(decision.getType().name());
        state.setLastReason(decision.getReason());

        if ("extreme_countertrend_stop_synced".equals(decision.getReason())
                || "flat_ma20_stop_synced".equals(decision.getReason())) {
            applyWhipsawCooldown(state, latest, holdBars, config);
            state.toNeutral();
            state.clearPendingReverse();
            return;
        }

        if (decision.getType() == LifecycleDecisionType.ENTER_LONG) {
            enterLong(state, latest, decision.getEntrySource());
            return;
        }
        if (decision.getType() == LifecycleDecisionType.ENTER_SHORT) {
            enterShort(state, latest, decision.getEntrySource());
            return;
        }
        if (decision.getType() == LifecycleDecisionType.LEAVE_LONG) {
            if (isEarlyTrendFailureExit(decision)) {
                applyWhipsawCooldown(state, latest, holdBars, config);
                state.toNeutral();
                state.clearPendingReverse();
            } else if (decisionEngine.canStartPendingReverse(state, latest, config)) {
                state.toNeutral();
                state.startPendingReverse(LifecycleDirection.SHORT, latest,
                        "reverse_short_after_dif_dea_cross_down");
            } else {
                applyWhipsawCooldown(state, latest, holdBars, config);
                state.toNeutral();
                state.clearPendingReverse();
            }
            return;
        }
        if (decision.getType() == LifecycleDecisionType.LEAVE_SHORT) {
            if (isEarlyTrendFailureExit(decision)) {
                applyWhipsawCooldown(state, latest, holdBars, config);
                state.toNeutral();
                state.clearPendingReverse();
            } else if (decisionEngine.canStartPendingReverse(state, latest, config)) {
                state.toNeutral();
                state.startPendingReverse(LifecycleDirection.LONG, latest,
                        "reverse_long_after_dif_dea_cross_up");
            } else {
                applyWhipsawCooldown(state, latest, holdBars, config);
                state.toNeutral();
                state.clearPendingReverse();
            }
        }
    }

    /**
     * 判断当前离场是否由新仓早期趋势失败触发。
     */
    private boolean isEarlyTrendFailureExit(LifecycleDecision decision) {
        return decision != null
                && ("early_trend_failure_long".equals(decision.getReason())
                || "early_trend_failure_short".equals(decision.getReason())
                || "trend_fifth_bar_failure_long".equals(decision.getReason())
                || "trend_fifth_bar_failure_short".equals(decision.getReason())
                || "trend_not_launched_long".equals(decision.getReason())
                || "trend_not_launched_short".equals(decision.getReason())
                || "trend_zero_progress_long".equals(decision.getReason())
                || "trend_zero_progress_short".equals(decision.getReason())
                || "trend_checkpoint_giveback_long".equals(decision.getReason())
                || "trend_checkpoint_giveback_short".equals(decision.getReason())
                || "early_profit_round_trip_long".equals(decision.getReason())
                || "early_profit_round_trip_short".equals(decision.getReason())
                || "mature_profit_giveback_long".equals(decision.getReason())
                || "mature_profit_giveback_short".equals(decision.getReason())
                || "profit_extension_reversal_confirmed_long".equals(decision.getReason())
                || "profit_extension_reversal_confirmed_short".equals(decision.getReason())
                || "profit_extension_floor_long".equals(decision.getReason())
                || "profit_extension_floor_short".equals(decision.getReason()));
    }

    /**
     * 璁剧疆澶氬ご鎸佷粨鐘舵€併€?     */
    private void enterLong(LifecycleState state, LifecycleIndicatorSample latest, String entrySource) {
        state.setPhase(LifecyclePhase.LONG_ACTIVE);
        state.setDirection(LifecycleDirection.LONG);
        state.setEntryIndex(latest.getIndex());
        state.setEntryPrice(latest.getClose());
        state.setEntrySource(entrySource);
        state.clearProtectiveStop();
        state.clearProfitExtension();
        state.setCooldownUntilIndex(-1);
        state.clearPendingEntry();
        state.clearPendingReverse();
    }

    /**
     * 璁剧疆绌哄ご鎸佷粨鐘舵€併€?     */
    private void enterShort(LifecycleState state, LifecycleIndicatorSample latest, String entrySource) {
        state.setPhase(LifecyclePhase.SHORT_ACTIVE);
        state.setDirection(LifecycleDirection.SHORT);
        state.setEntryIndex(latest.getIndex());
        state.setEntryPrice(latest.getClose());
        state.setEntrySource(entrySource);
        state.clearProtectiveStop();
        state.clearProfitExtension();
        state.setCooldownUntilIndex(-1);
        state.clearPendingEntry();
        state.clearPendingReverse();
    }

    /**
     * 鐭寔浠撹鍙嶅悜浜ゅ弶鎵撳嚭鍚庤繘鍏ュ喎鍗达紝鍑忓皯杩炵画鍙嶆墜銆?     */
    private void applyWhipsawCooldown(LifecycleState state, LifecycleIndicatorSample latest, int holdBars,
                                      LifecycleConfig config) {
        if (state == null || latest == null || config == null) {
            return;
        }
        if (holdBars <= config.getNoReverseHoldBars()) {
            state.setCooldownUntilIndex(latest.getIndex() + config.getWhipsawCooldownBars());
        }
    }

    /**
     * 判断回测引擎是否已在当前K线触发策略保护止损。
     */
    private boolean isProtectiveStopHit(LifecycleState state, LifecycleIndicatorSample latest) {
        if (state == null || latest == null || latest.getIndex() <= state.getEntryIndex()) {
            return false;
        }
        double stopPrice = state.getProtectiveStopPrice();
        if (!Double.isFinite(stopPrice) || stopPrice <= 0.0d) {
            return false;
        }
        return state.inLong() ? latest.getLow() <= stopPrice
                : state.inShort() && latest.getHigh() >= stopPrice;
    }

    /**
     * 根据入场环境挂载极端逆势或MA20走平专属止损。
     */
    private boolean armProtectiveStop(LifecycleState state,
                                      LifecycleIndicatorSample latest,
                                      LifecycleDecision decision,
                                      double ma20TrendPct,
                                      double breakoutConfirmRatio,
                                      LifecycleConfig config) {
        if (state == null || latest == null || decision == null || config == null) {
            return false;
        }
        double threshold = config.getExtremeCounterTrendThresholdPct();
        boolean strongBreakout = Double.isFinite(breakoutConfirmRatio)
                && breakoutConfirmRatio >= config.getExtremeCounterTrendStrongBreakoutRatio();
        double configuredStopLossPct = strongBreakout
                ? config.getExtremeCounterTrendStopLossPct()
                : config.getExtremeCounterTrendWideStopLossPct();
        double stopLossPct = configuredStopLossPct / 100.0d;
        if (decision.getType() == LifecycleDecisionType.ENTER_LONG && ma20TrendPct <= -threshold) {
            state.setProtectiveStop(latest.getClose() * (1.0d - stopLossPct),
                    STOP_SOURCE_EXTREME_COUNTERTREND);
            return true;
        }
        if (decision.getType() == LifecycleDecisionType.ENTER_SHORT && ma20TrendPct >= threshold) {
            state.setProtectiveStop(latest.getClose() * (1.0d + stopLossPct),
                    STOP_SOURCE_EXTREME_COUNTERTREND);
            return true;
        }
        if (!isOrdinaryConfirmedEntry(decision)
                || !Double.isFinite(ma20TrendPct)
                || Math.abs(ma20TrendPct) > config.getMa20TrendThresholdPct()) {
            return false;
        }
        double flatStopLossPct = config.getFlatMa20StopLossPct() / 100.0d;
        if (decision.getType() == LifecycleDecisionType.ENTER_LONG) {
            state.setProtectiveStop(latest.getClose() * (1.0d - flatStopLossPct), STOP_SOURCE_FLAT_MA20);
            return true;
        }
        if (decision.getType() == LifecycleDecisionType.ENTER_SHORT) {
            state.setProtectiveStop(latest.getClose() * (1.0d + flatStopLossPct), STOP_SOURCE_FLAT_MA20);
            return true;
        }
        return false;
    }

    /**
     * 判断当前动作是否为普通交叉确认入场。
     */
    private boolean isOrdinaryConfirmedEntry(LifecycleDecision decision) {
        if (decision == null) {
            return false;
        }
        String entrySource = decision.getEntrySource();
        return "confirmed_dif_dea_cross_up".equals(entrySource)
                || "confirmed_dif_dea_cross_down".equals(entrySource);
    }

    /**
     * 根据保护止损来源返回状态同步原因。
     */
    private String protectiveStopSyncReason(LifecycleState state) {
        return STOP_SOURCE_FLAT_MA20.equals(state.getProtectiveStopSource())
                ? "flat_ma20_stop_synced"
                : "extreme_countertrend_stop_synced";
    }

    /**
     * 鏋勫缓淇″彿澶囨敞浠ュ吋瀹圭幇鏈夊弽鎵嬭瘑鍒€?     */
    private String buildRemark(LifecycleDecision decision,
                               LifecycleIndicatorSample latest,
                               LifecycleState state,
                               LifecycleConfig config,
                               int recentCrossCount,
                               boolean reverseBlockedByProfitableWeakCross,
                               double entrySignalHigh,
                               double entrySignalLow,
                               double entryMacdStrength,
                               double currentMacdRetentionRatio,
                               boolean earlyFailureWindow,
                               double maxFavorableProgressPct,
                               double currentProgressPct,
                               boolean entrySignalBoundaryInvalidated,
                               double ma20TrendPct,
                               LifecycleDirection ma20TrendDirection,
                               boolean profitExtensionActive,
                               double profitExtensionReverseGap) {
        return getName()
                + " decision=" + decision.getType()
                + ", reason=" + decision.getReason()
                + ", reverseEntry=" + decision.isReverseEntryAllowed()
                + ", phase=" + state.getPhase()
                + ", direction=" + state.getDirection()
                + ", barTime=" + latest.getEndTime()
                + ", dif=" + latest.getDif()
                + ", dea=" + latest.getDea()
                + ", macd=" + latest.getMacdBar()
                + ", barRangePct=" + decisionEngine.calculateBarRangePct(latest)
                + ", holdBars=" + currentHoldBars(state, latest)
                + ", entryPrice=" + state.getEntryPrice()
                + ", entrySource=" + state.getEntrySource()
                + ", cooldownUntilIndex=" + state.getCooldownUntilIndex()
                + ", inCooldown=" + isInCooldown(state, latest)
                + ", recentCrossCount=" + recentCrossCount
                + ", pendingEntryDirection=" + state.getPendingEntryDirection()
                + ", pendingEntryIndex=" + state.getPendingEntryIndex()
                + ", pendingEntryAge=" + decisionEngine.pendingEntryAge(state, latest)
                + ", pendingReverseDirection=" + state.getPendingReverseDirection()
                + ", pendingReverseIndex=" + state.getPendingReverseIndex()
                + ", pendingReverseAge=" + decisionEngine.pendingReverseAge(state, latest)
                + ", pendingReverseSource=" + state.getPendingReverseSource()
                + ", reverseBlockedByProfitableWeakCross="
                + reverseBlockedByProfitableWeakCross
                + ", entrySignalHigh=" + entrySignalHigh
                + ", entrySignalLow=" + entrySignalLow
                + ", entryMacdStrength=" + entryMacdStrength
                + ", currentMacdRetentionRatio=" + currentMacdRetentionRatio
                + ", earlyFailureWindow=" + earlyFailureWindow
                + ", maxFavorableProgressPct=" + maxFavorableProgressPct
                + ", currentProgressPct=" + currentProgressPct
                + ", profitGivebackActivationPct=" + config.getProfitGivebackActivationPct()
                + ", earlyProfitRoundTripMaxHoldBars=" + config.getEarlyProfitRoundTripMaxHoldBars()
                + ", matureProfitGivebackMinHoldBars=" + config.getMatureProfitGivebackMinHoldBars()
                + ", matureProfitRetainedPct=" + config.getMatureProfitRetainedPct()
                + ", matureProfitMacdRetentionRatio=" + config.getMatureProfitMacdRetentionRatio()
                + ", entrySignalBoundaryInvalidated=" + entrySignalBoundaryInvalidated
                + ", ma20TrendPct=" + ma20TrendPct
                + ", ma20TrendDirection=" + ma20TrendDirection
                + ", extremeCounterTrendStrongBreakoutRatio="
                + config.getExtremeCounterTrendStrongBreakoutRatio()
                + ", extremeCounterTrendTightStopLossPct=" + config.getExtremeCounterTrendStopLossPct()
                + ", extremeCounterTrendWideStopLossPct=" + config.getExtremeCounterTrendWideStopLossPct()
                + ", flatMa20StopLossPct=" + config.getFlatMa20StopLossPct()
                + ", protectiveStopPrice=" + state.getProtectiveStopPrice()
                + ", protectiveStopSource=" + state.getProtectiveStopSource()
                + ", profitExtensionActive=" + profitExtensionActive
                + ", profitExtensionReverseGap=" + profitExtensionReverseGap
                + ", profitExtensionMinHoldBars=" + config.getProfitableReverseFilterHoldBars()
                + ", profitExtensionActivationPct=" + config.getProfitGivebackActivationPct()
                + ", profitExtensionFloorPct=" + config.getProfitableReverseFilterMinProfitPct()
                + ", profitExtensionReverseGapMin=" + config.getProfitableReverseDifDeaGapMin();
    }

    /**
     * 鎵撳嵃鍘熷K绾挎暟鎹紝渚夸簬鏍稿鍥炴祴杈撳叆銆?     */
    private void logRawKline(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        if (currentOhlc == null) {
            log.info("difDeaLifecycle raw kline, symbol:{}, text:{}, currentOhlc:null", symbol, text);
            return;
        }
        log.info("difDeaLifecycle raw kline, symbol:{}, text:{}, seriesBarCount:{}, seriesEndIndex:{}, "
                        + "securityid:{}, ohlcText:{}, starttime:{}, endtime:{}, fmttime:{}, inf1:{}, "
                        + "open:{}, high:{}, low:{}, close:{}, volume:{}",
                symbol,
                text,
                series == null ? 0 : series.getBarCount(),
                series == null ? -1 : series.getEndIndex(),
                currentOhlc.securityid,
                currentOhlc.text,
                currentOhlc.starttime,
                readOptionalField(currentOhlc, "endtime"),
                readOptionalField(currentOhlc, "fmttime"),
                readOptionalField(currentOhlc, "inf1"),
                currentOhlc.open,
                currentOhlc.high,
                currentOhlc.low,
                currentOhlc.close,
                currentOhlc.volume);
    }

    /**
     * 鎵撳嵃绛栫暐瀹為檯浣跨敤鐨勬寚鏍囧寲K绾垮拰鍐崇瓥缁撴灉銆?     */
    private void logDecisionKline(String symbol,
                                  String text,
                                  LifecycleConfig config,
                                  LifecycleIndicatorSample latest,
                                  LifecycleState state,
                                  LifecycleDecision decision,
                                  int recentCrossCount,
                                  double breakoutConfirmRatio,
                                  boolean reverseBlockedByProfitableWeakCross,
                                  double entrySignalHigh,
                                  double entrySignalLow,
                                  double entryMacdStrength,
                                  double currentMacdRetentionRatio,
                                  boolean earlyFailureWindow,
                                  double maxFavorableProgressPct,
                                  double currentProgressPct,
                                  boolean entrySignalBoundaryInvalidated,
                                  double ma20TrendPct,
                                  LifecycleDirection ma20TrendDirection,
                                  boolean profitExtensionActive,
                                  double profitExtensionReverseGap) {
        log.info("difDeaLifecycle decision kline, symbol:{}, text:{}, index:{}, barTime:{}, "
                        + "open:{}, high:{}, low:{}, close:{}, dif:{}, dea:{}, macd:{}, ma10:{}, ma20:{}, "
                        + "barRangePct:{}, phase:{}, direction:{}, decision:{}, reason:{}, reverseEntry:{}, "
                        + "holdBars:{}, entryPrice:{}, entrySource:{}, cooldownUntilIndex:{}, inCooldown:{}, "
                        + "recentCrossCount:{}, crossDensityLookbackBars:{}, pendingEntryDirection:{}, "
                        + "pendingEntryIndex:{}, pendingEntryAge:{}, pendingReverseDirection:{}, "
                        + "pendingReverseIndex:{}, pendingReverseAge:{}, pendingReverseSource:{}, "
                        + "breakoutConfirmRatio:{}, minBreakoutConfirmRatio:{}, "
                        + "reverseBlockedByProfitableWeakCross:{}, entrySignalHigh:{}, entrySignalLow:{}, "
                        + "entryMacdStrength:{}, currentMacdRetentionRatio:{}, earlyFailureWindow:{}, "
                        + "maxFavorableProgressPct:{}, currentProgressPct:{}, profitGivebackActivationPct:{}, "
                        + "earlyProfitRoundTripMaxHoldBars:{}, matureProfitGivebackMinHoldBars:{}, "
                        + "matureProfitRetainedPct:{}, matureProfitMacdRetentionRatio:{}, "
                        + "entrySignalBoundaryInvalidated:{}, "
                        + "ma20TrendPct:{}, ma20TrendDirection:{}, ma20TrendLookbackBars:{}, "
                        + "ma20TrendThresholdPct:{}, extremeCounterTrendThresholdPct:{}, "
                        + "extremeCounterTrendStrongBreakoutRatio:{}, extremeCounterTrendTightStopLossPct:{}, "
                        + "extremeCounterTrendWideStopLossPct:{}, flatMa20StopLossPct:{}, "
                        + "protectiveStopPrice:{}, protectiveStopSource:{}, "
                        + "profitExtensionActive:{}, profitExtensionReverseGap:{}, "
                        + "profitExtensionMinHoldBars:{}, profitExtensionActivationPct:{}, "
                        + "profitExtensionFloorPct:{}, profitExtensionReverseGapMin:{}",
                symbol,
                text,
                latest.getIndex(),
                latest.getEndTime(),
                latest.getOpen(),
                latest.getHigh(),
                latest.getLow(),
                latest.getClose(),
                latest.getDif(),
                latest.getDea(),
                latest.getMacdBar(),
                latest.getMa10(),
                latest.getMa20(),
                decisionEngine.calculateBarRangePct(latest),
                state.getPhase(),
                state.getDirection(),
                decision.getType(),
                decision.getReason(),
                decision.isReverseEntryAllowed(),
                currentHoldBars(state, latest),
                state.getEntryPrice(),
                state.getEntrySource(),
                state.getCooldownUntilIndex(),
                isInCooldown(state, latest),
                recentCrossCount,
                config.getCrossDensityLookbackBars(),
                state.getPendingEntryDirection(),
                state.getPendingEntryIndex(),
                decisionEngine.pendingEntryAge(state, latest),
                state.getPendingReverseDirection(),
                state.getPendingReverseIndex(),
                decisionEngine.pendingReverseAge(state, latest),
                state.getPendingReverseSource(),
                breakoutConfirmRatio,
                config.getMinBreakoutConfirmRatio(),
                reverseBlockedByProfitableWeakCross,
                entrySignalHigh,
                entrySignalLow,
                entryMacdStrength,
                currentMacdRetentionRatio,
                earlyFailureWindow,
                maxFavorableProgressPct,
                currentProgressPct,
                config.getProfitGivebackActivationPct(),
                config.getEarlyProfitRoundTripMaxHoldBars(),
                config.getMatureProfitGivebackMinHoldBars(),
                config.getMatureProfitRetainedPct(),
                config.getMatureProfitMacdRetentionRatio(),
                entrySignalBoundaryInvalidated,
                ma20TrendPct,
                ma20TrendDirection,
                config.getMa20TrendLookbackBars(),
                config.getMa20TrendThresholdPct(),
                config.getExtremeCounterTrendThresholdPct(),
                config.getExtremeCounterTrendStrongBreakoutRatio(),
                config.getExtremeCounterTrendStopLossPct(),
                config.getExtremeCounterTrendWideStopLossPct(),
                config.getFlatMa20StopLossPct(),
                state.getProtectiveStopPrice(),
                state.getProtectiveStopSource(),
                profitExtensionActive,
                profitExtensionReverseGap,
                config.getProfitableReverseFilterHoldBars(),
                config.getProfitGivebackActivationPct(),
                config.getProfitableReverseFilterMinProfitPct(),
                config.getProfitableReverseDifDeaGapMin());
    }

    /**
     * 璁＄畻褰撳墠鎸佷粨鏍规暟銆?     */
    private int currentHoldBars(LifecycleState state, LifecycleIndicatorSample latest) {
        if (state == null || latest == null || state.getEntryIndex() < 0) {
            return 0;
        }
        return Math.max(0, latest.getIndex() - state.getEntryIndex());
    }

    /**
     * 鍒ゆ柇褰撳墠鏍锋湰鏄惁浠嶅湪鐭寔浠撳弽澶嶄氦鍙夊喎鍗存湡鍐呫€?     */
    private boolean isInCooldown(LifecycleState state, LifecycleIndicatorSample latest) {
        return state != null && latest != null && state.getCooldownUntilIndex() >= latest.getIndex();
    }

    /**
     * 鍒ゆ柇褰撳墠鏍锋湰鏄惁浠嶅湪浜ゅ弶瀵嗗害鍐峰嵈鏈熷唴銆?     */
    /**
     * 璇诲彇TTbookOhlc鍙€夊瓧娈碉紝鍏煎涓嶅悓渚濊禆鐗堟湰銆?     */
    private String readOptionalField(TTbookOhlc currentOhlc, String fieldName) {
        if (currentOhlc == null || StringUtils.isBlank(fieldName)) {
            return "";
        }
        try {
            java.lang.reflect.Field field = currentOhlc.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(currentOhlc);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignore) {
            return "";
        }
    }

    /**
     * 璁板綍鏈氦鏄撳師鍥犮€?     */
    private void reject(String symbol, String reason) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedReason = StringUtils.defaultIfBlank(reason, "unknown");
        rejectStats.computeIfAbsent(normalizedSymbol, key -> new ConcurrentHashMap<String, Integer>())
                .merge(normalizedReason, 1, Integer::sum);
    }

    /**
     * 鐢熸垚鍐呭瓨鐘舵€侀敭銆?     */
    private String key(String symbol, String text) {
        return normalizeSymbol(symbol) + "|" + normalizeText(text);
    }

    /**
     * 鏍囧噯鍖栧搧绉嶃€?     */
    private String normalizeSymbol(String symbol) {
        return StringUtils.trimToEmpty(symbol).toUpperCase(Locale.ROOT);
    }

    /**
     * 鏍囧噯鍖栧懆鏈熴€?     */
    private String normalizeText(String text) {
        return StringUtils.trimToEmpty(text).toUpperCase(Locale.ROOT);
    }
}
