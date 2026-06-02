package com.app.dc.service.simulation.runtime;

import com.app.common.utils.IdUtil;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.signal.StrategyParametersSupport;
import com.app.dc.service.simulation.BacktestModels;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class StrategyAutoPublishService {

    private static final DateTimeFormatter CLICKHOUSE_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${strategy.auto.publish.enabled:true}")
    private boolean enabled;

    @Value("${strategy.auto.publish.source:simulation_auto_publish}")
    private String publishSource;

    @Value("${strategy.auto.publish.minValidateTrades:5}")
    private int minValidateTrades;

    @Value("${strategy.auto.publish.maxValidateDrawdownPct:0.30}")
    private double maxValidateDrawdownPct;

    @Value("${strategy.auto.publish.minValidateProfitFactor:1.05}")
    private double minValidateProfitFactor;

    @Autowired
    private StrategyAutoPublishDao strategyAutoPublishDao;

    public StrategyAutoPublishDecision maybePublish(StrategyBacktestTaskRow task,
                                                    StrategyCandidateRow candidate,
                                                    BacktestModels.BacktestResponse response) {
        StrategyAutoPublishDecision decision = new StrategyAutoPublishDecision();
        decision.published = false;
        decision.action = "SKIP";
        if (!enabled) {
            decision.reason = "auto publish disabled";
            return decision;
        }
        if (candidate == null) {
            decision.reason = "candidate missing";
            return decision;
        }
        if (StringUtils.isBlank(candidate.description)) {
            decision.reason = "candidate description is blank";
            return decision;
        }

        try {
            StrategyBacktestSummary current = summarizeCurrent(task, candidate, response);
            decision.currentTotalPnl = current.totalPnl;
            decision.currentValidatePnl = current.validatePnl;
            decision.currentForwardPnl = current.forwardPnl;
            decision.currentForwardScore = current.forwardScore;
            decision.currentValidatePrimaryScore = current.validatePrimaryScore;
            decision.currentFeeAdjustedValidatePnl = current.feeAdjustedValidatePnl;

            if (!StringUtils.equalsIgnoreCase(current.windowMode, "WALK_FORWARD")) {
                decision.reason = "window_mode is not WALK_FORWARD";
                return decision;
            }
            if (!gt(current.sliceCount == null ? 0D : current.sliceCount.doubleValue(), 2D)) {
                decision.reason = "slice_count < 3";
                return decision;
            }
            if (!isTrue(current.overfitPass)) {
                decision.reason = StringUtils.isBlank(current.overfitReason)
                        ? "overfit gate not passed"
                        : current.overfitReason;
                return decision;
            }
            if (!isTrue(current.oosPass)) {
                decision.reason = "oos gate not passed";
                return decision;
            }
            if (current.resultCount != null && current.resultCount.intValue() > 1
                    && "{}".equals(StringUtils.trimToEmpty(current.bestParamSetJson))) {
                decision.reason = "publishable best param set missing for multi-symbol result";
                return decision;
            }
            if (!gt(preferredValidateScore(current), 0D)) {
                decision.reason = "validate primary score <= 0";
                return decision;
            }
            if (!gte(current.validateTradeCount == null ? 0D : current.validateTradeCount.doubleValue(), (double) Math.max(1, minValidateTrades))) {
                decision.reason = "validate trade count below threshold";
                return decision;
            }
            if (!lte(current.validateMaxDrawdownPct, maxValidateDrawdownPct)) {
                decision.reason = "validate drawdown above threshold";
                return decision;
            }
            if (!gte(current.validateProfitFactor, minValidateProfitFactor)) {
                decision.reason = "validate profit factor below threshold";
                return decision;
            }
            if (!gt(preferredFeeAdjustedValidate(current), 0D)) {
                decision.reason = "fee adjusted validate pnl <= 0";
                return decision;
            }
            if (!gt(current.forwardScore, 0D)) {
                decision.reason = "forward_score <= 0";
                return decision;
            }
            if (!gte(current.forwardPnl, 0D)) {
                decision.reason = "forward_pnl < 0";
                return decision;
            }
            if (!gte(forwardContribution(current.forwardPnl, current.totalPnl), current.minForwardContribution)) {
                decision.reason = "forward contribution below threshold";
                return decision;
            }
            if (isTrue(current.fragileBest)) {
                decision.reason = "fragile best param";
                return decision;
            }

            StrategyLiveRegistryPublishRow active = strategyAutoPublishDao.loadCurrentActive(candidate.strategyName);
            StrategyLiveRegistryPublishRow baselineRow = active;
            boolean baselineMigration = StringUtils.equalsIgnoreCase(candidate.generationType, "LIVE_BASELINE_MIGRATION");
            boolean reviewEvolution = StringUtils.equalsIgnoreCase(candidate.generationType, "REVIEW_EVOLUTION");
            if (baselineRow == null && (reviewEvolution || baselineMigration)) {
                baselineRow = strategyAutoPublishDao.loadLatestLiveBaseline(candidate.strategyName);
            }

            if (baselineRow == null) {
                publish(decision, task, candidate, null, current, "PROMOTE",
                        "promote profitable walk-forward first version");
                return decision;
            }

            decision.baselineVersion = baselineRow.strategyVersion;
            if (StringUtils.equalsIgnoreCase(baselineRow.strategyVersion, candidate.strategyVersion)) {
                decision.reason = active == null
                        ? "same version already latest live baseline"
                        : "same version already active";
                return decision;
            }

            StrategyBacktestSummary baseline = strategyAutoPublishDao
                    .loadLatestSummary(candidate.strategyName, baselineRow.strategyVersion);
            if (baseline == null) {
                decision.reason = active == null
                        ? "historical live baseline backtest summary missing"
                        : "baseline backtest summary missing";
                return decision;
            }
            decision.baselineTotalPnl = baseline.totalPnl;
            decision.baselineValidatePnl = baseline.validatePnl;
            decision.baselineForwardPnl = baseline.forwardPnl;
            decision.baselineForwardScore = baseline.forwardScore;
            decision.baselineValidatePrimaryScore = baseline.validatePrimaryScore;

            if (!gt(current.forwardScore, baseline.forwardScore)) {
                decision.reason = active == null
                        ? "forward_score not better than latest live baseline"
                        : "forward_score not better than active baseline";
                return decision;
            }
            if (!gt(preferredValidateScore(current), preferredValidateScore(baseline))) {
                decision.reason = active == null
                        ? "validate score not better than latest live baseline"
                        : "validate score not better than active baseline";
                return decision;
            }

            publish(decision, task, candidate, baselineRow, current, "REPLACE",
                    active == null
                            ? "replace latest live baseline after review evolution"
                            : "replace active version with stronger backtest result");
            return decision;
        } catch (Exception e) {
            log.error("StrategyAutoPublishService maybePublish error, strategy:{}@{}",
                    candidate.strategyName, candidate.strategyVersion, e);
            decision.reason = "auto publish error: " + e.getMessage();
            return decision;
        }
    }

    private void publish(StrategyAutoPublishDecision decision,
                         StrategyBacktestTaskRow task,
                         StrategyCandidateRow candidate,
                         StrategyLiveRegistryPublishRow active,
                         StrategyBacktestSummary current,
                         String eventType,
                         String reason) {
        String now = nowString();
        strategyAutoPublishDao.retireActive(candidate.strategyName, candidate.strategyVersion, now);
        strategyAutoPublishDao.insertRegistry(buildRegistryRow(task, candidate, current, now));
        strategyAutoPublishDao.insertReleaseEvent(buildReleaseEvent(task, candidate, active, current, eventType, reason, now));
        decision.published = true;
        decision.action = eventType;
        decision.reason = reason;
        decision.baselineVersion = active == null ? "" : active.strategyVersion;
    }

    private StrategyLiveRegistryPublishRow buildRegistryRow(StrategyBacktestTaskRow task,
                                                            StrategyCandidateRow candidate,
                                                            StrategyBacktestSummary current,
                                                            String effectiveTime) {
        BacktestParam param = parseTaskPayload(task);
        StrategyLiveRegistryPublishRow row = new StrategyLiveRegistryPublishRow();
        row.id = IdUtil.getId();
        row.strategyName = candidate.strategyName;
        row.strategyVersion = candidate.strategyVersion;
        row.category = blankTo(candidate.category, "generated");
        row.scene = candidate.scene;
        row.runtimeType = blankTo(candidate.runtimeType, "CLASSPATH");
        row.symbolScope = resolveSymbolScope(param);
        row.textScope = resolveTextScope(param);
        row.artifactUri = blankTo(candidate.artifactUri, "classpath://builtin");
        row.entryClass = candidate.entryClass;
        row.parametersJson = resolveRuntimeParametersJson(candidate, current);
        row.status = "ACTIVE";
        row.effectiveTime = effectiveTime;
        row.retireTime = null;
        row.source = publishSource;
        row.payload = blankTo(candidate.payload, "{}");
        row.description = candidate.description;
        return row;
    }

    private String resolveTextScope(BacktestParam param) {
        if (param == null) {
            return "*";
        }
        String text = blankTo(param.text, "");
        return StringUtils.isBlank(text) ? "*" : text;
    }

    private String resolveSymbolScope(BacktestParam param) {
        if (param == null) {
            throw new IllegalStateException("backtest param missing for auto publish");
        }
        String raw = blankTo(param.symbols, blankTo(param.symbol, ""));
        String normalized = normalizeSymbolScope(raw);
        if (StringUtils.isBlank(normalized)) {
            throw new IllegalStateException("backtest symbol scope is blank");
        }
        return normalized;
    }

    private String resolveRuntimeParametersJson(StrategyCandidateRow candidate,
                                               StrategyBacktestSummary current) {
        if (current != null && StringUtils.isNotBlank(current.bestParamSetJson)
                && !"{}".equals(current.bestParamSetJson.trim())) {
            return current.bestParamSetJson;
        }
        if (current != null && current.resultCount != null && current.resultCount.intValue() > 1) {
            throw new IllegalStateException("multi-symbol publish requires a concrete bestParamSetJson");
        }
        Map<String, Object> defaults = candidate == null
                ? Collections.<String, Object>emptyMap()
                : StrategyParametersSupport.extractDefaultParams(candidate.parametersJson);
        if (defaults == null || defaults.isEmpty()) {
            return "{}";
        }
        return JsonUtils.Serializer(defaults);
    }

    private StrategyReleaseEventRecord buildReleaseEvent(StrategyBacktestTaskRow task,
                                                         StrategyCandidateRow candidate,
                                                         StrategyLiveRegistryPublishRow active,
                                                         StrategyBacktestSummary current,
                                                         String eventType,
                                                         String reason,
                                                         String eventTime) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("taskId", task == null ? "" : task.id);
        payload.put("strategyName", candidate.strategyName);
        payload.put("strategyVersion", candidate.strategyVersion);
        payload.put("windowMode", current.windowMode);
        payload.put("sliceCount", current.sliceCount);
        payload.put("currentValidatePnl", current.validatePnl);
        payload.put("currentForwardPnl", current.forwardPnl);
        payload.put("currentTotalPnl", current.totalPnl);
        payload.put("currentForwardScore", current.forwardScore);
        payload.put("minForwardContribution", current.minForwardContribution);
        payload.put("overfitPass", current.overfitPass);
        payload.put("overfitReason", current.overfitReason);
        if (active != null) {
            payload.put("replacedVersion", active.strategyVersion);
        }

        StrategyReleaseEventRecord row = new StrategyReleaseEventRecord();
        row.id = IdUtil.getId();
        row.eventTime = eventTime;
        row.strategyName = candidate.strategyName;
        row.fromVersion = active == null ? null : active.strategyVersion;
        row.toVersion = candidate.strategyVersion;
        row.runtimeType = blankTo(candidate.runtimeType, "CLASSPATH");
        row.eventType = eventType;
        row.reason = reason;
        row.source = publishSource;
        row.payload = JsonUtils.Serializer(payload);
        return row;
    }

    private StrategyBacktestSummary summarizeCurrent(StrategyBacktestTaskRow task,
                                                     StrategyCandidateRow candidate,
                                                     BacktestModels.BacktestResponse response) {
        StrategyBacktestSummary summary = new StrategyBacktestSummary();
        summary.sid = task == null ? "" : task.id;
        summary.strategyName = candidate.strategyName;
        summary.strategyVersion = candidate.strategyVersion;
        summary.runtimeType = candidate.runtimeType;
        summary.scene = candidate.scene;
        summary.runTime = nowString();
        summary.windowMode = response == null ? "" : response.windowMode;
        summary.sliceCount = response == null ? 0 : response.sliceCount;
        summary.optimizationMode = response == null ? "" : response.optimizationMode;
        summary.trialCount = response == null ? 0 : response.trialCount;
        summary.bestRank = response == null ? 0 : response.bestRank;
        summary.bestParamSetJson = response == null ? "{}" : blankTo(response.bestParamSetJson, "{}");
        summary.minForwardContribution = response == null ? 0D : toDouble(response.minForwardContribution);
        summary.validatePrimaryScore = response == null ? 0D : toDouble(response.validatePrimaryScore);
        summary.forwardAuxScore = response == null ? 0D : toDouble(response.forwardAuxScore);
        summary.feeAdjustedValidatePnl = response == null ? 0D : toDouble(response.feeAdjustedValidatePnl);
        summary.sliceParamDriftScore = response == null ? 0D : toDouble(response.sliceParamDriftScore);
        summary.oosPass = response == null ? 0 : response.oosPass;
        List<BacktestModels.BacktestResult> results = response == null
                ? null
                : response.results;
        double totalPnl = 0D;
        double fitPnl = 0D;
        double validatePnl = 0D;
        double forwardPnl = 0D;
        double forwardScoreSum = 0D;
        double validateTradeCount = 0D;
        double validateMaxDrawdownPct = 0D;
        double validateProfitFactor = 0D;
        int fragileBest = 0;
        int count = 0;
        boolean overfitPass = true;
        String overfitReason = "";
        if (results != null) {
            for (BacktestModels.BacktestResult result : results) {
                if (result == null) {
                    continue;
                }
                totalPnl += toDouble(result.totalPnl == null ? calcTotalPnl(result) : result.totalPnl);
                fitPnl += toDouble(result.fitPnl);
                validatePnl += toDouble(result.validatePnl);
                forwardPnl += toDouble(result.forwardPnl);
                forwardScoreSum += toDouble(result.forwardScore);
                validateTradeCount += result.tradeCount == null ? 0 : result.tradeCount.intValue();
                validateMaxDrawdownPct = Math.max(validateMaxDrawdownPct, toDouble(result.maxDrawdownPct));
                validateProfitFactor += toDouble(result.profitFactor);
                if (Integer.valueOf(1).equals(result.fragileBest)) {
                    fragileBest = 1;
                }
                if (!Integer.valueOf(1).equals(result.overfitPass)) {
                    overfitPass = false;
                    if (StringUtils.isBlank(overfitReason) && StringUtils.isNotBlank(result.overfitReason)) {
                        overfitReason = result.overfitReason;
                    }
                }
                count++;
            }
        }
        summary.fitPnl = scale(fitPnl);
        summary.validatePnl = scale(validatePnl);
        summary.forwardPnl = scale(forwardPnl);
        summary.totalPnl = scale(totalPnl);
        summary.forwardScore = scale(count <= 0 ? 0D : forwardScoreSum / count);
        summary.validateTradeCount = Integer.valueOf((int) validateTradeCount);
        summary.validateMaxDrawdownPct = scale(validateMaxDrawdownPct);
        summary.validateProfitFactor = scale(count <= 0 ? 0D : validateProfitFactor / count);
        summary.fragileBest = fragileBest;
        summary.overfitPass = overfitPass ? 1 : 0;
        summary.overfitReason = overfitReason;
        summary.resultCount = count;
        return summary;
    }

    private Double preferredValidateScore(StrategyBacktestSummary summary) {
        if (summary == null) {
            return 0D;
        }
        if (gt(summary.validatePrimaryScore, 0D)) {
            return summary.validatePrimaryScore;
        }
        return summary.validatePnl;
    }

    private Double preferredFeeAdjustedValidate(StrategyBacktestSummary summary) {
        if (summary == null) {
            return 0D;
        }
        if (summary.feeAdjustedValidatePnl != null && Math.abs(summary.feeAdjustedValidatePnl) > 0D) {
            return summary.feeAdjustedValidatePnl;
        }
        return summary.validatePnl;
    }

    private boolean lte(Double left, double right) {
        return toDouble(left) <= right;
    }

    private BigDecimal calcTotalPnl(BacktestModels.BacktestResult result) {
        if (result == null || result.initialCapital == null || result.finalCapital == null) {
            return BigDecimal.ZERO;
        }
        return result.finalCapital.subtract(result.initialCapital);
    }

    private BacktestParam parseTaskPayload(StrategyBacktestTaskRow task) {
        if (task == null || StringUtils.isBlank(task.payload)) {
            return null;
        }
        try {
            StrategyBacktestTaskPayloadEnvelope envelope =
                    JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
            if (envelope != null && envelope.backtestParam != null) {
                return envelope.backtestParam;
            }
        } catch (Exception ignore) {
        }
        try {
            return JsonUtils.Deserialize(task.payload, BacktestParam.class);
        } catch (Exception e) {
            log.warn("parseTaskPayload error, task:{}", task.id, e);
            return null;
        }
    }

    private String normalizeSymbolScope(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String normalized = raw.replace("|", ",");
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        for (String token : normalized.split(",")) {
            String item = StringUtils.defaultString(token).trim().toUpperCase(Locale.ENGLISH);
            if (StringUtils.isBlank(item)) {
                continue;
            }
            unique.add(item);
        }
        if (unique.isEmpty()) {
            return "";
        }
        return StringUtils.join(new ArrayList<String>(unique), ",");
    }

    private boolean gt(Double left, Double right) {
        return toDouble(left) > toDouble(right);
    }

    private boolean gte(Double left, Double right) {
        return toDouble(left) >= toDouble(right);
    }

    private boolean isTrue(Integer value) {
        return value != null && value.intValue() > 0;
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0D : value.doubleValue();
    }

    private double toDouble(Double value) {
        return value == null ? 0D : value;
    }

    private Double scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private Double forwardContribution(Double forwardPnl, Double totalPnl) {
        double total = toDouble(totalPnl);
        if (total <= 0D) {
            return 0D;
        }
        return scale(toDouble(forwardPnl) / total);
    }

    private String blankTo(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value.trim();
    }

    private String nowString() {
        return CLICKHOUSE_DATETIME.format(LocalDateTime.now());
    }
}
