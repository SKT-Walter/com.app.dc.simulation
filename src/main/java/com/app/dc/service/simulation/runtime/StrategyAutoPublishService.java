package com.app.dc.service.simulation.runtime;

import com.app.common.utils.IdUtil;
import com.app.dc.po.backtest.BacktestParam;
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
import java.util.LinkedHashMap;
import java.util.List;
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
            if (!gt(current.validatePnl, 0D)) {
                decision.reason = "validate_pnl <= 0";
                return decision;
            }
            if (!gt(current.forwardPnl, 0D)) {
                decision.reason = "forward_pnl <= 0";
                return decision;
            }
            if (!gt(current.totalPnl, 0D)) {
                decision.reason = "total_pnl <= 0";
                return decision;
            }
            if (!gt(current.forwardScore, 0D)) {
                decision.reason = "forward_score <= 0";
                return decision;
            }

            StrategyLiveRegistryPublishRow active = strategyAutoPublishDao.loadCurrentActive(candidate.strategyName);
            if (active == null) {
                publish(decision, task, candidate, null, current, "PROMOTE",
                        "promote profitable walk-forward first version");
                return decision;
            }

            decision.baselineVersion = active.strategyVersion;
            if (StringUtils.equalsIgnoreCase(active.strategyVersion, candidate.strategyVersion)) {
                decision.reason = "same version already active";
                return decision;
            }

            StrategyBacktestSummary baseline = strategyAutoPublishDao
                    .loadLatestSummary(candidate.strategyName, active.strategyVersion);
            if (baseline == null) {
                decision.reason = "baseline backtest summary missing";
                return decision;
            }
            decision.baselineTotalPnl = baseline.totalPnl;
            decision.baselineValidatePnl = baseline.validatePnl;
            decision.baselineForwardPnl = baseline.forwardPnl;
            decision.baselineForwardScore = baseline.forwardScore;

            if (!gt(current.forwardScore, baseline.forwardScore)) {
                decision.reason = "forward_score not better than active baseline";
                return decision;
            }
            if (!gt(current.totalPnl, baseline.totalPnl)) {
                decision.reason = "total_pnl not better than active baseline";
                return decision;
            }

            publish(decision, task, candidate, active, current, "REPLACE",
                    "replace active version with stronger backtest result");
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
        strategyAutoPublishDao.insertRegistry(buildRegistryRow(task, candidate, now));
        strategyAutoPublishDao.insertReleaseEvent(buildReleaseEvent(task, candidate, active, current, eventType, reason, now));
        decision.published = true;
        decision.action = eventType;
        decision.reason = reason;
        decision.baselineVersion = active == null ? "" : active.strategyVersion;
    }

    private StrategyLiveRegistryPublishRow buildRegistryRow(StrategyBacktestTaskRow task,
                                                            StrategyCandidateRow candidate,
                                                            String effectiveTime) {
        BacktestParam param = parseTaskPayload(task);
        StrategyLiveRegistryPublishRow row = new StrategyLiveRegistryPublishRow();
        row.id = IdUtil.getId();
        row.strategyName = candidate.strategyName;
        row.strategyVersion = candidate.strategyVersion;
        row.category = blankTo(candidate.category, "generated");
        row.scene = candidate.scene;
        row.runtimeType = blankTo(candidate.runtimeType, "CLASSPATH");
        row.symbolScope = deriveSymbolScope(param);
        row.textScope = deriveTextScope(param);
        row.artifactUri = blankTo(candidate.artifactUri, "classpath://builtin");
        row.entryClass = candidate.entryClass;
        row.parametersJson = blankTo(candidate.payload, "{}");
        row.status = "ACTIVE";
        row.effectiveTime = effectiveTime;
        row.retireTime = null;
        row.source = publishSource;
        row.payload = blankTo(candidate.payload, "{}");
        row.description = candidate.description;
        return row;
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
        List<BacktestModels.BacktestResult> results = response == null
                ? null
                : response.results;
        double totalPnl = 0D;
        double fitPnl = 0D;
        double validatePnl = 0D;
        double forwardPnl = 0D;
        double forwardScoreSum = 0D;
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
        summary.overfitPass = overfitPass ? 1 : 0;
        summary.overfitReason = overfitReason;
        summary.resultCount = count;
        return summary;
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
            return JsonUtils.Deserialize(task.payload, BacktestParam.class);
        } catch (Exception e) {
            log.warn("parseTaskPayload error, task:{}", task.id, e);
            return null;
        }
    }

    private String deriveSymbolScope(BacktestParam param) {
        if (param == null) {
            return "*";
        }
        if (StringUtils.isNotBlank(param.symbols)) {
            return normalizeCsv(param.symbols);
        }
        if (StringUtils.isNotBlank(param.symbol)) {
            return normalizeCsv(param.symbol);
        }
        return "*";
    }

    private String deriveTextScope(BacktestParam param) {
        if (param == null || StringUtils.isBlank(param.text)) {
            return "*";
        }
        return normalizeCsv(param.text);
    }

    private String normalizeCsv(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "*";
        }
        String[] parts = raw.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String token = part == null ? "" : part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(token);
        }
        return sb.length() <= 0 ? "*" : sb.toString();
    }

    private boolean gt(Double left, Double right) {
        return toDouble(left) > toDouble(right);
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

    private String blankTo(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value.trim();
    }

    private String nowString() {
        return CLICKHOUSE_DATETIME.format(LocalDateTime.now());
    }
}
