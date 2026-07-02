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

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将 DIF/DEA 生命周期决策适配为 simulation 回测框架可执行的买卖信号。
 */
@Slf4j
@Service("difDeaLifecycle")
public class DifDeaLifecycleBacktestStrategy implements BinanceBacktestStrategy {

    private static final int MIN_SIGNAL_BARS = 35;
    private static final int MACD_WINDOW = 50;
    private final LifecycleIndicatorCalculator indicatorCalculator = new LifecycleIndicatorCalculator(MACD_WINDOW);
    private final DifDeaLifecycleDecisionEngine decisionEngine;
    private final LifecycleConfigProvider configProvider;
    private final Map<String, LifecycleState> stateMap = new ConcurrentHashMap<String, LifecycleState>();
    private final Map<String, Map<String, Integer>> rejectStats = new ConcurrentHashMap<String, Map<String, Integer>>();

    /**
     * 注入生命周期决策引擎和配置提供器。
     */
    public DifDeaLifecycleBacktestStrategy(DifDeaLifecycleDecisionEngine decisionEngine,
                                           LifecycleConfigProvider configProvider) {
        this.decisionEngine = decisionEngine;
        this.configProvider = configProvider;
    }

    @Override
    /**
     * 返回回测策略注册名称。
     */
    public String getName() {
        return DifDeaLifecycleDecisionEngine.STRATEGY_NAME;
    }

    @Override
    /**
     * 对当前 K 线执行生命周期判断并生成回测信号。
     */
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedText = normalizeText(text);
        if (!"5M".equals(normalizedText)) {
            reject(normalizedSymbol, "unsupported_text");
            return signal;
        }

        LifecycleConfig config = configProvider.getConfig(normalizedSymbol, normalizedText);
        List<LifecycleIndicatorSample> samples = indicatorCalculator.calculate(series, config);
        if (series == null || series.getBarCount() < MIN_SIGNAL_BARS || samples.size() < MIN_SIGNAL_BARS) {
            reject(normalizedSymbol, "not_enough_warmup_bars");
            return signal;
        }

        LifecycleIndicatorSample latest = samples.get(samples.size() - 1);
        LifecycleState state = stateMap.computeIfAbsent(key(normalizedSymbol, normalizedText),
                v -> new LifecycleState(getName(), normalizedSymbol, normalizedText));
        if (latest.getEndTime().equals(state.getLastBarTime())) {
            reject(normalizedSymbol, "duplicate_bar");
            return signal;
        }

        LifecycleContext context = new LifecycleContext(getName(), normalizedSymbol, normalizedText, samples, config, state);
        LifecycleDecision decision = decisionEngine.decide(context);
        String decisionTrace = decisionEngine.getLastDecisionTrace();
        updateState(state, latest, decision);
        logBarMetrics(normalizedSymbol, normalizedText, samples, latest, state, decision, decisionTrace);
        if (isReverseEntryBlocked(decision)) {
            reject(normalizedSymbol, "reverse_entry_blocked_by_range_compression");
        }

        if (!decision.hasAction()) {
            reject(normalizedSymbol, decision.getReason());
            return signal;
        }

        applyDecision(signal, decision);
        signal.algoName = getName();
        signal.remark = buildRemark(decision, samples, latest, state, decisionTrace);
        return signal;
    }

    @Override
    /**
     * 重置指定品种的拒绝统计和生命周期状态。
     */
    public void resetRejectStats(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        rejectStats.remove(normalizedSymbol);
        stateMap.keySet().removeIf(key -> key.startsWith(normalizedSymbol + "|"));
    }

    @Override
    /**
     * 获取指定品种的拒绝原因统计快照。
     */
    public Map<String, Integer> snapshotRejectStats(String symbol) {
        Map<String, Integer> stats = rejectStats.get(normalizeSymbol(symbol));
        if (stats == null) {
            return new LinkedHashMap<String, Integer>();
        }
        return new LinkedHashMap<String, Integer>(stats);
    }

    /**
     * 将生命周期动作映射为 BUY/SELL 回测信号。
     */
    private void applyDecision(Signal signal, LifecycleDecision decision) {
        if (decision.getType() == LifecycleDecisionType.ENTER_LONG) {
            signal.side = Side.BUY;
            signal.ocType = OCType.OPEN;
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_LONG) {
            signal.side = Side.SELL;
            signal.ocType = OCType.ClOSE;
        } else if (decision.getType() == LifecycleDecisionType.ENTER_SHORT) {
            signal.side = Side.SELL;
            signal.ocType = OCType.OPEN;
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_SHORT) {
            signal.side = Side.BUY;
            signal.ocType = OCType.ClOSE;
        }
    }

    /**
     * 根据本次决策推进生命周期状态。
     */
    private void updateState(LifecycleState state, LifecycleIndicatorSample latest, LifecycleDecision decision) {
        state.setLastBarTime(latest.getEndTime());
        state.setLastDecision(decision.getType().name());
        state.setLastReason(decision.getReason());

        if (decision.getType() == LifecycleDecisionType.PENDING_LONG_LAUNCH) {
            boolean keepSamePending = state.inPendingLong();
            if (state.inPendingLong()) {
                state.continuePendingRange(latest, decisionEngine.getPendingRangeBuildBars());
            } else {
                state.startPendingRange(latest, decisionEngine.getPendingRangeBuildBars());
            }
            state.setPhase(LifecyclePhase.PENDING_LONG_LAUNCH);
            state.setDirection(LifecycleDirection.LONG);
            state.setPendingSource(resolvePendingSource(state, decision, keepSamePending));
        } else if (decision.getType() == LifecycleDecisionType.PENDING_SHORT_LAUNCH) {
            boolean keepSamePending = state.inPendingShort();
            if (state.inPendingShort()) {
                state.continuePendingRange(latest, decisionEngine.getPendingRangeBuildBars());
            } else {
                state.startPendingRange(latest, decisionEngine.getPendingRangeBuildBars());
            }
            state.setPhase(LifecyclePhase.PENDING_SHORT_LAUNCH);
            state.setDirection(LifecycleDirection.SHORT);
            state.setPendingSource(resolvePendingSource(state, decision, keepSamePending));
        } else if (decision.getType() == LifecycleDecisionType.ENTER_LONG) {
            state.setPhase(LifecyclePhase.LONG_ACTIVE);
            state.setDirection(LifecycleDirection.LONG);
            state.clearPending();
        } else if (decision.getType() == LifecycleDecisionType.ENTER_SHORT) {
            state.setPhase(LifecyclePhase.SHORT_ACTIVE);
            state.setDirection(LifecycleDirection.SHORT);
            state.clearPending();
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_LONG
                && "dif_dea_cross_down".equals(decision.getReason())
                && decision.isReverseEntryAllowed()) {
            state.setPhase(LifecyclePhase.SHORT_ACTIVE);
            state.setDirection(LifecycleDirection.SHORT);
            state.clearPending();
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_SHORT
                && "dif_dea_cross_up".equals(decision.getReason())
                && decision.isReverseEntryAllowed()) {
            state.setPhase(LifecyclePhase.LONG_ACTIVE);
            state.setDirection(LifecycleDirection.LONG);
            state.clearPending();
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_LONG
                && "macd_shrinking_and_close_weakening".equals(decision.getReason())) {
            state.setPhase(LifecyclePhase.PENDING_LONG_LAUNCH);
            state.setDirection(LifecycleDirection.LONG);
            state.startPendingRange(latest, decisionEngine.getPendingRangeBuildBars());
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_SHORT
                && "macd_recovering_and_close_strengthening".equals(decision.getReason())) {
            state.setPhase(LifecyclePhase.PENDING_SHORT_LAUNCH);
            state.setDirection(LifecycleDirection.SHORT);
            state.startPendingRange(latest, decisionEngine.getPendingRangeBuildBars());
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_LONG
                || decision.getType() == LifecycleDecisionType.LEAVE_SHORT) {
            state.setPhase(LifecyclePhase.NEUTRAL);
            state.setDirection(LifecycleDirection.NONE);
            state.clearPending();
        } else if (state.inPendingLaunch()
                && ("pending_launch_invalidated_by_reverse_cross".equals(decision.getReason())
                || "pending_long_launch_expired".equals(decision.getReason())
                || "pending_short_launch_expired".equals(decision.getReason())
                || "pending_launch_replaced_by_new_long_cycle".equals(decision.getReason())
                || "pending_launch_replaced_by_new_short_cycle".equals(decision.getReason()))) {
            state.setPhase(LifecyclePhase.NEUTRAL);
            state.setDirection(LifecycleDirection.NONE);
            state.clearPending();
        }
    }

    /**
     * 构造回测报告中可追踪的信号说明。
     */
    private String buildRemark(LifecycleDecision decision,
                               List<LifecycleIndicatorSample> samples,
                               LifecycleIndicatorSample latest,
                               LifecycleState state,
                               String decisionTrace) {
        return getName()
                + " decision=" + decision.getType()
                + ", reason=" + decision.getReason()
                + ", reverseEntry=" + decision.isReverseEntryAllowed()
                + ", phase=" + state.getPhase()
                + ", barTime=" + formatBarTime(latest.getEndTime())
                + ", open=" + latest.getOpen()
                + ", high=" + latest.getHigh()
                + ", low=" + latest.getLow()
                + ", close=" + latest.getClose()
                + ", dif=" + latest.getDif()
                + ", dea=" + latest.getDea()
                + ", macd=" + latest.getMacdBar()
                + ", ma5=" + latest.getMa5()
                + ", ma10=" + latest.getMa10()
                + ", stddev=" + latest.getCloseStddev()
                + ", donchianHigh=" + latest.getDonchianHigh()
                + ", donchianLow=" + latest.getDonchianLow()
                + ", donchianWidth=" + latest.getDonchianWidth()
                + ", recentCrossCount=" + latest.getRecentCrossCount()
                + ", barRangePct=" + decisionEngine.calculateBarRangePct(latest)
                + ", rangeCompressed=" + isRangeCompressed(samples, latest, configProvider.getConfig(state.getSymbol(), state.getText()))
                + ", maCounterTrendBlocked=" + isMaCounterTrendBlocked(samples, decision)
                + ", ma5Up=" + decisionEngine.isMa5Up(samples)
                + ", ma5Down=" + decisionEngine.isMa5Down(samples)
                + ", ma10Down3=" + decisionEngine.isMa10Down(samples)
                + ", ma10Up3=" + decisionEngine.isMa10Up(samples)
                + ", adx=" + latest.getAdx()
                + ", pendingBars=" + state.getPendingBars()
                + ", pendingRangeReady=" + state.isPendingRangeReady()
                + ", pendingBuildBars=" + decisionEngine.getPendingRangeBuildBars()
                + ", pendingSource=" + state.getPendingSource()
                + ", pendingExpired=" + isPendingExpired(decision)
                + ", pendingMaxBars=" + decisionEngine.getLowMacdPendingMaxBars()
                + ", pendingHighClose=" + state.getPendingHighClose()
                + ", pendingLowClose=" + state.getPendingLowClose()
                + ", pendingUpperBodyBound=" + state.getPendingUpperBodyBound()
                + ", pendingLowerBodyBound=" + state.getPendingLowerBodyBound()
                + ", pendingBreakoutPassed=" + isPendingBreakoutPassed(latest, state)
                + ", trace=" + StringUtils.defaultString(decisionTrace);
    }

    /**
     * 按收线时刻打印当前 K 线的 DIF、DEA、MACD 和决策结果，便于回测逐根对照。
     */
    private void logBarMetrics(String symbol,
                               String text,
                               List<LifecycleIndicatorSample> samples,
                               LifecycleIndicatorSample latest,
                               LifecycleState state,
                               LifecycleDecision decision,
                               String decisionTrace) {
        LifecycleConfig config = configProvider.getConfig(symbol, text);
        log.info("difDeaLifecycle bar, symbol:{}, text:{}, barTime:{}, open:{}, high:{}, low:{}, close:{}, dif:{}, dea:{}, macd:{}, ma5:{}, ma10:{}, " +
                        "stddev:{}, donchianHigh:{}, donchianLow:{}, donchianWidth:{}, recentCrossCount:{}, " +
                        "barRangePct:{}, rangeCompressed:{}, maCounterTrendBlocked:{}, ma5Up:{}, ma5Down:{}, ma10Down3:{}, ma10Up3:{}, adx:{}, phase:{}, decision:{}, reason:{}, reverseEntry:{}, " +
                        "pendingBars:{}, pendingRangeReady:{}, pendingBuildBars:{}, pendingSource:{}, pendingExpired:{}, pendingMaxBars:{}, pendingHighClose:{}, pendingLowClose:{}, " +
                        "pendingUpperBodyBound:{}, pendingLowerBodyBound:{}, pendingBreakoutPassed:{}, trace:{}",
                symbol,
                text,
                formatBarTime(latest.getEndTime()),
                latest.getOpen(),
                latest.getHigh(),
                latest.getLow(),
                latest.getClose(),
                latest.getDif(),
                latest.getDea(),
                latest.getMacdBar(),
                latest.getMa5(),
                latest.getMa10(),
                latest.getCloseStddev(),
                latest.getDonchianHigh(),
                latest.getDonchianLow(),
                latest.getDonchianWidth(),
                latest.getRecentCrossCount(),
                decisionEngine.calculateBarRangePct(latest),
                isRangeCompressed(samples, latest, config),
                isMaCounterTrendBlocked(samples, decision),
                decisionEngine.isMa5Up(samples),
                decisionEngine.isMa5Down(samples),
                decisionEngine.isMa10Down(samples),
                decisionEngine.isMa10Up(samples),
                latest.getAdx(),
                state.getPhase(),
                decision.getType(),
                decision.getReason(),
                decision.isReverseEntryAllowed(),
                state.getPendingBars(),
                state.isPendingRangeReady(),
                decisionEngine.getPendingRangeBuildBars(),
                state.getPendingSource(),
                isPendingExpired(decision),
                decisionEngine.getLowMacdPendingMaxBars(),
                state.getPendingHighClose(),
                state.getPendingLowClose(),
                state.getPendingUpperBodyBound(),
                state.getPendingLowerBodyBound(),
                isPendingBreakoutPassed(latest, state),
                StringUtils.defaultString(decisionTrace));
    }

    /**
     * 判断当前指标样本是否处于低MACD蓄势状态。
     */
    private boolean isRangeCompressed(List<LifecycleIndicatorSample> samples,
                                      LifecycleIndicatorSample latest,
                                      LifecycleConfig config) {
        if (latest == null || config == null || !config.isCompressionFilterEnabled()) {
            return false;
        }
        int hitCount = 0;
        if (samples != null && samples.size() >= 5) {
            for (int i = samples.size() - 5; i < samples.size(); i++) {
                double macd = samples.get(i).getMacdBar();
                if (!Double.isNaN(macd) && Math.abs(macd) < 0.5d) {
                    hitCount++;
                }
            }
        }
        return hitCount >= 4 || latest.getRecentCrossCount() >= config.getCrossCountThreshold();
    }

    /**
     * 判断当前拒绝原因是否命中高一级均线趋势未翻转过滤。
     */
    private boolean isMaCounterTrendBlocked(List<LifecycleIndicatorSample> samples, LifecycleDecision decision) {
        if (decision == null) {
            return false;
        }
        if ("entry_blocked_by_ma_counter_trend".equals(decision.getReason())) {
            return true;
        }
        if (decision.getType() == LifecycleDecisionType.ENTER_LONG
                || decision.getType() == LifecycleDecisionType.LEAVE_SHORT) {
            return decisionEngine.isLongEntryBlockedByMaCounterTrend(samples);
        }
        if (decision.getType() == LifecycleDecisionType.ENTER_SHORT
                || decision.getType() == LifecycleDecisionType.LEAVE_LONG) {
            return decisionEngine.isShortEntryBlockedByMaCounterTrend(samples);
        }
        return false;
    }

    /**
     * 判断反向交叉平仓后是否因为震荡而禁止立即反手。
     */
    private boolean isReverseEntryBlocked(LifecycleDecision decision) {
        if (decision == null || decision.isReverseEntryAllowed()) {
            return false;
        }
        return (decision.getType() == LifecycleDecisionType.LEAVE_LONG
                && "dif_dea_cross_down".equals(decision.getReason()))
                || (decision.getType() == LifecycleDecisionType.LEAVE_SHORT
                && "dif_dea_cross_up".equals(decision.getReason()));
    }

    /**
     * 判断本次 pending 是否因为低MACD压缩观察超时而失效。
     */
    private boolean isPendingExpired(LifecycleDecision decision) {
        if (decision == null) {
            return false;
        }
        return "pending_long_launch_expired".equals(decision.getReason())
                || "pending_short_launch_expired".equals(decision.getReason());
    }

    /**
     * 判断当前 K 线是否已经突破冻结后的 pending 实体边界。
     */
    private boolean isPendingBreakoutPassed(LifecycleIndicatorSample latest, LifecycleState state) {
        if (latest == null || state == null || !state.isPendingRangeReady()) {
            return false;
        }
        if (state.inPendingLong()) {
            return !Double.isNaN(state.getPendingUpperBodyBound())
                    && latest.getClose() > state.getPendingUpperBodyBound();
        }
        if (state.inPendingShort()) {
            return !Double.isNaN(state.getPendingLowerBodyBound())
                    && latest.getClose() < state.getPendingLowerBodyBound();
        }
        return false;
    }

    /**
     * 根据本次决策和已有状态推断 pending 观察态来源。
     */
    private PendingSource resolvePendingSource(LifecycleState state,
                                               LifecycleDecision decision,
                                               boolean keepSamePending) {
        if (decision == null) {
            return keepSamePending && state != null ? state.getPendingSource() : PendingSource.OTHER;
        }
        if (isLowMacdPendingReason(decision.getReason())) {
            return PendingSource.LOW_MACD_ENTRY;
        }
        if (keepSamePending && state != null && state.getPendingSource() != PendingSource.NONE) {
            return state.getPendingSource();
        }
        return PendingSource.OTHER;
    }

    /**
     * 判断当前 pending 原因是否属于低MACD压缩观察来源。
     */
    private boolean isLowMacdPendingReason(String reason) {
        return "entry_blocked_by_low_macd".equals(reason);
    }

    /**
     * 将带时区的收线时间压缩为本地时间字符串，便于日志阅读。
     */
    private String formatBarTime(String endTime) {
        if (StringUtils.isBlank(endTime)) {
            return endTime;
        }
        try {
            return ZonedDateTime.parse(endTime).toLocalDateTime().toString();
        } catch (Exception ignore) {
            return endTime;
        }
    }

    /**
     * 记录未产生交易动作的原因。
     */
    private void reject(String symbol, String reason) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedReason = StringUtils.defaultIfBlank(reason, "unknown");
        rejectStats.computeIfAbsent(normalizedSymbol, v -> new ConcurrentHashMap<String, Integer>())
                .merge(normalizedReason, 1, Integer::sum);
    }

    /**
     * 生成状态缓存键。
     */
    private String key(String symbol, String text) {
        return normalizeSymbol(symbol) + "|" + normalizeText(text);
    }

    /**
     * 标准化交易品种。
     */
    private String normalizeSymbol(String symbol) {
        return StringUtils.trimToEmpty(symbol).toUpperCase(Locale.ROOT);
    }

    /**
     * 标准化 K 线周期。
     */
    private String normalizeText(String text) {
        return StringUtils.trimToEmpty(text).toUpperCase(Locale.ROOT);
    }
}
