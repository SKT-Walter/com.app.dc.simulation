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
        StrategyLiveRegistryPublishRow active = strategyAutoPublishDao.loadCurrentActive(candidate.strategyName, symbolScope);
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
        decision.baselineForwardScore = baseline.forwardScore;
        decision.baselineValidatePrimaryScore = baseline.validatePrimaryScore;
        String baselineReason = validateAgainstBaseline(current, baseline, active == null);
        if (StringUtils.isNotBlank(baselineReason)) {
            decision.reason = baselineReason;
            return decision;
        }
        publishOne(decision, task, candidate, baselineRow, current, symbolScope, "REPLACE",
                active == null
                        ? "replace latest live baseline after review evolution"
                        : "replace active version with stronger backtest result");
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
        decision.totalPnl = current.totalPnl;
        decision.validatePrimaryScore = current.validatePrimaryScore;
        decision.forwardScore = current.forwardScore;
        decision.feeAdjustedValidatePnl = current.feeAdjustedValidatePnl;
        decision.validateTradeCount = current.validateTradeCount;
        decision.validateMaxDrawdownPct = current.validateMaxDrawdownPct;
        decision.validateProfitFactor = current.validateProfitFactor;
        decision.oosPass = current.oosPass;
        decision.overfitPass = current.overfitPass;

        String gateReason = validatePublishThresholds(current);
        if (StringUtils.isNotBlank(gateReason)) {
            decision.published = false;
            decision.action = "SKIP";
            decision.reason = gateReason;
            return decision;
        }

        StrategyLiveRegistryPublishRow active = strategyAutoPublishDao.loadCurrentActive(candidate.strategyName, current.symbolScope);
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
        String baselineReason = validateAgainstBaseline(current, baseline, active == null);
        if (StringUtils.isNotBlank(baselineReason)) {
            decision.published = false;
            decision.action = "SKIP";
            decision.reason = baselineReason;
            return decision;
        }
        insertPublishRow(task, candidate, baselineRow, current, current.symbolScope, "REPLACE",
                active == null
                        ? "replace latest live baseline after review evolution"
                        : "replace active version with stronger backtest result",
                effectiveTime);
        decision.published = true;
        decision.action = "REPLACE";
        decision.reason = active == null
                ? "replace latest live baseline after review evolution"
                : "replace active version with stronger backtest result";
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
        summary.symbolScope = resolveSingleSymbol(response);
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

    private StrategyBacktestSummary summarizeCurrent(StrategyBacktestTaskRow task,
                                                     StrategyCandidateRow candidate,
                                                     BacktestModels.BacktestResult result) {
        BacktestModels.BacktestResponse response = wrapResultAsResponse(null, result);
        StrategyBacktestSummary summary = summarizeCurrent(task, candidate, response);
        summary.symbolScope = result == null ? "" : normalizeSymbolScope(result.symbol);
        return summary;
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

    private String validateGlobalPreconditions(StrategyBacktestSummary current, StrategyCandidateRow candidate) {
        if (!StringUtils.equalsIgnoreCase(current.windowMode, "WALK_FORWARD")) {
            return "window_mode is not WALK_FORWARD";
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
        if (!gte(current.forwardPnl, 0D)) {
            return "forward_pnl < 0";
        }
        if (!gte(forwardContribution(current.forwardPnl, current.totalPnl), current.minForwardContribution)) {
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

    private String validateAgainstBaseline(StrategyBacktestSummary current,
                                           StrategyBacktestSummary baseline,
                                           boolean latestBaselineMode) {
        if (!gt(current.forwardScore, baseline.forwardScore)) {
            return latestBaselineMode
                    ? "forward_score not better than latest live baseline"
                    : "forward_score not better than active baseline";
        }
        if (!gt(preferredValidateScore(current), preferredValidateScore(baseline))) {
            return latestBaselineMode
                    ? "validate score not better than latest live baseline"
                    : "validate score not better than active baseline";
        }
        return "";
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
