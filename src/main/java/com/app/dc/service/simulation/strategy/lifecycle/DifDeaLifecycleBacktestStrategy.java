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

        List<LifecycleIndicatorSample> samples = indicatorCalculator.calculate(series);
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

        LifecycleConfig config = configProvider.getConfig(normalizedSymbol, normalizedText);
        LifecycleContext context = new LifecycleContext(getName(), normalizedSymbol, normalizedText, samples, config, state);
        LifecycleDecision decision = decisionEngine.decide(context);
        updateState(state, latest, decision);
        logBarMetrics(normalizedSymbol, normalizedText, latest, state, decision);

        if (!decision.hasAction()) {
            reject(normalizedSymbol, decision.getReason());
            return signal;
        }

        applyDecision(signal, decision);
        signal.algoName = getName();
        signal.remark = buildRemark(decision, latest, state);
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

        if (decision.getType() == LifecycleDecisionType.ENTER_LONG) {
            state.setPhase(LifecyclePhase.LONG_ACTIVE);
            state.setDirection(LifecycleDirection.LONG);
        } else if (decision.getType() == LifecycleDecisionType.ENTER_SHORT) {
            state.setPhase(LifecyclePhase.SHORT_ACTIVE);
            state.setDirection(LifecycleDirection.SHORT);
        } else if (decision.getType() == LifecycleDecisionType.LEAVE_LONG
                || decision.getType() == LifecycleDecisionType.LEAVE_SHORT) {
            state.setPhase(LifecyclePhase.NEUTRAL);
            state.setDirection(LifecycleDirection.NONE);
        }
    }

    /**
     * 构造回测报告中可追踪的信号说明。
     */
    private String buildRemark(LifecycleDecision decision, LifecycleIndicatorSample latest, LifecycleState state) {
        return getName()
                + " decision=" + decision.getType()
                + ", reason=" + decision.getReason()
                + ", phase=" + state.getPhase()
                + ", barTime=" + formatBarTime(latest.getEndTime())
                + ", dif=" + latest.getDif()
                + ", dea=" + latest.getDea()
                + ", macd=" + latest.getMacdBar()
                + ", close=" + latest.getClose();
    }

    /**
     * 按收线时刻打印当前 K 线的 DIF、DEA、MACD 和决策结果，便于回测逐根对照。
     */
    private void logBarMetrics(String symbol,
                               String text,
                               LifecycleIndicatorSample latest,
                               LifecycleState state,
                               LifecycleDecision decision) {
        log.info("difDeaLifecycle bar, symbol:{}, text:{}, barTime:{}, close:{}, dif:{}, dea:{}, macd:{}, phase:{}, decision:{}, reason:{}",
                symbol,
                text,
                formatBarTime(latest.getEndTime()),
                latest.getClose(),
                latest.getDif(),
                latest.getDea(),
                latest.getMacdBar(),
                state.getPhase(),
                decision.getType(),
                decision.getReason());
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
