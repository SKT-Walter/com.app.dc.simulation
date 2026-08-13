package com.app.dc.service.simulation.runtime;

import com.app.common.utils.IdUtil;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.signal.StrategyParametersSupport;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.scene.SceneQualificationPolicy;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

    @Value("${strategy.auto.publish.minValidateTrades:20}")
    private int minValidateTrades;

    @Value("${strategy.auto.publish.maxValidateDrawdownPct:0.15}")
    private double maxValidateDrawdownPct;

    @Value("${strategy.auto.publish.minValidateProfitFactor:1.20}")
    private double minValidateProfitFactor;

    @Value("${strategy.auto.publish.scene.enabled:true}")
    private boolean sceneQualificationEnabled;

    @Value("${strategy.auto.publish.scene.minSceneRecords:30}")
    private int sceneMinRecords;

    @Value("${strategy.auto.publish.scene.minTrades:5}")
    private int sceneMinTrades;

    @Value("${strategy.auto.publish.scene.minProfitFactor:1.05}")
    private double sceneMinProfitFactor;

    @Value("${strategy.auto.publish.scene.maxDrawdownPct:0.25}")
    private double sceneMaxDrawdownPct;

    @Value("${strategy.auto.publish.lossAwareBaselineReplace.enabled:true}")
    private boolean lossAwareBaselineReplaceEnabled;

    @Value("${strategy.auto.publish.lossAwareBaselineReplace.todayPnlThreshold:3.0}")
    private double lossAwareBaselineReplaceTodayPnlThreshold;

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
        if (isNonPublishingValidationTask(task)) {
            decision.reason = "live recheck is validation only";
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
            decision.currentFeeAdjustedForwardPnl = current.feeAdjustedForwardPnl;
            decision.currentForwardScore = current.forwardScore;
            decision.currentValidatePrimaryScore = current.validatePrimaryScore;
            decision.currentFeeAdjustedValidatePnl = current.feeAdjustedValidatePnl;
            decision.sceneQualificationPass = current.sceneQualificationPass;
            decision.sceneQualificationReason = current.sceneQualificationReason;
            decision.sceneRecordCount = current.sceneRecordCount;
            decision.sceneMatchedBarCount = current.sceneMatchedBarCount;
            decision.sceneTradeCount = current.sceneTradeCount;
            decision.scenePnl = current.scenePnl;
            decision.sceneProfitFactor = current.sceneProfitFactor;
            decision.sceneMaxDrawdownPct = current.sceneMaxDrawdownPct;

            String globalBlock = validateGlobalPreconditions(current, candidate);
            if (StringUtils.isNotBlank(globalBlock)) {
                decision.reason = globalBlock;
                return decision;
            }

            if (current.resultCount != null && current.resultCount.intValue() > 1) {
                return maybePublishMultiSymbol(decision, task, candidate, response, current);
            }
            return maybePublishSingleSymbol(decision, task, candidate, current, resolveSingleSymbol(response));
        } catch (Exception e) {
            log.error("StrategyAutoPublishService maybePublish error, strategy:{}@{}",
                    candidate.strategyName, candidate.strategyVersion, e);
            decision.reason = "auto publish error: " + e.getMessage();
            return decision;
        }
    }

    private StrategyAutoPublishDecision maybePublishSingleSymbol(StrategyAutoPublishDecision decision,
                                                                 StrategyBacktestTaskRow task,
                                                                 StrategyCandidateRow candidate,
                                                                 StrategyBacktestSummary current,
                                                                 String symbolScope) {
        String gateReason = validatePublishThresholds(current);
        if (StringUtils.isNotBlank(gateReason)) {
            decision.reason = gateReason;
            return decision;
        }
        StrategyLiveRegistryPublishRow active = resolveActiveRow(candidate.strategyName, symbolScope);
        StrategyLiveRegistryPublishRow baselineRow = resolveBaselineRow(candidate, active, symbolScope);
        if (baselineRow == null) {
            publishOne(decision, task, candidate, null, current, symbolScope, "PROMOTE",
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
                .loadLatestSummary(candidate.strategyName, baselineRow.strategyVersion, symbolScope);
        if (baseline == null) {
            decision.reason = active == null
                    ? "historical live baseline backtest summary missing"
                    : "baseline backtest summary missing";
            return decision;
        }
        decision.baselineTotalPnl = baseline.totalPnl;
        decision.baselineValidatePnl = baseline.validatePnl;
        decision.baselineForwardPnl = baseline.forwardPnl;
        decision.baselineFeeAdjustedForwardPnl = baseline.feeAdjustedForwardPnl;
        decision.baselineForwardScore = baseline.forwardScore;
        decision.baselineValidatePrimaryScore = baseline.validatePrimaryScore;
        BaselineComparison baselineCheck = compareAgainstBaseline(candidate, current, baseline, active, active == null, symbolScope);
        if (StringUtils.isNotBlank(baselineCheck.blockReason)) {
            decision.reason = baselineCheck.blockReason;
            return decision;
        }
        publishOne(decision, task, candidate, baselineRow, current, symbolScope, "REPLACE",
                baselineCheck.replaceReason);
        return decision;
    }

    private StrategyAutoPublishDecision maybePublishMultiSymbol(StrategyAutoPublishDecision decision,
                                                                StrategyBacktestTaskRow task,
                                                                StrategyCandidateRow candidate,
                                                                BacktestModels.BacktestResponse response,
                                                                StrategyBacktestSummary aggregate) {
        List<BacktestModels.BacktestResult> results = response == null || response.results == null
                ? Collections.<BacktestModels.BacktestResult>emptyList()
                : response.results;
        if (results.isEmpty()) {
            decision.reason = "multi-symbol result missing";
            return decision;
        }
        String now = nowString();
        int published = 0;
        int skipped = 0;
        String action = "";
        for (BacktestModels.BacktestResult result : results) {
            if (result == null) {
                continue;
            }
            StrategyBacktestSummary current = summarizeCurrent(task, candidate, wrapResultAsResponse(response, result));
            current.bestParamSetJson = blankTo(result.bestParamSetJson, "{}");
            current.resultCount = 1;
            current.validateTradeCount = result.tradeCount == null ? 0 : result.tradeCount;
            current.validateMaxDrawdownPct = scale(toDouble(result.maxDrawdownPct));
            current.validateProfitFactor = scale(toDouble(result.profitFactor));
            current.fragileBest = result.fragileBest == null ? 0 : result.fragileBest;
            current.overfitPass = result.overfitPass == null ? 0 : result.overfitPass;
            current.overfitReason = blankTo(result.overfitReason, "");
            current.oosPass = result.oosPass == null ? 0 : result.oosPass;
            current.symbolScope = normalizeSymbolScope(result.symbol);

            StrategyAutoPublishDecision.SymbolDecision symbolDecision =
                    buildSymbolDecision(candidate, current, now, task);
            decision.symbolDecisions.add(symbolDecision);
            if (symbolDecision.published) {
                published++;
                decision.publishedSymbols.add(symbolDecision.symbol);
                if (StringUtils.isBlank(decision.baselineVersion) && StringUtils.isNotBlank(symbolDecision.baselineVersion)) {
                    decision.baselineVersion = symbolDecision.baselineVersion;
                }
                if ("REPLACE".equals(symbolDecision.action)) {
                    action = "REPLACE";
                } else if (StringUtils.isBlank(action)) {
                    action = "PROMOTE";
                }
            } else {
                skipped++;
                decision.skippedSymbols.add(symbolDecision.symbol);
            }
        }
        decision.publishedCount = published;
        decision.skippedCount = skipped;
        decision.published = published > 0;
        decision.action = published <= 0 ? "SKIP"
                : (published == decision.symbolDecisions.size() ? action : ("REPLACE".equals(action) ? "PARTIAL_REPLACE" : "PARTIAL_PROMOTE"));
        if (published > 0 && skipped > 0) {
            decision.reason = "published " + published + " symbol(s), skipped " + skipped + " symbol(s)";
        } else if (published > 0) {
            decision.reason = "published " + published + " symbol(s)";
        } else {
            decision.reason = firstSkippedReason(decision.symbolDecisions, "no symbol passed publish gate");
        }
        decision.currentTotalPnl = aggregate.totalPnl;
        decision.currentValidatePnl = aggregate.validatePnl;
        decision.currentForwardPnl = aggregate.forwardPnl;
        decision.currentFeeAdjustedForwardPnl = aggregate.feeAdjustedForwardPnl;
        decision.currentForwardScore = aggregate.forwardScore;
        decision.currentValidatePrimaryScore = aggregate.validatePrimaryScore;
        decision.currentFeeAdjustedValidatePnl = aggregate.feeAdjustedValidatePnl;
        return decision;
    }

    private StrategyAutoPublishDecision.SymbolDecision buildSymbolDecision(StrategyCandidateRow candidate,
                                                                           StrategyBacktestSummary current,
                                                                           String effectiveTime,
                                                                           StrategyBacktestTaskRow task) {
        StrategyAutoPublishDecision.SymbolDecision decision = new StrategyAutoPublishDecision.SymbolDecision();
        decision.symbol = current.symbolScope;
        decision.bestParamSetJson = current.bestParamSetJson;
        decision.validatePnl = current.validatePnl;
        decision.forwardPnl = current.forwardPnl;
        decision.feeAdjustedForwardPnl = current.feeAdjustedForwardPnl;
        decision.totalPnl = current.totalPnl;
        decision.validatePrimaryScore = current.validatePrimaryScore;
        decision.forwardScore = current.forwardScore;
        decision.feeAdjustedValidatePnl = current.feeAdjustedValidatePnl;
        decision.validateTradeCount = current.validateTradeCount;
        decision.validateMaxDrawdownPct = current.validateMaxDrawdownPct;
        decision.validateProfitFactor = current.validateProfitFactor;
        decision.oosPass = current.oosPass;
        decision.overfitPass = current.overfitPass;
        decision.sceneQualificationPass = current.sceneQualificationPass;
        decision.sceneQualificationReason = current.sceneQualificationReason;

        String gateReason = validatePublishThresholds(current);
        if (StringUtils.isNotBlank(gateReason)) {
            decision.published = false;
            decision.action = "SKIP";
            decision.reason = gateReason;
            return decision;
        }

        StrategyLiveRegistryPublishRow active = resolveActiveRow(candidate.strategyName, current.symbolScope);
        StrategyLiveRegistryPublishRow baselineRow = resolveBaselineRow(candidate, active, current.symbolScope);
        if (baselineRow == null) {
            insertPublishRow(task, candidate, null, current, current.symbolScope, "PROMOTE",
                    "promote profitable walk-forward first version", effectiveTime);
            decision.published = true;
            decision.action = "PROMOTE";
            decision.reason = "promote profitable walk-forward first version";
            return decision;
        }
        decision.baselineVersion = baselineRow.strategyVersion;
        if (StringUtils.equalsIgnoreCase(baselineRow.strategyVersion, candidate.strategyVersion)) {
            decision.published = false;
            decision.action = "SKIP";
            decision.reason = active == null
                    ? "same version already latest live baseline"
                    : "same version already active";
            return decision;
        }
        StrategyBacktestSummary baseline = strategyAutoPublishDao
                .loadLatestSummary(candidate.strategyName, baselineRow.strategyVersion, current.symbolScope);
        if (baseline == null) {
            decision.published = false;
            decision.action = "SKIP";
            decision.reason = active == null
                    ? "historical live baseline backtest summary missing"
                    : "baseline backtest summary missing";
            return decision;
        }
        BaselineComparison baselineCheck = compareAgainstBaseline(candidate, current, baseline, active, active == null, current.symbolScope);
        if (StringUtils.isNotBlank(baselineCheck.blockReason)) {
            decision.published = false;
            decision.action = "SKIP";
            decision.reason = baselineCheck.blockReason;
            return decision;
        }
        insertPublishRow(task, candidate, baselineRow, current, current.symbolScope, "REPLACE",
                baselineCheck.replaceReason,
                effectiveTime);
        decision.published = true;
        decision.action = "REPLACE";
        decision.reason = baselineCheck.replaceReason;
        return decision;
    }

    private void publishOne(StrategyAutoPublishDecision decision,
                            StrategyBacktestTaskRow task,
                            StrategyCandidateRow candidate,
                            StrategyLiveRegistryPublishRow active,
                            StrategyBacktestSummary current,
                            String symbolScope,
                            String eventType,
                            String reason) {
        String now = nowString();
        insertPublishRow(task, candidate, active, current, symbolScope, eventType, reason, now);
        decision.published = true;
        decision.action = eventType;
        decision.reason = reason;
        decision.baselineVersion = active == null ? "" : active.strategyVersion;
        decision.publishedCount = 1;
        decision.skippedCount = 0;
        if (StringUtils.isNotBlank(symbolScope)) {
            decision.publishedSymbols.add(symbolScope);
        }
    }

    private void insertPublishRow(StrategyBacktestTaskRow task,
                                  StrategyCandidateRow candidate,
                                  StrategyLiveRegistryPublishRow active,
                                  StrategyBacktestSummary current,
                                  String symbolScope,
                                  String eventType,
                                  String reason,
                                  String effectiveTime) {
        strategyAutoPublishDao.retireActive(candidate.strategyName, symbolScope, candidate.strategyVersion, effectiveTime);
        strategyAutoPublishDao.insertRegistry(buildRegistryRow(task, candidate, current, symbolScope, effectiveTime));
        strategyAutoPublishDao.insertReleaseEvent(buildReleaseEvent(task, candidate, active, current, symbolScope, eventType, reason, effectiveTime));
        rebalanceAggregateActiveScopes(candidate, symbolScope, effectiveTime);
    }

    private StrategyLiveRegistryPublishRow buildRegistryRow(StrategyBacktestTaskRow task,
                                                            StrategyCandidateRow candidate,
                                                            StrategyBacktestSummary current,
                                                            String symbolScope,
                                                            String effectiveTime) {
        BacktestParam param = parseTaskPayload(task);
        StrategyLiveRegistryPublishRow row = new StrategyLiveRegistryPublishRow();
        row.id = IdUtil.getId();
        row.strategyName = candidate.strategyName;
        row.strategyVersion = candidate.strategyVersion;
        row.category = blankTo(candidate.category, "generated");
        row.scene = candidate.scene;
        row.runtimeType = blankTo(candidate.runtimeType, "CLASSPATH");
        row.symbolScope = StringUtils.isBlank(symbolScope) ? resolveSymbolScope(param) : symbolScope;
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
                                                         String symbolScope,
                                                         String eventType,
                                                         String reason,
                                                         String eventTime) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("taskId", task == null ? "" : task.id);
        payload.put("strategyName", candidate.strategyName);
        payload.put("strategyVersion", candidate.strategyVersion);
        payload.put("symbolScope", blankTo(symbolScope, ""));
        payload.put("windowMode", current.windowMode);
        payload.put("sliceCount", current.sliceCount);
        payload.put("currentValidatePnl", current.validatePnl);
        payload.put("currentForwardPnl", current.forwardPnl);
        payload.put("currentFeeAdjustedForwardPnl", current.feeAdjustedForwardPnl);
        payload.put("currentTotalPnl", current.totalPnl);
        payload.put("currentForwardScore", current.forwardScore);
        payload.put("minForwardContribution", current.minForwardContribution);
        payload.put("overfitPass", current.overfitPass);
        payload.put("overfitReason", current.overfitReason);
        payload.put("sceneQualificationPass", current.sceneQualificationPass);
        payload.put("sceneQualificationReason", current.sceneQualificationReason);
        payload.put("sceneRecordCount", current.sceneRecordCount);
        payload.put("sceneMatchedBarCount", current.sceneMatchedBarCount);
        payload.put("sceneTradeCount", current.sceneTradeCount);
        payload.put("scenePnl", current.scenePnl);
        payload.put("sceneProfitFactor", current.sceneProfitFactor);
        payload.put("sceneMaxDrawdownPct", current.sceneMaxDrawdownPct);
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
        summary.symbolScope = resolveSingleSymbol(response);
        summary.runtimeType = candidate.runtimeType;
        summary.executionModelVersion = resolveExecutionModelVersion(response);
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
        summary.feeAdjustedForwardPnl = response == null ? 0D : toDouble(response.feeAdjustedForwardPnl);
        summary.sliceParamDriftScore = response == null ? 0D : toDouble(response.sliceParamDriftScore);
        summary.oosPass = response == null ? 0 : response.oosPass;
        List<BacktestModels.BacktestResult> results = response == null
                ? null
                : response.results;
        double totalPnl = 0D;
        double fitPnl = 0D;
        double validatePnl = 0D;
        double forwardPnl = 0D;
        double feeAdjustedForwardPnl = 0D;
        double forwardScoreSum = 0D;
        double validateTradeCount = 0D;
        double validateMaxDrawdownPct = 0D;
        double validateProfitFactor = 0D;
        int fragileBest = 0;
        int count = 0;
        boolean overfitPass = true;
        String overfitReason = "";
        boolean fullPeriodSafetyPass = true;
        String fullPeriodSafetyReason = "";
        if (results != null) {
            for (BacktestModels.BacktestResult result : results) {
                if (result == null) {
                    continue;
                }
                totalPnl += toDouble(result.totalPnl == null ? calcTotalPnl(result) : result.totalPnl);
                fitPnl += toDouble(result.fitPnl);
                validatePnl += toDouble(result.validatePnl);
                forwardPnl += toDouble(result.forwardPnl);
                feeAdjustedForwardPnl += toDouble(result.feeAdjustedForwardPnl);
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
                if (result.fullPeriodSafety == null || !Boolean.TRUE.equals(result.fullPeriodSafety.passed)) {
                    fullPeriodSafetyPass = false;
                    if (StringUtils.isBlank(fullPeriodSafetyReason)) {
                        fullPeriodSafetyReason = result.fullPeriodSafety == null
                                ? "full-period safety evidence missing"
                                : blankTo(result.fullPeriodSafety.reason, "full-period safety check failed");
                    }
                }
                count++;
            }
        }
        summary.fitPnl = scale(fitPnl);
        summary.validatePnl = scale(validatePnl);
        summary.forwardPnl = scale(forwardPnl);
        summary.feeAdjustedForwardPnl = scale(feeAdjustedForwardPnl);
        summary.totalPnl = scale(totalPnl);
        summary.forwardScore = scale(count <= 0 ? 0D : forwardScoreSum / count);
        summary.validateTradeCount = Integer.valueOf((int) validateTradeCount);
        summary.validateMaxDrawdownPct = scale(validateMaxDrawdownPct);
        summary.validateProfitFactor = scale(count <= 0 ? 0D : validateProfitFactor / count);
        summary.fragileBest = fragileBest;
        summary.overfitPass = overfitPass ? 1 : 0;
        summary.overfitReason = overfitReason;
        summary.fullPeriodSafetyPass = fullPeriodSafetyPass;
        summary.fullPeriodSafetyReason = fullPeriodSafetyReason;
        summary.resultCount = count;
        summarizeSceneQualification(summary, results);
        return summary;
    }

    private void summarizeSceneQualification(StrategyBacktestSummary summary,
                                             List<BacktestModels.BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            summary.sceneQualificationPass = false;
            summary.sceneQualificationReason = "scene replay result missing";
            summary.sceneRecordCount = 0;
            summary.sceneMatchedBarCount = 0;
            summary.sceneTradeCount = 0;
            summary.scenePnl = 0D;
            summary.sceneProfitFactor = 0D;
            summary.sceneMaxDrawdownPct = 0D;
            return;
        }
        boolean passed = true;
        int minRecords = Integer.MAX_VALUE;
        int minMatchedBars = Integer.MAX_VALUE;
        int minTrades = Integer.MAX_VALUE;
        double pnl = 0D;
        double minProfitFactor = Double.MAX_VALUE;
        double maxDrawdown = 0D;
        String reason = "";
        for (BacktestModels.BacktestResult result : results) {
            BacktestModels.SceneShadowMetrics metrics = result == null ? null : result.sceneShadow;
            if (metrics == null) {
                passed = false;
                if (StringUtils.isBlank(reason)) {
                    reason = "scene replay result missing";
                }
                continue;
            }
            SceneQualificationPolicy.Decision decision = SceneQualificationPolicy.evaluate(
                    metrics, sceneMinRecords, sceneMinTrades, sceneMinProfitFactor, sceneMaxDrawdownPct);
            if (!decision.passed) {
                passed = false;
                if (StringUtils.isBlank(reason)) {
                    reason = decision.reason;
                }
            }
            minRecords = Math.min(minRecords, intValue(metrics.sceneRecordCount));
            minMatchedBars = Math.min(minMatchedBars, intValue(metrics.matchedBarCount));
            minTrades = Math.min(minTrades, intValue(metrics.tradeCount));
            pnl += toDouble(metrics.totalPnl);
            minProfitFactor = Math.min(minProfitFactor, toDouble(metrics.profitFactor));
            maxDrawdown = Math.max(maxDrawdown, toDouble(metrics.maxDrawdownPct));
        }
        summary.sceneQualificationPass = passed;
        summary.sceneQualificationReason = StringUtils.isBlank(reason)
                ? "scene qualification passed" : reason;
        summary.sceneRecordCount = minRecords == Integer.MAX_VALUE ? 0 : minRecords;
        summary.sceneMatchedBarCount = minMatchedBars == Integer.MAX_VALUE ? 0 : minMatchedBars;
        summary.sceneTradeCount = minTrades == Integer.MAX_VALUE ? 0 : minTrades;
        summary.scenePnl = pnl;
        summary.sceneProfitFactor = minProfitFactor == Double.MAX_VALUE ? 0D : minProfitFactor;
        summary.sceneMaxDrawdownPct = maxDrawdown;
    }

    private StrategyBacktestSummary summarizeCurrent(StrategyBacktestTaskRow task,
                                                     StrategyCandidateRow candidate,
                                                     BacktestModels.BacktestResult result) {
        BacktestModels.BacktestResponse response = wrapResultAsResponse(null, result);
        StrategyBacktestSummary summary = summarizeCurrent(task, candidate, response);
        summary.symbolScope = result == null ? "" : normalizeSymbolScope(result.symbol);
        return summary;
    }

    private String resolveExecutionModelVersion(BacktestModels.BacktestResponse response) {
        if (response == null || response.results == null) {
            return "";
        }
        for (BacktestModels.BacktestResult result : response.results) {
            if (result != null && StringUtils.isNotBlank(result.executionModelVersion)) {
                return result.executionModelVersion;
            }
        }
        return "";
    }

    private BacktestModels.BacktestResponse wrapResultAsResponse(BacktestModels.BacktestResponse template,
                                                                 BacktestModels.BacktestResult result) {
        BacktestModels.BacktestResponse response = new BacktestModels.BacktestResponse();
        if (template != null) {
            response.strategyName = template.strategyName;
            response.strategyVersion = template.strategyVersion;
            response.baselineVersion = template.baselineVersion;
            response.runtimeType = template.runtimeType;
            response.scene = template.scene;
            response.symbol = result == null ? "" : result.symbol;
            response.symbols = result == null || StringUtils.isBlank(result.symbol)
                    ? Collections.<String>emptyList()
                    : Collections.singletonList(result.symbol);
            response.text = result == null ? template.text : result.text;
            response.beginDate = result == null ? template.beginDate : result.beginDate;
            response.endDate = result == null ? template.endDate : result.endDate;
            response.windowMode = template.windowMode;
            response.sliceCount = result != null && result.sliceCount != null ? result.sliceCount : template.sliceCount;
            response.symbolCount = 1;
            response.fitWindowDays = result != null && result.fitWindowDays != null ? result.fitWindowDays : template.fitWindowDays;
            response.validateWindowDays = result != null && result.validateWindowDays != null ? result.validateWindowDays : template.validateWindowDays;
            response.forwardWindowDays = result != null && result.forwardWindowDays != null ? result.forwardWindowDays : template.forwardWindowDays;
            response.minSliceCount = result != null && result.minSliceCount != null ? result.minSliceCount : template.minSliceCount;
            response.optimizationMode = template.optimizationMode;
            response.optimizationObjective = template.optimizationObjective;
            response.minForwardContribution = template.minForwardContribution;
            response.trialCount = result != null && result.trialCount != null ? result.trialCount : template.trialCount;
            response.trialBudget = result != null && result.trialBudget != null ? result.trialBudget : template.trialBudget;
            response.trialBudgetUsed = result != null && result.trialBudgetUsed != null ? result.trialBudgetUsed : template.trialBudgetUsed;
            response.trialBudgetHit = result != null && result.trialBudgetHit != null ? result.trialBudgetHit : template.trialBudgetHit;
            response.coarseCandidateCount = result != null && result.coarseCandidateCount != null ? result.coarseCandidateCount : template.coarseCandidateCount;
            response.fineCandidateCount = result != null && result.fineCandidateCount != null ? result.fineCandidateCount : template.fineCandidateCount;
            response.bestRank = result != null && result.bestRank != null ? result.bestRank : template.bestRank;
            response.elapsedMs = result != null && result.elapsedMs != null ? result.elapsedMs : template.elapsedMs;
            response.results = result == null
                    ? Collections.<BacktestModels.BacktestResult>emptyList()
                    : Collections.singletonList(result);
        } else {
            response.symbol = result == null ? "" : result.symbol;
            response.symbols = result == null || StringUtils.isBlank(result.symbol)
                    ? Collections.<String>emptyList()
                    : Collections.singletonList(result.symbol);
            response.results = result == null
                    ? Collections.<BacktestModels.BacktestResult>emptyList()
                    : Collections.singletonList(result);
        }
        if (result != null) {
            response.fitPnl = result.fitPnl;
            response.validatePnl = result.validatePnl;
            response.forwardPnl = result.forwardPnl;
            response.totalPnl = result.totalPnl;
            response.forwardScore = result.forwardScore;
            response.validatePrimaryScore = result.validatePrimaryScore;
            response.forwardAuxScore = result.forwardAuxScore;
            response.feeAdjustedValidatePnl = result.feeAdjustedValidatePnl;
            response.feeAdjustedForwardPnl = result.feeAdjustedForwardPnl;
            response.sliceParamDriftScore = result.sliceParamDriftScore;
            response.oosPass = result.oosPass;
            response.overfitPass = result.overfitPass;
            response.overfitReason = result.overfitReason;
            response.bestParamSetJson = result.bestParamSetJson;
            response.fragileBest = result.fragileBest;
            response.stableParamRangeJson = result.stableParamRangeJson;
            response.neighborAvgPnl = result.neighborAvgPnl;
            response.neighborWorstPnl = result.neighborWorstPnl;
        }
        return response;
    }

    private String resolveSingleSymbol(BacktestModels.BacktestResponse response) {
        if (response == null) {
            return "";
        }
        if (StringUtils.isNotBlank(response.symbol)) {
            return normalizeSymbolScope(response.symbol);
        }
        if (response.symbols != null && response.symbols.size() == 1) {
            return normalizeSymbolScope(response.symbols.get(0));
        }
        return "";
    }

    private StrategyLiveRegistryPublishRow resolveBaselineRow(StrategyCandidateRow candidate,
                                                              StrategyLiveRegistryPublishRow active,
                                                              String symbolScope) {
        StrategyLiveRegistryPublishRow baselineRow = active;
        boolean baselineMigration = StringUtils.equalsIgnoreCase(candidate.generationType, "LIVE_BASELINE_MIGRATION");
        boolean reviewEvolution = StringUtils.equalsIgnoreCase(candidate.generationType, "REVIEW_EVOLUTION");
        if (baselineRow == null && (reviewEvolution || baselineMigration)) {
            baselineRow = strategyAutoPublishDao.loadLatestLiveBaseline(candidate.strategyName, symbolScope);
        }
        return baselineRow;
    }

    private StrategyLiveRegistryPublishRow resolveActiveRow(String strategyName, String symbolScope) {
        if (StringUtils.isBlank(strategyName)) {
            return null;
        }
        List<StrategyLiveRegistryPublishRow> rows = strategyAutoPublishDao.listCurrentActiveRows(strategyName);
        if (rows == null || rows.isEmpty()) {
            return strategyAutoPublishDao.loadCurrentActive(strategyName, symbolScope);
        }
        String normalizedTarget = normalizeSymbolScope(symbolScope);
        if (StringUtils.isBlank(normalizedTarget)) {
            return rows.get(0);
        }
        StrategyLiveRegistryPublishRow broadMatch = null;
        for (StrategyLiveRegistryPublishRow row : rows) {
            if (row == null) {
                continue;
            }
            String normalizedScope = normalizeSymbolScope(row.symbolScope);
            if (StringUtils.equalsIgnoreCase(normalizedScope, normalizedTarget)) {
                return row;
            }
            if (scopeContainsSymbol(normalizedScope, normalizedTarget) && broadMatch == null) {
                broadMatch = row;
            }
        }
        return broadMatch;
    }

    private void rebalanceAggregateActiveScopes(StrategyCandidateRow candidate,
                                                String publishedSymbol,
                                                String effectiveTime) {
        if (candidate == null || StringUtils.isBlank(candidate.strategyName) || StringUtils.isBlank(publishedSymbol)) {
            return;
        }
        String normalizedPublished = normalizeSymbolScope(publishedSymbol);
        if (StringUtils.isBlank(normalizedPublished)) {
            return;
        }
        List<StrategyLiveRegistryPublishRow> activeRows = strategyAutoPublishDao.listCurrentActiveRows(candidate.strategyName);
        if (activeRows == null || activeRows.isEmpty()) {
            return;
        }
        for (StrategyLiveRegistryPublishRow row : activeRows) {
            if (row == null || StringUtils.isBlank(row.symbolScope)) {
                continue;
            }
            if (StringUtils.equalsIgnoreCase(row.strategyVersion, candidate.strategyVersion)) {
                continue;
            }
            List<String> tokens = scopeTokens(row.symbolScope);
            if (tokens.size() <= 1 || !tokens.contains(normalizedPublished)) {
                continue;
            }
            strategyAutoPublishDao.retireActive(candidate.strategyName, row.symbolScope, candidate.strategyVersion, effectiveTime);
            List<String> remaining = new ArrayList<String>(tokens);
            remaining.remove(normalizedPublished);
            if (!remaining.isEmpty()) {
                strategyAutoPublishDao.insertRegistry(buildCarryForwardRegistryRow(row, remaining, effectiveTime));
            }
        }
    }

    private StrategyLiveRegistryPublishRow buildCarryForwardRegistryRow(StrategyLiveRegistryPublishRow source,
                                                                        List<String> remainingSymbols,
                                                                        String effectiveTime) {
        StrategyLiveRegistryPublishRow row = new StrategyLiveRegistryPublishRow();
        row.id = IdUtil.getId();
        row.strategyName = source.strategyName;
        row.strategyVersion = source.strategyVersion;
        row.category = source.category;
        row.scene = source.scene;
        row.runtimeType = source.runtimeType;
        row.symbolScope = StringUtils.join(remainingSymbols, ",");
        row.textScope = source.textScope;
        row.artifactUri = source.artifactUri;
        row.entryClass = source.entryClass;
        row.parametersJson = source.parametersJson;
        row.status = "ACTIVE";
        row.effectiveTime = effectiveTime;
        row.retireTime = null;
        row.source = source.source;
        row.payload = rewriteCarryForwardPayload(source.payload, row.symbolScope);
        row.description = source.description;
        return row;
    }

    @SuppressWarnings("unchecked")
    private String rewriteCarryForwardPayload(String rawPayload, String remainingScope) {
        if (StringUtils.isBlank(rawPayload) || StringUtils.isBlank(remainingScope)) {
            return blankTo(rawPayload, "{}");
        }
        try {
            Map<String, Object> payload = JsonUtils.Deserialize(rawPayload, Map.class);
            if (payload == null) {
                payload = new LinkedHashMap<String, Object>();
            } else {
                payload = new LinkedHashMap<String, Object>(payload);
            }
            payload.put("symbols", remainingScope);
            payload.put("symbolScope", remainingScope);
            payload.put("symbol", firstScopeToken(remainingScope));
            return JsonUtils.Serializer(payload);
        } catch (Exception e) {
            log.warn("rewriteCarryForwardPayload failed, scope:{}, fallback to raw payload", remainingScope, e);
            return blankTo(rawPayload, "{}");
        }
    }

    private String validateGlobalPreconditions(StrategyBacktestSummary current, StrategyCandidateRow candidate) {
        if (!StringUtils.equals(current.executionModelVersion, BacktestModels.EXECUTION_MODEL_VERSION)) {
            return "unsupported backtest execution model";
        }
        if (!StringUtils.equalsIgnoreCase(current.windowMode, BacktestModels.SCENE_CONDITIONED_WINDOW_MODE)) {
            return "window_mode is not SCENE_CONDITIONED_WALK_FORWARD";
        }
        if (!Boolean.TRUE.equals(current.fullPeriodSafetyPass)) {
            return StringUtils.isBlank(current.fullPeriodSafetyReason)
                    ? "full-period safety check failed"
                    : current.fullPeriodSafetyReason;
        }
        if (expectsOptimizationEvidence(candidate) && (current.trialCount == null || current.trialCount.intValue() <= 0)) {
            return "optimization evidence missing";
        }
        if (!gt(current.sliceCount == null ? 0D : current.sliceCount.doubleValue(), 2D)) {
            return "slice_count < 3";
        }
        return "";
    }

    private String validatePublishThresholds(StrategyBacktestSummary current) {
        if (sceneQualificationEnabled) {
            if (!Boolean.TRUE.equals(current.sceneQualificationPass)) {
                return StringUtils.isBlank(current.sceneQualificationReason)
                        ? "scene qualification not passed"
                        : current.sceneQualificationReason;
            }
            if (current.sceneRecordCount == null || current.sceneRecordCount < Math.max(1, sceneMinRecords)) {
                return "scene history records below threshold";
            }
            if (current.sceneMatchedBarCount == null || current.sceneMatchedBarCount <= 0) {
                return "no K-line interval matched the strategy scene";
            }
            if (current.sceneTradeCount == null || current.sceneTradeCount < Math.max(1, sceneMinTrades)) {
                return "scene-matched trades below threshold";
            }
            if (!gt(current.scenePnl, 0D)) {
                return "scene fee-adjusted pnl <= 0";
            }
            if (!gte(current.sceneProfitFactor, sceneMinProfitFactor)) {
                return "scene profit factor below threshold";
            }
            if (!lte(current.sceneMaxDrawdownPct, sceneMaxDrawdownPct)) {
                return "scene drawdown above threshold";
            }
        }
        if (!isTrue(current.overfitPass)) {
            return StringUtils.isBlank(current.overfitReason)
                    ? "overfit gate not passed"
                    : current.overfitReason;
        }
        if (!isTrue(current.oosPass)) {
            return "oos gate not passed";
        }
        if (!gt(preferredValidateScore(current), 0D)) {
            return "validate primary score <= 0";
        }
        if (!gte(current.validateTradeCount == null ? 0D : current.validateTradeCount.doubleValue(), (double) Math.max(1, minValidateTrades))) {
            return "validate trade count below threshold";
        }
        if (!lte(current.validateMaxDrawdownPct, maxValidateDrawdownPct)) {
            return "validate drawdown above threshold";
        }
        if (!gte(current.validateProfitFactor, minValidateProfitFactor)) {
            return "validate profit factor below threshold";
        }
        if (!gt(preferredFeeAdjustedValidate(current), 0D)) {
            return "fee adjusted validate pnl <= 0";
        }
        if (!gt(current.forwardScore, 0D)) {
            return "forward_score <= 0";
        }
        if (!gt(preferredFeeAdjustedForward(current), 0D)) {
            return "fee adjusted forward pnl <= 0";
        }
        if (!gte(forwardContribution(preferredFeeAdjustedForward(current), current.totalPnl), current.minForwardContribution)) {
            return "forward contribution below threshold";
        }
        if (isTrue(current.fragileBest)) {
            return "fragile best param";
        }
        if ("{}".equals(StringUtils.trimToEmpty(current.bestParamSetJson))) {
            return "best param set missing";
        }
        return "";
    }

    private BaselineComparison compareAgainstBaseline(StrategyCandidateRow candidate,
                                                      StrategyBacktestSummary current,
                                                      StrategyBacktestSummary baseline,
                                                      StrategyLiveRegistryPublishRow active,
                                                      boolean latestBaselineMode,
                                                      String symbolScope) {
        BaselineComparison decision = new BaselineComparison();
        decision.replaceReason = latestBaselineMode
                ? "replace latest live baseline after review evolution"
                : "replace active version with stronger backtest result";
        if (!StringUtils.equals(baseline.executionModelVersion, current.executionModelVersion)) {
            decision.replaceReason = "replace legacy backtest baseline with realistic execution model result";
            return decision;
        }
        if (shouldAllowLossAwareBaselineReplace(candidate, active, latestBaselineMode, symbolScope)) {
            StrategyLiveTradeStatsRow stats = loadTodayTradeStats(candidate, active, symbolScope);
            if (isSevereActiveLoss(stats)) {
                decision.replaceReason = "replace active losing version after profitable walk-forward review"
                        + " (tradeDate=" + LocalDate.now()
                        + ", activeTodayPnl=" + trimDouble(toDouble(stats.todayPnl))
                        + ", activeTodayTradeCount=" + intValue(stats.todayTradeCount)
                        + ")";
                return decision;
            }
        }
        if (!gt(current.forwardScore, baseline.forwardScore)) {
            decision.blockReason = latestBaselineMode
                    ? "forward_score not better than latest live baseline"
                    : "forward_score not better than active baseline";
            return decision;
        }
        if (!gt(preferredValidateScore(current), preferredValidateScore(baseline))) {
            decision.blockReason = latestBaselineMode
                    ? "validate score not better than latest live baseline"
                    : "validate score not better than active baseline";
            return decision;
        }
        return decision;
    }

    private String firstSkippedReason(List<StrategyAutoPublishDecision.SymbolDecision> items, String fallback) {
        if (items == null) {
            return fallback;
        }
        for (StrategyAutoPublishDecision.SymbolDecision item : items) {
            if (item != null && !item.published && StringUtils.isNotBlank(item.reason)) {
                return item.reason;
            }
        }
        return fallback;
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

    private Double preferredFeeAdjustedForward(StrategyBacktestSummary summary) {
        if (summary == null) {
            return 0D;
        }
        if (summary.feeAdjustedForwardPnl != null && Math.abs(summary.feeAdjustedForwardPnl) > 0D) {
            return summary.feeAdjustedForwardPnl;
        }
        return summary.forwardPnl;
    }

    private boolean expectsOptimizationEvidence(StrategyCandidateRow candidate) {
        return candidate != null && StrategyParametersSupport.isOptimizationSupported(candidate.parametersJson);
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

    private boolean isNonPublishingValidationTask(StrategyBacktestTaskRow task) {
        if (task == null) {
            return false;
        }
        return isLiveRecheckPayload(task.initialPayload) || isLiveRecheckPayload(task.payload);
    }

    private boolean isLiveRecheckPayload(String payload) {
        if (StringUtils.isBlank(payload)) {
            return false;
        }
        try {
            StrategyBacktestTaskPayloadEnvelope envelope =
                    JsonUtils.Deserialize(payload, StrategyBacktestTaskPayloadEnvelope.class);
            if (envelope == null) {
                return false;
            }
            return "live_recheck".equalsIgnoreCase(StringUtils.trimToEmpty(envelope.workflowMode))
                    || "LIVE_RECHECK".equalsIgnoreCase(
                    StringUtils.trimToEmpty(envelope.workflowImprovementFlowType));
        } catch (Exception e) {
            return false;
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

    private List<String> scopeTokens(String raw) {
        String normalized = normalizeSymbolScope(raw);
        if (StringUtils.isBlank(normalized)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String token : normalized.split(",")) {
            String value = StringUtils.defaultString(token).trim().toUpperCase(Locale.ENGLISH);
            if (StringUtils.isNotBlank(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private boolean scopeContainsSymbol(String rawScope, String symbol) {
        String normalizedSymbol = normalizeSymbolScope(symbol);
        if (StringUtils.isBlank(normalizedSymbol)) {
            return false;
        }
        return scopeTokens(rawScope).contains(normalizedSymbol);
    }

    private String firstScopeToken(String rawScope) {
        List<String> tokens = scopeTokens(rawScope);
        return tokens.isEmpty() ? "" : tokens.get(0);
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

    private boolean shouldAllowLossAwareBaselineReplace(StrategyCandidateRow candidate,
                                                        StrategyLiveRegistryPublishRow active,
                                                        boolean latestBaselineMode,
                                                        String symbolScope) {
        return lossAwareBaselineReplaceEnabled
                && !latestBaselineMode
                && candidate != null
                && active != null
                && StringUtils.equalsIgnoreCase(active.strategyName, candidate.strategyName)
                && (StringUtils.isBlank(symbolScope)
                || StringUtils.isBlank(active.symbolScope)
                || StringUtils.equalsIgnoreCase(normalizeSymbolScope(active.symbolScope), normalizeSymbolScope(symbolScope)));
    }

    private StrategyLiveTradeStatsRow loadTodayTradeStats(StrategyCandidateRow candidate,
                                                          StrategyLiveRegistryPublishRow active,
                                                          String symbolScope) {
        if (candidate == null || active == null) {
            return null;
        }
        return strategyAutoPublishDao.loadTodayTradeStats(candidate.strategyName, active.strategyVersion, symbolScope);
    }

    private boolean isSevereActiveLoss(StrategyLiveTradeStatsRow stats) {
        return stats != null
                && intValue(stats.todayTradeCount) > 0
                && toDouble(stats.todayPnl) <= -Math.abs(lossAwareBaselineReplaceTodayPnlThreshold);
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private String trimDouble(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        String text = decimal.toPlainString();
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private String blankTo(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value.trim();
    }

    private String nowString() {
        return CLICKHOUSE_DATETIME.format(LocalDateTime.now());
    }

    private static class BaselineComparison {
        private String blockReason = "";
        private String replaceReason = "";
    }
}
