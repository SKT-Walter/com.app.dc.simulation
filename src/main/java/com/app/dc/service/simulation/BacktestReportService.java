package com.app.dc.service.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.BacktestResponse;
import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.BacktestSliceResult;
import com.app.dc.service.simulation.BacktestModels.EquityPoint;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.runtime.StrategyAutoPublishDao;
import com.app.dc.service.simulation.runtime.StrategyAutoPublishDecision;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskDao;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import com.app.dc.service.simulation.runtime.StrategyLiveRegistryPublishRow;
import com.app.dc.service.simulation.runtime.StrategyReleaseEventRecord;
import com.app.dc.signal.StrategyParametersSupport;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

@Service
@Slf4j
public class BacktestReportService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int REPLAY_MAX_CANDLES = 240;

    @Value("${binanceBacktestReportEnabled:true}")
    private boolean reportEnabled;

    @Value("${binanceBacktestReportDir:./src/docs}")
    private String reportDir;

    @Value("${strategy.auto.publish.minValidateTrades:20}")
    private int minValidateTrades;

    @Value("${strategy.auto.publish.maxValidateDrawdownPct:0.15}")
    private double maxValidateDrawdownPct;

    @Value("${strategy.auto.publish.minValidateProfitFactor:1.20}")
    private double minValidateProfitFactor;

    @Autowired
    private BacktestQueryService backtestQueryService;

    @Autowired
    private StrategyBacktestTaskDao strategyBacktestTaskDao;

    @Autowired
    private StrategyAutoPublishDao strategyAutoPublishDao;

    public String writeReport(BacktestResponse response) {
        return writeReport(null, response, null);
    }

    public String writeReport(String sid, BacktestResponse response, StrategyAutoPublishDecision publishDecision) {
        if (!reportEnabled || response == null) {
            return "";
        }
        try {
            Path dir = Paths.get(reportDir);
            Files.createDirectories(dir);
            String base = buildBaseName(response);
            Map<String, Object> report = buildReport(sid, response, publishDecision);
            Files.write(dir.resolve(base + ".json"), JsonUtils.Serializer(report).getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve(base + ".html"), buildHtml(report).getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve(base + ".md"), buildMarkdown(report).getBytes(StandardCharsets.UTF_8));
            return dir.resolve(base + ".html").toString().replace("\\", "/");
        } catch (Exception e) {
            log.error("BacktestReportService writeReport error", e);
            return "";
        }
    }

    public String writeCompareReport(BacktestResponse response) {
        return "";
    }

    private Map<String, Object> buildReport(String sid, BacktestResponse response, StrategyAutoPublishDecision decision) {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        StrategyCandidateRow candidate = strategyBacktestTaskDao.loadCandidate(response.strategyName, response.strategyVersion);
        List<StrategyLiveRegistryPublishRow> activeRows = strategyAutoPublishDao.listExactActiveRows(response.strategyName, response.strategyVersion);
        StrategyReleaseEventRecord release = strategyAutoPublishDao.loadLatestReleaseEvent(response.strategyName, response.strategyVersion);
        report.put("reportMeta", buildMeta(sid, response));
        report.put("tracking", buildTracking(sid, response, candidate, activeRows, release, decision));
        report.put("summary", buildSummary(response));
        report.put("gates", buildGates(response, candidate, activeRows, release, decision));
        report.put("audit", buildAudit(response, candidate));
        report.put("sceneShadow", buildSceneShadow(response));
        report.put("userSummary", buildSimpleUserSummary(response, decision, report));
        report.put("publish", buildPublish(decision));
        report.put("optimization", buildOptimization(response, candidate));
        report.put("results", buildResults(response));
        return report;
    }

    private Map<String, Object> buildMeta(String sid, BacktestResponse response) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("reportId", buildBaseName(response));
        meta.put("sid", defaultIfBlank(sid, ""));
        meta.put("generatedAt", LocalDateTime.now().toString());
        meta.put("strategyName", s(response.strategyName));
        meta.put("strategyVersion", s(response.strategyVersion));
        meta.put("strategyLabel", strategyLabel(response.strategyName, response.strategyVersion));
        meta.put("symbol", s(response.symbol));
        meta.put("symbols", response.symbols == null ? Collections.emptyList() : response.symbols);
        meta.put("text", s(response.text));
        meta.put("beginDate", s(response.beginDate));
        meta.put("endDate", s(response.endDate));
        meta.put("windowMode", s(response.windowMode));
        meta.put("fitWindowDays", nzInt(response.fitWindowDays));
        meta.put("validateWindowDays", nzInt(response.validateWindowDays));
        meta.put("forwardWindowDays", nzInt(response.forwardWindowDays));
        meta.put("minSliceCount", nzInt(response.minSliceCount));
        meta.put("currency", "USDT");
        return meta;
    }

    private Map<String, Object> buildTracking(String sid,
                                              BacktestResponse response,
                                              StrategyCandidateRow candidate,
                                              List<StrategyLiveRegistryPublishRow> activeRows,
                                              StrategyReleaseEventRecord release,
                                              StrategyAutoPublishDecision decision) {
        Map<String, Object> tracking = new LinkedHashMap<String, Object>();
        StrategyLiveRegistryPublishRow active = activeRows == null || activeRows.isEmpty() ? null : activeRows.get(0);
        tracking.put("sid", defaultIfBlank(sid, ""));
        tracking.put("strategyName", s(response == null ? null : response.strategyName));
        tracking.put("strategyVersion", s(response == null ? null : response.strategyVersion));
        tracking.put("strategyLabel", strategyLabel(response == null ? null : response.strategyName, response == null ? null : response.strategyVersion));
        tracking.put("scene", candidate == null ? "" : s(candidate.scene));
        tracking.put("generationType", candidate == null ? "" : s(candidate.generationType));
        tracking.put("runtimeType", candidate == null ? "" : s(candidate.runtimeType));
        tracking.put("candidateId", candidate == null ? "" : s(candidate.id));
        tracking.put("pipelineRunId", candidate == null ? "" : s(candidate.pipelineRunId()));
        tracking.put("candidateDescription", candidate == null ? "" : s(candidate.description));
        tracking.put("currentLiveVersion", active == null ? "" : s(active.strategyVersion));
        tracking.put("currentLiveLabel", active == null ? "" : strategyLabel(active.strategyName, active.strategyVersion));
        tracking.put("currentLiveStatus", active == null ? "" : s(active.status));
        tracking.put("currentLiveEffectiveTime", active == null ? "" : s(active.effectiveTime));
        tracking.put("currentLiveCount", activeRows == null ? 0 : activeRows.size());
        tracking.put("currentLiveSymbols", joinActiveSymbols(activeRows));
        tracking.put("releaseEventType", release == null ? "" : s(release.eventType));
        tracking.put("releaseEventTime", release == null ? "" : s(release.eventTime));
        tracking.put("releaseEventReason", release == null ? "" : translateReason(s(release.reason)));
        tracking.put("publishDecisionReason", decision == null ? "" : translateReason(s(decision.reason)));
        tracking.put("publishedCount", decision == null ? 0 : nzInt(decision.publishedCount));
        tracking.put("skippedCount", decision == null ? 0 : nzInt(decision.skippedCount));
        tracking.put("publishedSymbols", decision == null ? "" : joinStrings(decision.publishedSymbols));
        tracking.put("skippedSymbols", decision == null ? "" : joinStrings(decision.skippedSymbols));
        return tracking;
    }

    private Map<String, Object> buildSummary(BacktestResponse response) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("currency", "USDT");
        summary.put("fitPnl", scale(response.fitPnl));
        summary.put("validatePnl", scale(response.validatePnl));
        summary.put("forwardPnl", scale(response.forwardPnl));
        summary.put("totalPnl", scale(response.totalPnl));
        summary.put("validatePrimaryScore", scale(response.validatePrimaryScore));
        summary.put("forwardAuxScore", scale(response.forwardAuxScore));
        summary.put("feeAdjustedValidatePnl", scale(response.feeAdjustedValidatePnl));
        summary.put("feeAdjustedForwardPnl", scale(response.feeAdjustedForwardPnl));
        summary.put("sliceParamDriftScore", scale(response.sliceParamDriftScore));
        summary.put("oosPass", nzInt(response.oosPass));
        summary.put("sliceCount", nzInt(response.sliceCount));
        summary.put("symbolCount", nzInt(response.symbolCount));
        summary.put("fitWindowDays", nzInt(response.fitWindowDays));
        summary.put("validateWindowDays", nzInt(response.validateWindowDays));
        summary.put("forwardWindowDays", nzInt(response.forwardWindowDays));
        summary.put("minSliceCount", nzInt(response.minSliceCount));
        summary.put("optimizationMode", s(response.optimizationMode));
        summary.put("optimizationObjective", s(response.optimizationObjective));
        summary.put("minForwardContribution", scale(response.minForwardContribution));
        summary.put("trialCount", nzInt(response.trialCount));
        summary.put("trialBudget", nzInt(response.trialBudget));
        summary.put("trialBudgetUsed", nzInt(response.trialBudgetUsed));
        summary.put("trialBudgetHit", nzInt(response.trialBudgetHit));
        summary.put("coarseCandidateCount", nzInt(response.coarseCandidateCount));
        summary.put("fineCandidateCount", nzInt(response.fineCandidateCount));
        summary.put("bestRank", nzInt(response.bestRank));
        summary.put("bestParamSetJson", defaultIfBlank(response.bestParamSetJson, "{}"));
        summary.put("elapsedMs", nzInt(response.elapsedMs));
        summary.put("fragileBest", nzInt(response.fragileBest));
        summary.put("stableParamRangeJson", defaultIfBlank(response.stableParamRangeJson, "{}"));
        summary.put("neighborAvgPnl", scale(response.neighborAvgPnl));
        summary.put("neighborWorstPnl", scale(response.neighborWorstPnl));

        int tradeCount = 0;
        BigDecimal finalCapital = BigDecimal.ZERO;
        BigDecimal maxDrawdownPct = BigDecimal.ZERO;
        BigDecimal entryFeeTotal = BigDecimal.ZERO;
        BigDecimal exitFeeTotal = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        BigDecimal avgForwardScore = avgForwardScore(response.results);
        List<BacktestResult> rows = response.results == null ? Collections.<BacktestResult>emptyList() : response.results;
        for (BacktestResult result : rows) {
            tradeCount += nzInt(result.tradeCount);
            finalCapital = finalCapital.add(nz(result.finalCapital));
            maxDrawdownPct = maxDrawdownPct.max(nz(result.maxDrawdownPct));
            entryFeeTotal = entryFeeTotal.add(nz(result.entryFeeTotal));
            exitFeeTotal = exitFeeTotal.add(nz(result.exitFeeTotal));
            totalFee = totalFee.add(nz(result.totalFee));
        }
        summary.put("tradeCount", tradeCount);
        summary.put("forwardScore", scale(avgForwardScore));
        summary.put("finalCapital", scale(finalCapital));
        summary.put("maxDrawdownPct", scale(maxDrawdownPct));
        summary.put("entryFeeTotal", scale(entryFeeTotal));
        summary.put("exitFeeTotal", scale(exitFeeTotal));
        summary.put("totalFee", scale(totalFee));
        return summary;
    }

    private Map<String, Object> buildPublish(StrategyAutoPublishDecision decision) {
        Map<String, Object> publish = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<StrategyAutoPublishDecision.SymbolDecision> decisions = decision == null || decision.symbolDecisions == null
                ? Collections.<StrategyAutoPublishDecision.SymbolDecision>emptyList()
                : new ArrayList<StrategyAutoPublishDecision.SymbolDecision>(decision.symbolDecisions);
        decisions.sort(Comparator.comparing(x -> s(x.symbol)));
        for (StrategyAutoPublishDecision.SymbolDecision item : decisions) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("symbol", s(item.symbol));
            row.put("publishStatus", item.published ? "已发布" : "跳过");
            row.put("publishStatusClass", item.published ? "pass" : "warn");
            row.put("reason", translateReason(defaultIfBlank(item.reason, item.published ? "已满足单 Symbol 发布门槛" : "未满足单 Symbol 发布门槛")));
            row.put("validatePnl", scale(item.validatePnl));
            row.put("forwardPnl", scale(item.forwardPnl));
            row.put("validatePrimaryScore", scale(item.validatePrimaryScore));
            row.put("validateTradeCount", nzInt(item.validateTradeCount));
            row.put("validateMaxDrawdownPct", scale(item.validateMaxDrawdownPct));
            row.put("validateProfitFactor", scale(item.validateProfitFactor));
            row.put("bestParamSetJson", defaultIfBlank(item.bestParamSetJson, "{}"));
            rows.add(row);
        }
        publish.put("rows", rows);
        publish.put("publishedSymbols", decision == null ? "" : joinStrings(decision.publishedSymbols));
        publish.put("skippedSymbols", decision == null ? "" : joinStrings(decision.skippedSymbols));
        publish.put("publishedCount", decision == null ? 0 : nzInt(decision.publishedCount));
        publish.put("skippedCount", decision == null ? 0 : nzInt(decision.skippedCount));
        return publish;
    }

    private Map<String, Object> buildOptimization(BacktestResponse response, StrategyCandidateRow candidate) {
        Map<String, Object> optimization = new LinkedHashMap<String, Object>();
        String evidenceStatus = optimizationEvidenceStatus(response, candidate);
        String evidenceMessage = optimizationEvidenceMessage(response, candidate);
        optimization.put("optimizationMode", s(response == null ? null : response.optimizationMode));
        optimization.put("optimizationObjective", s(response == null ? null : response.optimizationObjective));
        optimization.put("evidenceStatus", evidenceStatus);
        optimization.put("evidenceMessage", evidenceMessage);
        optimization.put("minForwardContribution", scale(response == null ? null : response.minForwardContribution));
        optimization.put("trialCount", nzInt(response == null ? null : response.trialCount));
        optimization.put("trialBudget", nzInt(response == null ? null : response.trialBudget));
        optimization.put("trialBudgetUsed", nzInt(response == null ? null : response.trialBudgetUsed));
        optimization.put("trialBudgetHit", nzInt(response == null ? null : response.trialBudgetHit));
        optimization.put("coarseCandidateCount", nzInt(response == null ? null : response.coarseCandidateCount));
        optimization.put("fineCandidateCount", nzInt(response == null ? null : response.fineCandidateCount));
        optimization.put("bestRank", nzInt(response == null ? null : response.bestRank));
        optimization.put("bestParamSetJson", defaultIfBlank(response == null ? null : response.bestParamSetJson, "{}"));
        optimization.put("fragileBest", nzInt(response == null ? null : response.fragileBest));
        optimization.put("stableParamRangeJson", defaultIfBlank(response == null ? null : response.stableParamRangeJson, "{}"));
        optimization.put("neighborAvgPnl", scale(response == null ? null : response.neighborAvgPnl));
        optimization.put("neighborWorstPnl", scale(response == null ? null : response.neighborWorstPnl));
        List<Map<String, Object>> trials = new ArrayList<Map<String, Object>>();
        List<BacktestModels.OptimizationTrial> items = response == null || response.trials == null
                ? Collections.<BacktestModels.OptimizationTrial>emptyList()
                : response.trials;
        int limit = 20;
        for (BacktestModels.OptimizationTrial trial : items) {
            if (trial == null || limit-- <= 0) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("trialNo", nzInt(trial.trialNo));
            row.put("phase", s(trial.phase));
            row.put("rank", nzInt(trial.rank));
            row.put("fitPnl", scale(trial.fitPnl));
            row.put("validatePnl", scale(trial.validatePnl));
            row.put("forwardPnl", scale(trial.forwardPnl));
            row.put("totalPnl", scale(trial.totalPnl));
            row.put("forwardScore", scale(trial.forwardScore));
            row.put("maxDrawdownPct", scale(trial.maxDrawdownPct));
            row.put("overfitPass", nzInt(trial.overfitPass));
            row.put("overfitReason", translateReason(s(trial.overfitReason)));
            row.put("elapsedMs", nzInt(trial.elapsedMs));
            row.put("symbolCount", nzInt(trial.symbolCount));
            row.put("sliceCount", nzInt(trial.sliceCount));
            row.put("fitWindowDays", nzInt(trial.fitWindowDays));
            row.put("validateWindowDays", nzInt(trial.validateWindowDays));
            row.put("forwardWindowDays", nzInt(trial.forwardWindowDays));
            row.put("minSliceCount", nzInt(trial.minSliceCount));
            row.put("optimizationObjective", s(trial.optimizationObjective));
            row.put("minForwardContribution", scale(trial.minForwardContribution));
            row.put("fragileBest", nzInt(trial.fragileBest));
            row.put("stableParamRangeJson", defaultIfBlank(trial.stableParamRangeJson, "{}"));
            row.put("neighborAvgPnl", scale(trial.neighborAvgPnl));
            row.put("neighborWorstPnl", scale(trial.neighborWorstPnl));
            row.put("paramSetJson", defaultIfBlank(trial.paramSetJson, "{}"));
            trials.add(row);
        }
        optimization.put("trials", trials);
        optimization.put("paramParticipation", buildOptimizationParamParticipation(response));
        optimization.put("combinationSummary", buildOptimizationCombinationSummary(response));
        optimization.put("quality", buildOptimizationQuality(response, candidate));
        List<Map<String, Object>> heatmaps = buildOptimizationHeatmaps(response, candidate);
        optimization.put("heatmaps", heatmaps);
        optimization.put("heatmap", heatmaps.isEmpty() ? buildDisabledHeatmap("本次未产出可用热力图。") : heatmaps.get(0));
        return optimization;
    }

    private Map<String, Object> buildAudit(BacktestResponse response, StrategyCandidateRow candidate) {
        Map<String, Object> audit = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> checks = buildAuditChecks(response, candidate);
        boolean hardFail = false;
        boolean softWarn = false;
        for (Map<String, Object> check : checks) {
            String status = s(check.get("status"));
            if ("FAIL".equals(status)) {
                hardFail = true;
            } else if ("WARN".equals(status)) {
                softWarn = true;
            }
        }
        BigDecimal validateScore = preferredValidateScore(response);
        String finalDecision;
        if (hardFail) {
            finalDecision = gt(validateScore, BigDecimal.ZERO) ? "WATCH" : "FAIL";
        } else if (softWarn) {
            finalDecision = "WATCH";
        } else {
            finalDecision = "PASS";
        }
        audit.put("finalDecision", finalDecision);
        audit.put("finalDecisionLabel", auditDecisionLabelText(finalDecision));
        audit.put("summary", buildAuditSummary(response, candidate, checks, finalDecision));
        audit.put("checks", checks);
        return audit;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildUserSummary(BacktestResponse response,
                                                 StrategyCandidateRow candidate,
                                                 StrategyAutoPublishDecision decision,
                                                 Map<String, Object> report) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        Map<String, Object> gates = report == null ? null : (Map<String, Object>) report.get("gates");
        Map<String, Object> audit = report == null ? null : (Map<String, Object>) report.get("audit");
        Map<String, Object> core = report == null ? null : (Map<String, Object>) report.get("summary");
        boolean liveEntered = isTrue(gates == null ? null : gates.get("liveRegistryEntered"));
        boolean publishEligible = isTrue(gates == null ? null : gates.get("publishEligible"));
        String finalDecision = s(audit == null ? null : audit.get("finalDecision"));
        String headline;
        String statusClass;
        String actionLabel;
        if (liveEntered) {
            headline = "这版策略已经进入实盘";
            statusClass = "pass";
            actionLabel = "继续观察实盘";
        } else if ("PASS".equalsIgnoreCase(finalDecision) && publishEligible) {
            headline = "这版策略已经通过回测审核";
            statusClass = "pass";
            actionLabel = "等待自动发布";
        } else if ("WATCH".equalsIgnoreCase(finalDecision)) {
            headline = "这版策略暂时建议观察";
            statusClass = "warn";
            actionLabel = "不要直接上实盘";
        } else {
            headline = "这版策略暂时不建议上实盘";
            statusClass = "fail";
            actionLabel = "需要继续优化后再回测";
        }
        summary.put("headline", headline);
        summary.put("statusClass", statusClass);
        summary.put("actionLabel", actionLabel);
        summary.put("auditDecisionLabel", audit == null ? "" : s(audit.get("finalDecisionLabel")));
        summary.put("publishDecisionLabel", publishEligible ? "满足发布门槛" : "未满足发布门槛");
        summary.put("liveDecisionLabel", liveEntered ? "已进入实盘" : "未进入实盘");
        summary.put("auditSummary", audit == null ? "" : s(audit.get("summary")));

        List<String> reasons = new ArrayList<String>();
        if (audit != null && StringUtils.isNotBlank(s(audit.get("summary")))) {
            reasons.add(s(audit.get("summary")));
        }
        if (gates != null && StringUtils.isNotBlank(s(gates.get("publishReason")))) {
            addUnique(reasons, "发布判断：" + s(gates.get("publishReason")));
        }
        if (gates != null && !liveEntered && StringUtils.isNotBlank(s(gates.get("liveRegistryReason")))) {
            addUnique(reasons, "实盘状态：" + s(gates.get("liveRegistryReason")));
        }
        List<String> failedRules = gates == null ? Collections.<String>emptyList() : (List<String>) gates.get("failedRules");
        if (failedRules != null) {
            for (String item : failedRules) {
                addUnique(reasons, "未通过项：" + s(item));
                if (reasons.size() >= 3) {
                    break;
                }
            }
        }
        if (reasons.isEmpty() && response != null) {
            addUnique(reasons, "Validate 收益 " + scale(response.validatePnl).toPlainString()
                    + "，Forward 收益 " + scale(response.forwardPnl).toPlainString() + "。");
        }
        if (reasons.size() > 3) {
            reasons = new ArrayList<String>(reasons.subList(0, 3));
        }
        summary.put("reasons", reasons);

        List<String> nextSteps = new ArrayList<String>();
        if (liveEntered) {
            nextSteps.add("继续观察今天和未来 1 到 3 天的实盘盈亏、成交数和风控拦截情况。");
            nextSteps.add("如果后续复盘建议下线，再走升级版生成、回测和替换。");
        } else if ("WATCH".equalsIgnoreCase(finalDecision)) {
            nextSteps.add("暂时不要直接发布到实盘，先继续观察或补强策略逻辑。");
            nextSteps.add("优先针对报告里的未通过项重新生成或调参后再回测。");
        } else {
            nextSteps.add("先修复报告中的关键失败项，再重新回测。");
            nextSteps.add("如果 Forward 收益、OOS 或扣费后 Validate 不达标，不要直接发布。");
        }
        if (decision != null && decision.skippedSymbols != null && !decision.skippedSymbols.isEmpty()) {
            nextSteps.add("本次仍有跳过的品种：" + joinStrings(decision.skippedSymbols) + "，需要逐个看原因。");
        }
        summary.put("nextSteps", nextSteps);

        summary.put("keyValidatePnl", core == null ? "" : s(core.get("validatePnl")));
        summary.put("keyForwardPnl", core == null ? "" : s(core.get("forwardPnl")));
        summary.put("keyFeeAdjustedValidatePnl", core == null ? "" : s(core.get("feeAdjustedValidatePnl")));
        summary.put("keyFeeAdjustedForwardPnl", core == null ? "" : s(core.get("feeAdjustedForwardPnl")));
        summary.put("keyDrawdown", core == null ? "" : s(core.get("maxDrawdownPct")));
        summary.put("keyTradeCount", core == null ? "" : s(core.get("tradeCount")));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSimpleUserSummary(BacktestResponse response,
                                                       StrategyAutoPublishDecision decision,
                                                       Map<String, Object> report) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> gates = report == null ? null : (Map<String, Object>) report.get("gates");
        Map<String, Object> audit = report == null ? null : (Map<String, Object>) report.get("audit");
        Map<String, Object> core = report == null ? null : (Map<String, Object>) report.get("summary");
        Map<String, Object> sceneShadow = report == null ? null : (Map<String, Object>) report.get("sceneShadow");
        boolean liveEntered = isTrue(gates == null ? null : gates.get("liveRegistryEntered"));
        boolean publishEligible = isTrue(gates == null ? null : gates.get("publishEligible"));
        String finalDecision = s(audit == null ? null : audit.get("finalDecision"));
        if (liveEntered) {
            result.put("headline", "\u5df2\u901a\u8fc7\u9a8c\u8bc1\u5e76\u8fdb\u5165\u5b9e\u76d8");
            result.put("actionLabel", "\u7ee7\u7eed\u89c2\u5bdf\u5b9e\u76d8\u8868\u73b0");
            result.put("statusClass", "pass");
        } else if ("PASS".equalsIgnoreCase(finalDecision) && publishEligible) {
            result.put("headline", "\u5df2\u901a\u8fc7\u56de\u6d4b\u9a8c\u8bc1");
            result.put("actionLabel", "\u7b49\u5f85\u7cfb\u7edf\u81ea\u52a8\u53d1\u5e03");
            result.put("statusClass", "pass");
        } else if ("WATCH".equalsIgnoreCase(finalDecision)) {
            result.put("headline", "\u6837\u672c\u8fb9\u7f18\uff0c\u6682\u4e0d\u4e0a\u5b9e\u76d8");
            result.put("actionLabel", "\u7ee7\u7eed\u6539\u8fdb\u540e\u91cd\u65b0\u56de\u6d4b");
            result.put("statusClass", "warn");
        } else {
            result.put("headline", "\u672a\u901a\u8fc7\uff0c\u4e0d\u5efa\u8bae\u4e0a\u5b9e\u76d8");
            result.put("actionLabel", "\u4fee\u590d\u5931\u8d25\u539f\u56e0\u540e\u518d\u9a8c\u8bc1");
            result.put("statusClass", "fail");
        }
        result.put("auditSummary", audit == null ? "" : s(audit.get("summary")));
        result.put("keyFeeAdjustedValidatePnl", core == null ? "0" : s(core.get("feeAdjustedValidatePnl")));
        result.put("keyFeeAdjustedForwardPnl", core == null ? "0" : s(core.get("feeAdjustedForwardPnl")));
        result.put("keyDrawdown", core == null ? "0" : s(core.get("maxDrawdownPct")));
        result.put("keyTradeCount", core == null ? "0" : s(core.get("tradeCount")));

        List<String> reasons = new ArrayList<String>();
        List<String> failedRules = gates == null ? null : (List<String>) gates.get("failedRules");
        if (failedRules != null) {
            for (String failedRule : failedRules) {
                addUnique(reasons, translateReason(failedRule));
                if (reasons.size() >= 3) {
                    break;
                }
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("\u6263\u9664\u624b\u7eed\u8d39\u540e\u9a8c\u8bc1\u6536\u76ca\u4e3a "
                    + s(result.get("keyFeeAdjustedValidatePnl")) + " USDT\u3002");
            reasons.add("\u672a\u53c2\u4e0e\u9009\u53c2\u7684\u540e\u7eed\u65f6\u6bb5\u6536\u76ca\u4e3a "
                    + s(result.get("keyFeeAdjustedForwardPnl")) + " USDT\u3002");
        }
        result.put("reasons", reasons);

        List<String> nextSteps = new ArrayList<String>();
        if (liveEntered) {
            nextSteps.add("\u5173\u6ce8\u63a5\u4e0b\u6765 1-3 \u5929\u7684\u771f\u5b9e\u6210\u4ea4\u3001\u624b\u7eed\u8d39\u548c\u56de\u64a4\u3002");
        } else if ("PASS".equalsIgnoreCase(finalDecision) && publishEligible) {
            nextSteps.add("\u65e0\u9700\u624b\u5de5\u64cd\u4f5c\uff0c\u7531\u7cfb\u7edf\u5b8c\u6210\u53d1\u5e03\u3002");
        } else {
            nextSteps.add("\u4e0d\u4e0a\u5b9e\u76d8\uff0c\u5c06\u672a\u901a\u8fc7\u539f\u56e0\u56de\u704c\u5230\u4e0b\u4e00\u8f6e\u7b56\u7565\u6539\u8fdb\u3002");
        }
        if (sceneShadow != null && nzInt(sceneShadow.get("sceneRecordCount")) > 0) {
            nextSteps.add("\u573a\u666f\u5185\u62df\u5408\u3001\u9a8c\u8bc1\u548c\u524d\u77bb\u7ed3\u679c\u662f\u6b63\u5f0f\u53d1\u5e03\u8d44\u683c\u4f9d\u636e\u3002");
        }
        if (decision != null && decision.skippedSymbols != null && !decision.skippedSymbols.isEmpty()) {
            nextSteps.add("\u672c\u6b21\u672a\u53d1\u5e03\u54c1\u79cd\uff1a" + joinStrings(decision.skippedSymbols) + "\u3002");
        }
        result.put("nextSteps", nextSteps);
        return result;
    }

    private String buildAuditSummary(BacktestResponse response,
                                     StrategyCandidateRow candidate,
                                     List<Map<String, Object>> checks,
                                     String finalDecision) {
        if (response == null) {
            return "回测结果不存在，无法完成审核。";
        }
        List<String> failed = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        for (Map<String, Object> check : checks) {
            String status = s(check.get("status"));
            String name = s(check.get("name"));
            String message = s(check.get("message"));
            if ("FAIL".equals(status)) {
                failed.add(StringUtils.isBlank(message) ? name : name + " - " + message);
            } else if ("WARN".equals(status)) {
                warnings.add(StringUtils.isBlank(message) ? name : name + " - " + message);
            }
        }
        if ("PASS".equals(finalDecision)) {
            return "Validate OOS、Forward 辅助确认、参数稳健性和优化证据均满足当前审核门槛。";
        }
        if ("WATCH".equals(finalDecision)) {
            if (!failed.isEmpty()) {
                return "Validate 仍有一定可读性，但存在需要人工复核的问题：" + StringUtils.join(failed, "；");
            }
            return "主要门槛通过，但存在观察项：" + StringUtils.join(warnings, "；");
        }
        if (!failed.isEmpty()) {
            return "关键审核门槛未通过：" + StringUtils.join(failed, "；");
        }
        if (!hasOptimizationEvidence(response, candidate)) {
            return "缺少参数寻优证据，当前回测结果不能作为自动发布依据。";
        }
        return "当前回测未通过审核。";
    }

    private List<Map<String, Object>> buildAuditChecks(BacktestResponse response, StrategyCandidateRow candidate) {
        List<Map<String, Object>> checks = new ArrayList<Map<String, Object>>();
        if (response == null) {
            addAuditCheck(checks, "回测结果", "必须存在", "缺失", "FAIL", "未生成回测结果");
            return checks;
        }
        addAuditCheck(checks,
                "优化证据",
                expectsOptimizationEvidence(candidate) ? "trialCount > 0" : "无需优化",
                expectsOptimizationEvidence(candidate) ? String.valueOf(nzInt(response.trialCount)) : "不适用",
                !expectsOptimizationEvidence(candidate) || hasOptimizationEvidence(response, candidate) ? "PASS" : "FAIL",
                optimizationEvidenceMessage(response, candidate));
        addAuditCheck(checks,
                "过拟合检查",
                "必须通过",
                isTrue(response.overfitPass) ? "通过" : "失败",
                isTrue(response.overfitPass) ? "PASS" : "FAIL",
                translateReason(defaultIfBlank(response.overfitReason, "")));
        addAuditCheck(checks,
                "OOS 门槛",
                "必须通过",
                isTrue(response.oosPass) ? "通过" : "失败",
                isTrue(response.oosPass) ? "PASS" : "FAIL",
                isTrue(response.oosPass) ? "validate/forward 审核通过" : "validate/forward 审核未同时通过");
        BigDecimal validateScore = preferredValidateScore(response);
        addAuditCheck(checks,
                "Validate 主分",
                "> 0",
                scale(validateScore).toPlainString(),
                gt(validateScore, BigDecimal.ZERO) ? "PASS" : "FAIL",
                gt(validateScore, BigDecimal.ZERO) ? "" : "Validate 主 OOS 分数不为正");
        addAuditCheck(checks,
                "Forward 收益",
                ">= 0",
                scale(response.forwardPnl).toPlainString(),
                gte(response.forwardPnl, BigDecimal.ZERO) ? "PASS" : "FAIL",
                gte(response.forwardPnl, BigDecimal.ZERO) ? "" : "Forward 辅助确认收益为负");
        int tradeCount = sumTradeCount(response.results);
        addAuditCheck(checks,
                "Validate 交易数",
                ">= " + Math.max(1, minValidateTrades),
                String.valueOf(tradeCount),
                tradeCount >= Math.max(1, minValidateTrades) ? "PASS" : "WARN",
                tradeCount >= Math.max(1, minValidateTrades) ? "" : "Validate 交易样本偏少");
        BigDecimal maxDd = maxDrawdownPct(response.results);
        addAuditCheck(checks,
                "Validate 最大回撤",
                "<= " + scale(BigDecimal.valueOf(maxValidateDrawdownPct)).toPlainString(),
                scale(maxDd).toPlainString(),
                lte(maxDd, BigDecimal.valueOf(maxValidateDrawdownPct)) ? "PASS" : "FAIL",
                lte(maxDd, BigDecimal.valueOf(maxValidateDrawdownPct)) ? "" : "Validate 最大回撤超过阈值");
        BigDecimal profitFactor = avgProfitFactor(response.results);
        addAuditCheck(checks,
                "Validate Profit Factor",
                ">= " + scale(BigDecimal.valueOf(minValidateProfitFactor)).toPlainString(),
                scale(profitFactor).toPlainString(),
                gte(profitFactor, BigDecimal.valueOf(minValidateProfitFactor)) ? "PASS" : "WARN",
                gte(profitFactor, BigDecimal.valueOf(minValidateProfitFactor)) ? "" : "Profit factor 偏弱");
        BigDecimal feeAdjustedValidate = preferredFeeAdjustedValidate(response);
        addAuditCheck(checks,
                "扣费后 Validate 收益",
                "> 0",
                scale(feeAdjustedValidate).toPlainString(),
                gt(feeAdjustedValidate, BigDecimal.ZERO) ? "PASS" : "FAIL",
                gt(feeAdjustedValidate, BigDecimal.ZERO) ? "" : "扣费后的 Validate 收益不为正");
        addAuditCheck(checks,
                "参数脆弱性",
                "fragileBest 必须为 0",
                String.valueOf(nzInt(response.fragileBest)),
                isTrue(response.fragileBest) ? "WARN" : "PASS",
                isTrue(response.fragileBest) ? "最优参数点呈现孤点特征" : "最优参数邻域相对稳定");
                boolean singleSymbolMissingPublishable = !(response.results != null && response.results.size() > 1)
                && "{}".equals(StringUtils.trimToEmpty(response.bestParamSetJson));
        addAuditCheck(checks,
                "可发布参数集",
                response.results != null && response.results.size() > 1 ? "多 symbol 允许按 symbol 独立参数发布" : "需存在 bestParamSet",
                response.results != null && response.results.size() > 1
                        ? "按 symbol 独立最优参数"
                        : defaultIfBlank(response.bestParamSetJson, "{}"),
                response.results != null && response.results.size() > 1 ? "PASS" : (singleSymbolMissingPublishable ? "FAIL" : "PASS"),
                response.results != null && response.results.size() > 1
                        ? "多 symbol 场景不再要求统一 bestParamSet，支持按 symbol 拆分发布"
                        : (singleSymbolMissingPublishable ? "单一 symbol 缺少可发布参数集" : ""));
        return checks;
    }

    private void addAuditCheck(List<Map<String, Object>> checks,
                               String name,
                               String rule,
                               String actual,
                               String status,
                               String message) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("rule", rule);
        row.put("actual", actual);
        row.put("status", status);
        row.put("statusLabel", auditStatusLabel(status));
        row.put("message", message);
        checks.add(row);
    }

    private Map<String, Object> buildOptimizationQuality(BacktestResponse response, StrategyCandidateRow candidate) {
        Map<String, Object> quality = new LinkedHashMap<String, Object>();
        String status;
        List<String> reasons = new ArrayList<String>();
        if (!expectsOptimizationEvidence(candidate)) {
            status = "N/A";
            reasons.add("当前候选未启用参数优化。");
        } else if (!hasOptimizationEvidence(response, candidate)) {
            status = "MISSING";
            reasons.add("未产出 optimization trial，参数质量指标不能视为真实稳定性结论。");
        } else if (response != null && isTrue(response.fragileBest)) {
            status = "WEAK";
            reasons.add("最优参数点呈现孤点特征。");
            if (response.neighborWorstPnl != null) {
                reasons.add("邻域最差收益=" + scale(response.neighborWorstPnl).toPlainString());
            }
        } else if (response != null && gt(response.sliceParamDriftScore, BigDecimal.valueOf(2D))) {
            status = "WATCH";
            reasons.add("不同 slice 间参数漂移较大。");
            reasons.add("sliceParamDriftScore=" + scale(response.sliceParamDriftScore).toPlainString());
        } else {
            status = "GOOD";
            reasons.add("存在真实 trial 证据，且最优点未被判定为 fragile。");
            if (response != null) {
                reasons.add("neighborAvgPnl=" + scale(response.neighborAvgPnl).toPlainString());
            }
        }
        quality.put("status", status);
        quality.put("statusLabel", optimizationQualityLabel(status));
        quality.put("message", reasons.isEmpty() ? "" : reasons.get(0));
        quality.put("reasons", reasons);
        return quality;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildOptimizationHeatmaps(BacktestResponse response, StrategyCandidateRow candidate) {
        List<Map<String, Object>> heatmaps = new ArrayList<Map<String, Object>>();
        Map<String, Object> validateHeatmap = buildSliceHeatmap(
                response,
                "avgValidatePnl",
                "参数结果热图（Validate）");
        if (validateHeatmap != null) {
            heatmaps.add(validateHeatmap);
        }
        Map<String, Object> tradeHeatmap = buildSliceHeatmap(
                response,
                "avgValidateTradeCount",
                "交易覆盖热图（Validate）");
        if (tradeHeatmap != null) {
            heatmaps.add(tradeHeatmap);
        }
        Map<String, Object> forwardHeatmap = buildSliceHeatmap(
                response,
                "avgForwardPnl",
                "参数结果热图（Forward）");
        if (forwardHeatmap != null) {
            heatmaps.add(forwardHeatmap);
        }
        Map<String, Object> fitHeatmap = buildTrialHeatmap(
                response,
                candidate,
                "avgFitPnl",
                "参数试验热图（Fit）");
        if (fitHeatmap != null) {
            heatmaps.add(fitHeatmap);
        }
        if (heatmaps.isEmpty()) {
            heatmaps.add(buildDisabledHeatmap("本次未产出可用热力图。"));
        }
        return heatmaps;
    }

    private Map<String, Object> buildTrialHeatmap(BacktestResponse response,
                                                  StrategyCandidateRow candidate,
                                                  String metric,
                                                  String title) {
        if (!hasOptimizationEvidence(response, candidate) || response == null || response.trials == null || response.trials.isEmpty()) {
            return buildDisabledHeatmap("本次回测未产出可用 optimization trial，热力图不可用。");
        }
        Map<String, Set<String>> distinct = new LinkedHashMap<String, Set<String>>();
        List<Map<String, Object>> trialParamMaps = new ArrayList<Map<String, Object>>();
        for (BacktestModels.OptimizationTrial trial : response.trials) {
            Map<String, Object> params = parseJsonMap(trial == null ? null : trial.paramSetJson);
            trialParamMaps.add(params);
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                distinct.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<String>())
                        .add(String.valueOf(entry.getValue()));
            }
        }
        List<String> varying = new ArrayList<String>();
        for (Map.Entry<String, Set<String>> entry : distinct.entrySet()) {
            if (entry.getValue().size() > 1) {
                varying.add(entry.getKey());
            }
        }
        varying.sort((a, b) -> {
            int cmp = Integer.compare(distinct.get(b).size(), distinct.get(a).size());
            if (cmp != 0) {
                return cmp;
            }
            return a.compareTo(b);
        });
        if (varying.size() < 2) {
            return buildDisabledHeatmap("试验参数变化维度不足，无法生成二维热力图。");
        }
        String xParam = varying.get(0);
        String yParam = varying.get(1);
        List<String> aggregatedParams = varying.size() <= 2
                ? Collections.<String>emptyList()
                : varying.subList(2, varying.size());
        Set<String> xValueSet = new LinkedHashSet<String>();
        Set<String> yValueSet = new LinkedHashSet<String>();
        List<HeatmapPoint> points = new ArrayList<HeatmapPoint>();
        for (int i = 0; i < response.trials.size(); i++) {
            BacktestModels.OptimizationTrial trial = response.trials.get(i);
            Map<String, Object> params = i < trialParamMaps.size()
                    ? trialParamMaps.get(i)
                    : Collections.<String, Object>emptyMap();
            String xValue = String.valueOf(params.get(xParam));
            String yValue = String.valueOf(params.get(yParam));
            xValueSet.add(xValue);
            yValueSet.add(yValue);
            BigDecimal value = nz(trial == null ? null : trial.fitPnl);
            points.add(new HeatmapPoint(xValue, yValue, value));
        }
        String note = aggregatedParams.isEmpty()
                ? "当前展示的是本次实际 trial 试过的参数组合落点；空白格表示该参数组合本次未被搜索到。"
                : "当前只展示 trial 中变化次数最多的两个参数维度，其余变化参数已按实际 trial 结果聚合平均；空白格表示该参数组合本次未被搜索到。";
        Map<String, Object> heatmap = finalizeHeatmap(title, metric, xParam, yParam, xValueSet, yValueSet, aggregatedParams, points, note);
        heatmap.put("searchMode", defaultIfBlank(response == null ? null : response.optimizationMode, "UNKNOWN"));
        heatmap.put("viewType", "TRIAL_SEARCH");
        return heatmap;
    }

    private Map<String, Object> buildSliceHeatmap(BacktestResponse response, String metric, String title) {
        if (response == null || response.results == null || response.results.isEmpty()) {
            return null;
        }
        Map<String, Set<String>> distinct = new LinkedHashMap<String, Set<String>>();
        List<HeatmapPoint> points = new ArrayList<HeatmapPoint>();
        Set<String> xValueSet = new LinkedHashSet<String>();
        Set<String> yValueSet = new LinkedHashSet<String>();
        for (BacktestResult result : response.results) {
            if (result == null || result.sliceResults == null) {
                continue;
            }
            for (BacktestSliceResult slice : result.sliceResults) {
                Map<String, Object> params = parseJsonMap(slice == null ? null : slice.bestParamSetJson);
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    distinct.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<String>())
                            .add(String.valueOf(entry.getValue()));
                }
            }
        }
        List<String> varying = new ArrayList<String>();
        for (Map.Entry<String, Set<String>> entry : distinct.entrySet()) {
            if (entry.getValue().size() > 1) {
                varying.add(entry.getKey());
            }
        }
        varying.sort((a, b) -> {
            int cmp = Integer.compare(distinct.get(b).size(), distinct.get(a).size());
            if (cmp != 0) {
                return cmp;
            }
            return a.compareTo(b);
        });
        if (varying.size() < 2) {
            return buildDisabledHeatmap("最优参数在各个 slice 中变化维度不足，无法生成二维热力图。");
        }
        String xParam = varying.get(0);
        String yParam = varying.get(1);
        List<String> aggregatedParams = varying.size() <= 2
                ? Collections.<String>emptyList()
                : varying.subList(2, varying.size());
        for (BacktestResult result : response.results) {
            if (result == null || result.sliceResults == null) {
                continue;
            }
            for (BacktestSliceResult slice : result.sliceResults) {
                Map<String, Object> params = parseJsonMap(slice == null ? null : slice.bestParamSetJson);
                String xValue = String.valueOf(params.get(xParam));
                String yValue = String.valueOf(params.get(yParam));
                if ("null".equals(xValue) || "null".equals(yValue)) {
                    continue;
                }
                xValueSet.add(xValue);
                yValueSet.add(yValue);
                BigDecimal value;
                if ("avgValidatePnl".equals(metric)) {
                    value = nz(slice == null ? null : slice.validatePnl);
                } else if ("avgForwardPnl".equals(metric)) {
                    value = nz(slice == null ? null : slice.forwardPnl);
                } else if ("avgValidateTradeCount".equals(metric)) {
                    value = BigDecimal.valueOf(nzInt(slice == null ? null : slice.validateTradeCount));
                } else {
                    value = BigDecimal.ZERO;
                }
                points.add(new HeatmapPoint(xValue, yValue, value));
            }
        }
        String note;
        if ("avgValidateTradeCount".equals(metric)) {
            note = "当前展示的是各 slice 最优参数组合在 Validate 阶段的平均交易覆盖情况；空白格表示该参数组合没有成为任何 slice 的最优参数。";
        } else {
            note = "当前展示的是各 slice 最优参数组合在样本外阶段的平均结果；空白格表示该参数组合没有成为任何 slice 的最优参数。";
        }
        if (!aggregatedParams.isEmpty()) {
            note += " 其余变化参数已按实际 slice 最优结果做聚合平均。";
        }
        Map<String, Object> heatmap = finalizeHeatmap(title, metric, xParam, yParam, xValueSet, yValueSet, aggregatedParams, points, note);
        heatmap.put("searchMode", "SLICE_BEST_AGGREGATION");
        heatmap.put("gridComplete", 0);
        heatmap.put("viewType", "SLICE_BEST_AGGREGATION");
        return heatmap;
    }

    private Map<String, Object> finalizeHeatmap(String title,
                                                String metric,
                                                String xParam,
                                                String yParam,
                                                Set<String> xValueSet,
                                                Set<String> yValueSet,
                                                List<String> aggregatedParams,
                                                List<HeatmapPoint> points,
                                                String note) {
        Map<String, Object> heatmap = new LinkedHashMap<String, Object>();
        Map<String, BigDecimal> sumByCell = new LinkedHashMap<String, BigDecimal>();
        Map<String, Integer> countByCell = new LinkedHashMap<String, Integer>();
        for (HeatmapPoint point : points) {
            String key = point.xValue + "" + point.yValue;
            sumByCell.put(key, nz(sumByCell.get(key)).add(nz(point.value)));
            countByCell.put(key, nzInt(countByCell.get(key)) + 1);
        }
        List<String> xValues = new ArrayList<String>(xValueSet);
        List<String> yValues = new ArrayList<String>(yValueSet);
        xValues.sort(this::compareParamValueStrings);
        yValues.sort(this::compareParamValueStrings);
        List<Map<String, Object>> cells = new ArrayList<Map<String, Object>>();
        for (String yValue : yValues) {
            for (String xValue : xValues) {
                String key = xValue + "" + yValue;
                Integer count = countByCell.get(key);
                if (count == null || count.intValue() <= 0) {
                    continue;
                }
                BigDecimal avg = nz(sumByCell.get(key)).divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
                Map<String, Object> cell = new LinkedHashMap<String, Object>();
                cell.put("xValue", xValue);
                cell.put("yValue", yValue);
                cell.put("value", scale(avg));
                cell.put("count", count);
                cells.add(cell);
            }
        }
        heatmap.put("title", title);
        heatmap.put("enabled", cells.isEmpty() ? 0 : 1);
        heatmap.put("metric", metric);
        heatmap.put("xParam", xParam);
        heatmap.put("yParam", yParam);
        heatmap.put("xValues", xValues);
        heatmap.put("yValues", yValues);
        heatmap.put("aggregatedParams", new ArrayList<String>(aggregatedParams));
        heatmap.put("cells", cells);
        heatmap.put("gridComplete", (!xValues.isEmpty() && !yValues.isEmpty() && !cells.isEmpty() && aggregatedParams.isEmpty() && cells.size() == xValues.size() * yValues.size()) ? 1 : 0);
        heatmap.put("note", cells.isEmpty() ? "试验记录不足，暂时无法形成热力图。" : note);
        return heatmap;
    }

    private Map<String, Object> buildDisabledHeatmap(String note) {
        Map<String, Object> heatmap = new LinkedHashMap<String, Object>();
        heatmap.put("title", "参数热力图");
        heatmap.put("enabled", 0);
        heatmap.put("metric", "");
        heatmap.put("xParam", "");
        heatmap.put("yParam", "");
        heatmap.put("xValues", Collections.emptyList());
        heatmap.put("yValues", Collections.emptyList());
        heatmap.put("aggregatedParams", Collections.emptyList());
        heatmap.put("cells", Collections.emptyList());
        heatmap.put("searchMode", "UNKNOWN");
        heatmap.put("viewType", "DISABLED");
        heatmap.put("gridComplete", 0);
        heatmap.put("note", note);
        return heatmap;
    }

    private List<Map<String, Object>> buildOptimizationParamParticipation(BacktestResponse response) {
        if (response == null || response.trials == null || response.trials.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Set<String>> distinct = new LinkedHashMap<String, Set<String>>();
        for (BacktestModels.OptimizationTrial trial : response.trials) {
            Map<String, Object> params = parseJsonMap(trial == null ? null : trial.paramSetJson);
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                distinct.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<String>()).add(String.valueOf(entry.getValue()));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Set<String>> entry : distinct.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            List<String> values = new ArrayList<String>(entry.getValue());
            values.sort(this::compareParamValueStrings);
            row.put("name", entry.getKey());
            row.put("valueCount", values.size());
            row.put("values", values);
            row.put("valuesText", StringUtils.join(values, ", "));
            rows.add(row);
        }
        rows.sort((a, b) -> {
            int cmp = Integer.compare(nzInt(b.get("valueCount")), nzInt(a.get("valueCount")));
            if (cmp != 0) {
                return cmp;
            }
            return s(a.get("name")).compareTo(s(b.get("name")));
        });
        return rows;
    }

    private List<Map<String, Object>> buildOptimizationCombinationSummary(BacktestResponse response) {
        if (response == null || response.trials == null || response.trials.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, CombinationAgg> aggMap = new LinkedHashMap<String, CombinationAgg>();
        for (BacktestModels.OptimizationTrial trial : response.trials) {
            String paramJson = defaultIfBlank(trial == null ? null : trial.paramSetJson, "{}");
            CombinationAgg agg = aggMap.computeIfAbsent(paramJson, k -> new CombinationAgg(paramJson));
            agg.count++;
            agg.fitPnl = agg.fitPnl.add(nz(trial == null ? null : trial.fitPnl));
            agg.validatePnl = agg.validatePnl.add(nz(trial == null ? null : trial.validatePnl));
            agg.forwardPnl = agg.forwardPnl.add(nz(trial == null ? null : trial.forwardPnl));
            if (trial != null && gt(trial.fitPnl, BigDecimal.ZERO)) {
                agg.positiveFitCount++;
            }
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (CombinationAgg agg : aggMap.values()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("paramSetJson", agg.paramSetJson);
            row.put("count", agg.count);
            row.put("avgFitPnl", scale(agg.fitPnl.divide(BigDecimal.valueOf(Math.max(1, agg.count)), 6, RoundingMode.HALF_UP)));
            row.put("avgValidatePnl", scale(agg.validatePnl.divide(BigDecimal.valueOf(Math.max(1, agg.count)), 6, RoundingMode.HALF_UP)));
            row.put("avgForwardPnl", scale(agg.forwardPnl.divide(BigDecimal.valueOf(Math.max(1, agg.count)), 6, RoundingMode.HALF_UP)));
            row.put("positiveFitCount", agg.positiveFitCount);
            rows.add(row);
        }
        rows.sort((a, b) -> {
            int cmp = n(b.get("avgFitPnl")).compareTo(n(a.get("avgFitPnl")));
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(nzInt(b.get("count")), nzInt(a.get("count")));
        });
        if (rows.size() > 20) {
            return new ArrayList<Map<String, Object>>(rows.subList(0, 20));
        }
        return rows;
    }

    private Map<String, Object> buildGates(BacktestResponse response,
                                           StrategyCandidateRow candidate,
                                           List<StrategyLiveRegistryPublishRow> activeRows,
                                           StrategyReleaseEventRecord release,
                                           StrategyAutoPublishDecision decision) {
        Map<String, Object> gates = new LinkedHashMap<String, Object>();
        StrategyLiveRegistryPublishRow active = activeRows == null || activeRows.isEmpty() ? null : activeRows.get(0);
        boolean overfitPass = response.overfitPass != null && response.overfitPass.intValue() > 0;
        String overfitReason = StringUtils.defaultIfBlank(response.overfitReason, overfitPass ? "通过过拟合检查" : "未通过过拟合检查");
        boolean publishEligible = decision != null
                ? (decision.publishedCount != null ? decision.publishedCount.intValue() > 0 : decision.published)
                : isPublishEligible(response, candidate);
        String publishReason = decision != null && StringUtils.isNotBlank(decision.reason)
                ? publishReasonDetail(decision, translateReason(decision.reason))
                : publishReason(response, candidate);

        gates.put("overfitPass", overfitPass ? 1 : 0);
        gates.put("overfitReason", translateReason(overfitReason));
        gates.put("publishEligible", publishEligible ? 1 : 0);
        gates.put("publishReason", publishReason);
        gates.put("failedRules", buildFailedRules(response, candidate));
        gates.put("warnings", buildWarnings(response, candidate));

        if (active != null) {
            gates.put("liveRegistryEntered", 1);
            gates.put("liveRegistryReason", "已进入实盘");
            gates.put("liveStrategyLabel", strategyLabel(active.strategyName, active.strategyVersion));
            gates.put("liveEffectiveTime", s(active.effectiveTime));
            gates.put("liveStatus", s(active.status));
            gates.put("liveCount", activeRows == null ? 0 : activeRows.size());
            gates.put("liveSymbols", joinActiveSymbols(activeRows));
            gates.put("liveRegistryDetail", buildLiveRegistryDetail(activeRows));
            return gates;
        }

        gates.put("liveRegistryEntered", 0);
        gates.put("liveStrategyLabel", strategyLabel(response.strategyName, response.strategyVersion));
        gates.put("liveEffectiveTime", "");
        gates.put("liveStatus", "");
        gates.put("liveCount", 0);
        gates.put("liveSymbols", "");

        String liveReason = "";
        if (decision != null && StringUtils.isNotBlank(decision.reason)) {
            liveReason = translateReason(decision.reason);
        } else if (release != null && StringUtils.isNotBlank(release.reason)) {
            liveReason = translateReason(release.reason);
        } else if (!publishEligible) {
            liveReason = publishReason;
        } else {
            liveReason = "尚未执行自动发布";
        }
        gates.put("liveRegistryReason", liveReason);
        gates.put("liveRegistryDetail", liveReason);
        return gates;
    }

    private String publishReasonDetail(StrategyAutoPublishDecision decision, String fallback) {
        if (decision == null) {
            return fallback;
        }
        List<String> parts = new ArrayList<String>();
        if (StringUtils.isNotBlank(fallback)) {
            parts.add(fallback);
        }
        if (decision.publishedSymbols != null && !decision.publishedSymbols.isEmpty()) {
            parts.add("已发布 Symbol: " + joinStrings(decision.publishedSymbols));
        }
        if (decision.skippedSymbols != null && !decision.skippedSymbols.isEmpty()) {
            parts.add("已跳过 Symbol: " + joinStrings(decision.skippedSymbols));
        }
        return parts.isEmpty() ? fallback : StringUtils.join(parts, " / ");
    }

    private String buildLiveRegistryDetail(List<StrategyLiveRegistryPublishRow> activeRows) {
        if (activeRows == null || activeRows.isEmpty()) {
            return "";
        }
        StrategyLiveRegistryPublishRow active = activeRows.get(0);
        StringJoiner joiner = new StringJoiner(" / ");
        joiner.add("写入版本：" + strategyLabel(active.strategyName, active.strategyVersion));
        joiner.add("生效时间：" + s(active.effectiveTime));
        joiner.add("状态：" + s(active.status));
        joiner.add("已写入 Symbol：" + joinActiveSymbols(activeRows));
        return joiner.toString();
    }

    private List<Map<String, Object>> buildResults(BacktestResponse response) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<BacktestResult> items = response.results == null ? Collections.<BacktestResult>emptyList() : new ArrayList<BacktestResult>(response.results);
        items.sort(Comparator.comparing((BacktestResult x) -> s(x.symbol)).thenComparing(x -> s(x.text)));
        for (BacktestResult item : items) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("symbol", s(item.symbol));
            row.put("text", s(item.text));
            row.put("beginDate", s(item.beginDate));
            row.put("endDate", s(item.endDate));
            row.put("summary", buildResultSummary(item));
            row.put("equityCurve", buildEquityCurve(item));
            row.put("sliceDetails", buildSliceDetails(item));
            row.put("tradeDetails", buildTradeDetails(item));
            row.put("rejectReasons", buildRejectReasons(item));
            row.put("symbolReplay", buildReplay(item));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> buildResultSummary(BacktestResult result) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("currency", "USDT");
        summary.put("tradeCount", nzInt(result.tradeCount));
        summary.put("winCount", nzInt(result.winCount));
        summary.put("lossCount", nzInt(result.lossCount));
        summary.put("flatCount", nzInt(result.flatCount));
        summary.put("stopExitCount", nzInt(result.stopExitCount));
        summary.put("takeExitCount", nzInt(result.takeExitCount));
        summary.put("sliceCount", nzInt(result.sliceCount));
        summary.put("symbolCount", nzInt(result.symbolCount));
        summary.put("fitWindowDays", nzInt(result.fitWindowDays));
        summary.put("validateWindowDays", nzInt(result.validateWindowDays));
        summary.put("forwardWindowDays", nzInt(result.forwardWindowDays));
        summary.put("minSliceCount", nzInt(result.minSliceCount));
        summary.put("optimizationMode", s(result.optimizationMode));
        summary.put("optimizationObjective", s(result.optimizationObjective));
        summary.put("minForwardContribution", scale(result.minForwardContribution));
        summary.put("trialCount", nzInt(result.trialCount));
        summary.put("bestRank", nzInt(result.bestRank));
        summary.put("bestParamSetJson", defaultIfBlank(result.bestParamSetJson, "{}"));
        summary.put("elapsedMs", nzInt(result.elapsedMs));
        summary.put("fragileBest", nzInt(result.fragileBest));
        summary.put("stableParamRangeJson", defaultIfBlank(result.stableParamRangeJson, "{}"));
        summary.put("neighborAvgPnl", scale(result.neighborAvgPnl));
        summary.put("neighborWorstPnl", scale(result.neighborWorstPnl));
        summary.put("fitPnl", scale(result.fitPnl));
        summary.put("validatePnl", scale(result.validatePnl));
        summary.put("forwardPnl", scale(result.forwardPnl));
        summary.put("totalPnl", scale(result.totalPnl));
        summary.put("validatePrimaryScore", scale(result.validatePrimaryScore));
        summary.put("forwardAuxScore", scale(result.forwardAuxScore));
        summary.put("feeAdjustedValidatePnl", scale(result.feeAdjustedValidatePnl));
        summary.put("feeAdjustedForwardPnl", scale(result.feeAdjustedForwardPnl));
        summary.put("sliceParamDriftScore", scale(result.sliceParamDriftScore));
        summary.put("oosPass", nzInt(result.oosPass));
        summary.put("forwardScore", scale(result.forwardScore));
        summary.put("finalCapital", scale(result.finalCapital));
        summary.put("winRate", scale(result.winRate));
        summary.put("totalReturnPct", scale(result.totalReturnPct));
        summary.put("maxDrawdownPct", scale(result.maxDrawdownPct));
        summary.put("entryFeeTotal", scale(result.entryFeeTotal));
        summary.put("exitFeeTotal", scale(result.exitFeeTotal));
        summary.put("totalFee", scale(result.totalFee));
        summary.put("entryMakerFeeRatePct", scale(result.entryMakerFeeRatePct));
        summary.put("exitTakerFeeRatePct", scale(result.exitTakerFeeRatePct));
        summary.put("sceneShadow", sceneShadowMap(result.sceneShadow));
        summary.put("fullPeriodSafetyPass", result.fullPeriodSafety != null
                && Boolean.TRUE.equals(result.fullPeriodSafety.passed));
        summary.put("fullPeriodSafetyReason", result.fullPeriodSafety == null
                ? "" : s(result.fullPeriodSafety.reason));
        return summary;
    }

    private Map<String, Object> buildSceneShadow(BacktestResponse response) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int sceneRecords = 0;
        int coveredBars = 0;
        int matchedBars = 0;
        int blockedSignals = 0;
        int forcedExits = 0;
        int trades = 0;
        BigDecimal pnl = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        boolean anyMetrics = false;
        boolean allQualified = true;
        boolean anyInsufficient = false;
        boolean anyQualificationFailed = false;
        boolean anyError = false;
        List<BacktestResult> results = response == null || response.results == null
                ? Collections.<BacktestResult>emptyList() : response.results;
        for (BacktestResult result : results) {
            BacktestModels.SceneShadowMetrics metrics = result == null ? null : result.sceneShadow;
            if (metrics == null) {
                continue;
            }
            anyMetrics = true;
            Map<String, Object> row = sceneShadowMap(metrics);
            row.put("symbol", s(result.symbol));
            rows.add(row);
            sceneRecords += nzInt(metrics.sceneRecordCount);
            coveredBars += nzInt(metrics.coveredBarCount);
            matchedBars += nzInt(metrics.matchedBarCount);
            blockedSignals += nzInt(metrics.blockedSignalCount);
            forcedExits += nzInt(metrics.forcedExitCount);
            trades += nzInt(metrics.tradeCount);
            pnl = pnl.add(nz(metrics.totalPnl));
            fees = fees.add(nz(metrics.totalFee));
            if ("ERROR".equals(metrics.status)) {
                anyError = true;
            } else if ("QUALIFICATION_FAILED".equals(metrics.status)) {
                anyQualificationFailed = true;
                allQualified = false;
            } else if (!"QUALIFIED".equals(metrics.status)) {
                anyInsufficient = true;
                allQualified = false;
            }
        }
        String status = anyError ? "ERROR"
                : !anyMetrics ? "INSUFFICIENT_DATA"
                : anyQualificationFailed ? "QUALIFICATION_FAILED"
                : anyInsufficient ? "INSUFFICIENT_DATA"
                : allQualified ? "QUALIFIED" : "INSUFFICIENT_DATA";
        summary.put("status", status);
        summary.put("rows", rows);
        summary.put("sceneRecordCount", sceneRecords);
        summary.put("coveredBarCount", coveredBars);
        summary.put("matchedBarCount", matchedBars);
        summary.put("blockedSignalCount", blockedSignals);
        summary.put("forcedExitCount", forcedExits);
        summary.put("tradeCount", trades);
        summary.put("totalPnl", scale(pnl));
        summary.put("totalFee", scale(fees));
        return summary;
    }

    private Map<String, Object> sceneShadowMap(BacktestModels.SceneShadowMetrics metrics) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        if (metrics == null) {
            return row;
        }
        row.put("mode", s(metrics.mode));
        row.put("status", s(metrics.status));
        row.put("message", translateSceneShadowMessage(metrics));
        row.put("qualificationPass", Boolean.TRUE.equals(metrics.qualificationPass));
        row.put("qualificationReason", s(metrics.qualificationReason));
        row.put("strategyScene", s(metrics.strategyScene));
        row.put("dataBegin", s(metrics.dataBegin));
        row.put("dataEnd", s(metrics.dataEnd));
        row.put("sceneRecordCount", nzInt(metrics.sceneRecordCount));
        row.put("coveredBarCount", nzInt(metrics.coveredBarCount));
        row.put("matchedBarCount", nzInt(metrics.matchedBarCount));
        row.put("blockedSignalCount", nzInt(metrics.blockedSignalCount));
        row.put("forcedExitCount", nzInt(metrics.forcedExitCount));
        row.put("tradeCount", nzInt(metrics.tradeCount));
        row.put("totalPnl", scale(metrics.totalPnl));
        row.put("totalFee", scale(metrics.totalFee));
        row.put("maxDrawdownPct", scale(metrics.maxDrawdownPct));
        row.put("profitFactor", scale(metrics.profitFactor));
        row.put("winRate", scale(metrics.winRate));
        return row;
    }

    private String translateSceneShadowMessage(BacktestModels.SceneShadowMetrics metrics) {
        if (metrics == null) {
            return "";
        }
        if ("QUALIFIED".equals(metrics.status)) {
            return "\u573a\u666f\u56de\u6d4b\u901a\u8fc7\uff0c\u5019\u9009\u7b56\u7565\u53ef\u8fdb\u5165\u53d1\u5e03\u8d44\u683c\u6bd4\u8f83\u3002";
        }
        if ("QUALIFICATION_FAILED".equals(metrics.status)) {
            return "\u573a\u666f\u56de\u6d4b\u672c\u8eab\u4e0d\u6ee1\u8db3\u53d1\u5e03\u95e8\u69db\uff0c\u5019\u9009\u7b56\u7565\u4e0d\u53ef\u81ea\u52a8\u53d1\u5e03\u3002";
        }
        if ("NOT_APPLICABLE".equals(metrics.status)) {
            return "\u8be5\u573a\u666f\u7684\u5386\u53f2\u6837\u672c\u5c1a\u672a\u8fbe\u5230\u53ef\u9a8c\u8bc1\u6761\u4ef6\u3002";
        }
        if ("ERROR".equals(metrics.status)) {
            return "\u573a\u666f\u5185\u89c2\u5bdf\u56de\u6d4b\u672a\u5b8c\u6210\uff0c\u4e0d\u5f71\u54cd\u4e3b\u56de\u6d4b\u7ed3\u8bba\u3002";
        }
        return "\u573a\u666f\u5386\u53f2\u6216\u573a\u666f\u5185\u4ea4\u6613\u6570\u4e0d\u8db3\uff0c\u5f53\u524d\u53ea\u8bb0\u5f55\u3001\u4e0d\u4f5c\u51c6\u5165\u5224\u65ad\u3002";
    }

    private List<Map<String, Object>> buildEquityCurve(BacktestResult result) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<EquityPoint> curve = result.equityCurve == null ? Collections.<EquityPoint>emptyList() : result.equityCurve;
        if (!curve.isEmpty()) {
            for (EquityPoint point : curve) {
                rows.add(equityPoint(displayTime(point.time), point.equity, point.deltaPnl, point.cumulativePnl));
            }
            return rows;
        }
        BigDecimal initial = scale(result.initialCapital);
        rows.add(equityPoint(displayTime(result.beginDate), initial, BigDecimal.ZERO, BigDecimal.ZERO));
        BigDecimal cumulative = BigDecimal.ZERO;
        List<TradeRecord> trades = result.tradeList == null ? Collections.<TradeRecord>emptyList() : result.tradeList;
        for (TradeRecord trade : trades) {
            cumulative = cumulative.add(nz(trade.pnl));
            BigDecimal equity = trade.equityAfter == null ? initial.add(cumulative) : trade.equityAfter;
            rows.add(equityPoint(displayTime(trade.exitTime), equity, trade.pnl, trade.cumulativePnl == null ? cumulative : trade.cumulativePnl));
        }
        if (trades.isEmpty()) {
            rows.add(equityPoint(displayTime(result.endDate), scale(result.finalCapital), BigDecimal.ZERO, BigDecimal.ZERO));
        }
        return rows;
    }

    private Map<String, Object> equityPoint(String time, BigDecimal equity, BigDecimal deltaPnl, BigDecimal cumulativePnl) {
        Map<String, Object> point = new LinkedHashMap<String, Object>();
        point.put("time", time);
        point.put("equity", scale(equity));
        point.put("deltaPnl", scale(deltaPnl));
        point.put("cumulativePnl", scale(cumulativePnl));
        point.put("currency", "USDT");
        return point;
    }

    private List<Map<String, Object>> buildSliceDetails(BacktestResult result) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (result.sliceResults == null) {
            return rows;
        }
        for (BacktestSliceResult slice : result.sliceResults) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("sliceNo", nzInt(slice.sliceNo));
            row.put("fitBegin", s(slice.fitBegin));
            row.put("fitEnd", s(slice.fitEnd));
            row.put("validateBegin", s(slice.validateBegin));
            row.put("validateEnd", s(slice.validateEnd));
            row.put("forwardBegin", s(slice.forwardBegin));
            row.put("forwardEnd", s(slice.forwardEnd));
            row.put("fitPnl", scale(slice.fitPnl));
            row.put("validatePnl", scale(slice.validatePnl));
            row.put("forwardPnl", scale(slice.forwardPnl));
            row.put("bestParamSetJson", defaultIfBlank(slice.bestParamSetJson, "{}"));
            row.put("fitScore", scale(slice.fitScore));
            row.put("validateScore", scale(slice.validateScore));
            row.put("forwardScore", scale(slice.forwardScore));
            row.put("selectionObjective", s(slice.selectionObjective));
            row.put("fragileBest", nzInt(slice.fragileBest));
            row.put("fitTradeCount", nzInt(slice.fitTradeCount));
            row.put("validateTradeCount", nzInt(slice.validateTradeCount));
            row.put("forwardTradeCount", nzInt(slice.forwardTradeCount));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildTradeDetails(BacktestResult result) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<TradeRecord> trades = result.tradeList == null ? Collections.<TradeRecord>emptyList() : result.tradeList;
        int seq = 1;
        for (TradeRecord trade : trades) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("tradeNo", trade.tradeNo == null ? seq : trade.tradeNo);
            row.put("symbol", s(result.symbol));
            row.put("text", s(result.text));
            row.put("side", s(trade.side));
            row.put("signalTime", displayTime(trade.signalTime));
            row.put("signalPrice", scale(trade.signalPrice));
            row.put("entryTime", displayTime(trade.entryTime));
            row.put("entryPrice", scale(trade.entryPrice));
            row.put("exitTime", displayTime(trade.exitTime));
            row.put("exitPrice", scale(trade.exitPrice));
            row.put("stopPrice", scale(trade.stopPrice));
            row.put("takePrice", scale(trade.takePrice));
            row.put("qty", scale(trade.qty));
            row.put("holdBars", nzInt(trade.holdBars));
            row.put("entryReason", s(trade.entryReason));
            row.put("exitReason", s(trade.exitReason));
            row.put("grossReturnPct", scale(trade.grossReturnPct));
            row.put("returnPct", scale(trade.returnPct));
            row.put("pnl", scale(trade.pnl));
            row.put("entryFeeRatePct", scale(trade.entryFeeRatePct));
            row.put("exitFeeRatePct", scale(trade.exitFeeRatePct));
            row.put("entryFee", scale(trade.entryFee));
            row.put("exitFee", scale(trade.exitFee));
            row.put("totalFee", scale(trade.totalFee));
            row.put("equityAfter", scale(trade.equityAfter));
            row.put("cumulativePnl", scale(trade.cumulativePnl));
            row.put("currency", "USDT");
            rows.add(row);
            seq++;
        }
        return rows;
    }

    private List<Map<String, Object>> buildRejectReasons(BacktestResult result) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (result.rejectReasonCounts == null) {
            return rows;
        }
        for (Map.Entry<String, Integer> entry : result.rejectReasonCounts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("reason", translateReason(s(entry.getKey())));
            row.put("count", nzInt(entry.getValue()));
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, Object> item) -> (Integer) item.get("count")).reversed());
        return rows;
    }

    private Map<String, Object> buildReplay(BacktestResult result) {
        Map<String, Object> replay = new LinkedHashMap<String, Object>();
        ReplayWindow window = replayWindow(result);
        replay.put("securityID", s(result.symbol));
        replay.put("text", s(result.text));
        replay.put("replayBegin", window.begin);
        replay.put("replayEnd", window.end);
        replay.put("phaseWindow", buildPhaseWindow(window));
        List<TTbookOhlc> rawCandles = backtestQueryService.queryOhlc(result.symbol, result.text, window.begin, window.end);
        List<TTbookOhlc> candlesForChart = shrinkCandles(rawCandles, REPLAY_MAX_CANDLES);

        List<Map<String, Object>> candles = new ArrayList<Map<String, Object>>();
        for (TTbookOhlc row : candlesForChart) {
            Map<String, Object> candle = new LinkedHashMap<String, Object>();
            candle.put("time", displayTime(row.starttime));
            candle.put("open", scale(row.open));
            candle.put("high", scale(row.high));
            candle.put("low", scale(row.low));
            candle.put("close", scale(row.close));
            candles.add(candle);
        }

        List<Map<String, Object>> opens = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> closes = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> stops = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> takes = new ArrayList<Map<String, Object>>();
        List<TradeRecord> trades = result.tradeList == null ? Collections.<TradeRecord>emptyList() : result.tradeList;
        for (TradeRecord trade : trades) {
            if (!inReplayWindow(window, trade.entryTime) && !inReplayWindow(window, trade.exitTime)) {
                continue;
            }
            opens.add(replayPoint("open", trade.entryTime, trade.entryPrice, trade));
            closes.add(replayPoint("close", trade.exitTime, trade.exitPrice, trade));
            if (trade.stopPrice != null && trade.stopPrice.compareTo(BigDecimal.ZERO) > 0) {
                stops.add(replayPoint("stop", trade.entryTime, trade.stopPrice, trade));
            }
            if (trade.takePrice != null && trade.takePrice.compareTo(BigDecimal.ZERO) > 0) {
                takes.add(replayPoint("take", trade.entryTime, trade.takePrice, trade));
            }
        }

        replay.put("candles", candles);
        replay.put("openPoints", opens);
        replay.put("closePoints", closes);
        replay.put("stopLines", stops);
        replay.put("takeLines", takes);
        return replay;
    }

    private Map<String, Object> replayPoint(String type, String time, BigDecimal price, TradeRecord trade) {
        Map<String, Object> point = new LinkedHashMap<String, Object>();
        point.put("type", type);
        point.put("time", displayTime(time));
        point.put("price", scale(price));
        point.put("side", s(trade.side));
        point.put("tradeNo", trade.tradeNo == null ? 0 : trade.tradeNo);
        point.put("label", buildReplayLabel(type, trade));
        return point;
    }

    private String buildReplayLabel(String type, TradeRecord trade) {
        if ("open".equals(type)) {
            return "\u5f00\u4ed3#" + (trade.tradeNo == null ? "" : trade.tradeNo);
        }
        if ("close".equals(type)) {
            return "\u5e73\u4ed3#" + (trade.tradeNo == null ? "" : trade.tradeNo);
        }
        if ("stop".equals(type)) {
            return "\u6b62\u635f\u4ef7";
        }
        if ("take".equals(type)) {
            return "\u6b62\u76c8\u4ef7";
        }
        return type;
    }

    private ReplayWindow replayWindow(BacktestResult result) {
        if (result != null && result.sliceResults != null && !result.sliceResults.isEmpty()) {
            BacktestSliceResult last = result.sliceResults.get(result.sliceResults.size() - 1);
            String fitBegin = defaultIfBlank(last.fitBegin, result.beginDate);
            String fitEnd = defaultIfBlank(last.fitEnd, fitBegin);
            String validateBegin = defaultIfBlank(last.validateBegin, fitEnd);
            String validateEnd = defaultIfBlank(last.validateEnd, validateBegin);
            String forwardBegin = defaultIfBlank(last.forwardBegin, validateEnd);
            String forwardEnd = defaultIfBlank(last.forwardEnd, result.endDate);
            return new ReplayWindow(fitBegin, forwardEnd, fitBegin, fitEnd, validateBegin, validateEnd, forwardBegin, forwardEnd);
        }
        String begin = s(result == null ? null : result.beginDate);
        String end = s(result == null ? null : result.endDate);
        return new ReplayWindow(begin, end, begin, end, begin, end, begin, end);
    }

    private Map<String, Object> buildPhaseWindow(ReplayWindow window) {
        Map<String, Object> phaseWindow = new LinkedHashMap<String, Object>();
        if (window == null) {
            return phaseWindow;
        }
        phaseWindow.put("fitBegin", s(window.fitBegin));
        phaseWindow.put("fitEnd", s(window.fitEnd));
        phaseWindow.put("validateBegin", s(window.validateBegin));
        phaseWindow.put("validateEnd", s(window.validateEnd));
        phaseWindow.put("forwardBegin", s(window.forwardBegin));
        phaseWindow.put("forwardEnd", s(window.forwardEnd));
        return phaseWindow;
    }
    private boolean inReplayWindow(ReplayWindow window, String time) {
        if (window == null || StringUtils.isBlank(time)) {
            return false;
        }
        long ts = toEpochMillis(time);
        long begin = toEpochMillis(window.begin);
        long end = toEpochMillis(window.end);
        if (ts < 0 || begin < 0 || end < 0) {
            return true;
        }
        return ts >= begin && ts <= end + 86_400_000L;
    }

    private List<TTbookOhlc> shrinkCandles(List<TTbookOhlc> candles, int limit) {
        if (candles == null || candles.size() <= limit) {
            return candles == null ? Collections.<TTbookOhlc>emptyList() : candles;
        }
        List<TTbookOhlc> rows = new ArrayList<TTbookOhlc>();
        int step = (int) Math.ceil((double) candles.size() / (double) limit);
        for (int i = 0; i < candles.size(); i += step) {
            rows.add(candles.get(i));
        }
        TTbookOhlc tail = candles.get(candles.size() - 1);
        if (rows.isEmpty() || rows.get(rows.size() - 1) != tail) {
            rows.add(tail);
        }
        return rows;
    }

    private String buildHtml(Map<String, Object> report) {
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) report.get("reportMeta");
        @SuppressWarnings("unchecked")
        Map<String, Object> tracking = (Map<String, Object>) report.get("tracking");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> userSummary = (Map<String, Object>) report.get("userSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> gates = (Map<String, Object>) report.get("gates");
        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) report.get("audit");
        @SuppressWarnings("unchecked")
        Map<String, Object> publish = (Map<String, Object>) report.get("publish");
        @SuppressWarnings("unchecked")
        Map<String, Object> optimization = (Map<String, Object>) report.get("optimization");
        @SuppressWarnings("unchecked")
        Map<String, Object> sceneShadow = (Map<String, Object>) report.get("sceneShadow");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) report.get("results");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">")
                .append("<title>").append(escape(s(meta.get("strategyLabel")))).append(" \u56de\u6d4b\u62a5\u544a</title>")
                .append("<style>")
                .append("body{font-family:'Microsoft YaHei',sans-serif;background:#f5f7fb;color:#1f2937;margin:0;padding:24px;word-break:break-word;}")
                .append(".page{max-width:1440px;margin:0 auto;}")
                .append(".hero{background:linear-gradient(135deg,#0f172a,#1d4ed8);color:#fff;border-radius:20px;padding:28px 32px;margin-bottom:20px;}")
                .append(".hero h1{margin:0 0 8px;font-size:30px;}")
                .append(".hero p{margin:6px 0 0;color:#dbeafe;}")
                .append(".verdict{display:grid;grid-template-columns:minmax(280px,1.35fr) repeat(4,minmax(130px,.65fr));gap:12px;margin:18px 0;}")
                .append(".verdict-main{background:#fff;border:1px solid #dbeafe;border-radius:18px;padding:22px;box-shadow:0 12px 32px rgba(15,23,42,.08);}")
                .append(".verdict-main .metric-value{font-size:30px;line-height:1.25;}")
                .append(".plain-list{margin:14px 0 0;padding-left:20px;color:#475569;line-height:1.8;}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:14px;margin:18px 0;}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:16px;padding:18px;box-shadow:0 8px 24px rgba(15,23,42,.06);}")
                .append(".card h3{margin:0 0 10px;font-size:16px;color:#111827;}")
                .append(".metric-label{font-size:12px;color:#6b7280;margin-bottom:8px;}")
                .append(".metric-value{font-size:26px;font-weight:700;}")
                .append(".pass{color:#047857;}.fail{color:#b91c1c;}.warn{color:#b45309;}")
                .append(".section{margin-top:22px;}")
                .append(".section h2{margin:0 0 14px;font-size:22px;}")
                .append(".muted{color:#6b7280;}")
                .append(".table-wrap{overflow:auto;-webkit-overflow-scrolling:touch;border:1px solid #e5e7eb;border-radius:14px;background:#fff;}")
                .append("table{width:100%;min-width:720px;border-collapse:collapse;font-size:13px;}")
                .append("th,td{padding:10px 12px;border-bottom:1px solid #eef2f7;text-align:left;white-space:nowrap;}")
                .append("th{background:#f8fafc;color:#475569;position:sticky;top:0;}")
                .append("tr:nth-child(even) td{background:#fcfdff;}")
                .append(".svg-box{background:#fff;border:1px solid #e5e7eb;border-radius:16px;padding:12px;}")
                .append(".tips{background:#fff7ed;border:1px solid #fdba74;color:#9a3412;border-radius:14px;padding:14px 16px;}")
                .append("details.fold{background:#fff;border:1px solid #e5e7eb;border-radius:14px;padding:0 14px;margin-top:12px;}")
                .append("details.fold summary{cursor:pointer;list-style:none;padding:14px 4px;font-weight:700;color:#0f172a;}")
                .append("details.fold summary::-webkit-details-marker{display:none;}")
                .append("details.fold[open] summary{border-bottom:1px solid #eef2f7;}")
                .append(".fold-body{padding:14px 0 6px;}")
                .append("@media (max-width: 768px){")
                .append("body{padding:12px;}")
                .append(".page{max-width:none;}")
                .append(".hero{padding:18px 18px 16px;border-radius:16px;}")
                .append(".hero h1{font-size:22px;line-height:1.3;}")
                .append(".hero p{font-size:13px;line-height:1.6;}")
                .append(".section{margin-top:16px;}")
                .append(".section h2{font-size:18px;margin-bottom:10px;}")
                .append(".grid{grid-template-columns:1fr;gap:10px;margin:12px 0;}")
                .append(".verdict{grid-template-columns:1fr 1fr;}")
                .append(".verdict-main{grid-column:1/-1;padding:18px;}")
                .append(".card{padding:14px;border-radius:14px;}")
                .append(".metric-value{font-size:22px;}")
                .append(".table-wrap{border-radius:12px;}")
                .append("table{min-width:640px;font-size:12px;}")
                .append("th,td{padding:8px 10px;}")
                .append(".svg-box{padding:8px;border-radius:12px;overflow:auto;}")
                .append("svg{max-width:100%;height:auto;display:block;}")
                .append("}")
                .append("</style></head><body><div class=\"page\">");

        html.append("<div class=\"hero\"><h1>")
                .append(escape(s(meta.get("strategyLabel"))))
                .append(" \u4e2d\u6587\u56de\u6d4b\u62a5\u544a</h1><p>\u6807\u7684\uff1a")
                .append(escape(joinSymbols(meta.get("symbols"))))
                .append(" / \u5468\u671f\uff1a")
                .append(escape(s(meta.get("text"))))
                .append(" / \u65f6\u95f4\u7a97\u53e3\uff1a")
                .append(escape(s(meta.get("beginDate"))))
                .append(" ~ ")
                .append(escape(s(meta.get("endDate"))))
                .append(" / \u6a21\u5f0f\uff1a")
                .append(escape(s(meta.get("windowMode"))))
                .append("</p><p>\u751f\u6210\u65f6\u95f4\uff1a")
                .append(escape(s(meta.get("generatedAt"))))
                .append("</p></div>");

        html.append(renderSimpleUserSummarySection(userSummary));
        html.append(renderSceneShadowSection(sceneShadow));

        html.append(detailsBlock("工程追踪（默认收起）",
                new StringBuilder("<div class=\"table-wrap\"><table><thead><tr>")
                .append("<th>\u5b57\u6bb5</th><th>\u503c</th></tr></thead><tbody>")
                .append(trackingRow("\u7b56\u7565\u540d", tracking.get("strategyName")))
                .append(trackingRow("\u7248\u672c\u53f7", tracking.get("strategyVersion")))
                .append(trackingRow("SID", tracking.get("sid")))
                .append(trackingRow("Candidate ID", tracking.get("candidateId")))
                .append(trackingRow("Pipeline Run ID", tracking.get("pipelineRunId")))
                .append(trackingRow("\u573a\u666f", tracking.get("scene")))
                .append(trackingRow("\u7b56\u7565\u903b\u8f91\u63cf\u8ff0", tracking.get("candidateDescription")))
                .append(trackingRow("\u751f\u6210\u7c7b\u578b", tracking.get("generationType")))
                .append(trackingRow("\u8fd0\u884c\u7c7b\u578b", tracking.get("runtimeType")))
                .append(trackingRow("\u5f53\u524d Live \u7248\u672c", tracking.get("currentLiveVersion")))
                .append(trackingRow("\u5f53\u524d Live \u751f\u6548\u65f6\u95f4", tracking.get("currentLiveEffectiveTime")))
                .append(trackingRow("\u6700\u65b0\u53d1\u5e03\u4e8b\u4ef6", tracking.get("releaseEventType")))
                .append(trackingRow("\u53d1\u5e03\u4e8b\u4ef6\u539f\u56e0", tracking.get("releaseEventReason")))
                .append(trackingRow("\u81ea\u52a8\u53d1\u5e03\u5224\u5b9a", tracking.get("publishDecisionReason")))
                .append(trackingRow("\u5df2\u53d1\u5e03 Symbol \u6570", tracking.get("publishedCount")))
                .append(trackingRow("\u5df2\u53d1\u5e03 Symbol", tracking.get("publishedSymbols")))
                .append(trackingRow("\u8df3\u8fc7 Symbol \u6570", tracking.get("skippedCount")))
                .append(trackingRow("\u8df3\u8fc7 Symbol", tracking.get("skippedSymbols")))
                .append("</tbody></table></div>").toString(), false));

        StringBuilder auditBlock = new StringBuilder();
        auditBlock.append("<div class=\"grid\">")
                .append(statusCard("\u5ba1\u6838\u7ed3\u8bba", s(audit.get("finalDecisionLabel")), s(audit.get("summary")), auditDecisionClass(s(audit.get("finalDecision")))))
                .append(statusCard("\u53c2\u6570\u8d28\u91cf", s(((Map<String, Object>) optimization.get("quality")).get("statusLabel")), s(((Map<String, Object>) optimization.get("quality")).get("message")), optimizationQualityClass(s(((Map<String, Object>) optimization.get("quality")).get("status")))))
                .append("</div>")
                .append("<div class=\"tips\">\u8bf4\u660e\uff1a\u5ba1\u6838\u7ed3\u8bba\u6309\u7ec4\u5408/\u6574\u4f53\u56de\u6d4b\u4efb\u52a1\u7ed9\u51fa\uff1b\u5b9e\u76d8\u53d1\u5e03\u5219\u6309 Symbol \u72ec\u7acb\u5224\u5b9a\uff0c\u56e0\u6b64\u53ef\u80fd\u51fa\u73b0\u201c\u5ba1\u6838\u5931\u8d25\u6216\u89c2\u5bdf\uff0c\u4f46\u5df2\u53d1\u5e03\u90e8\u5206 Symbol\u201d\u7684\u60c5\u51b5\u3002</div>")
                .append(renderAuditTable(audit));
        html.append(detailsBlock("审核细节（默认收起）", auditBlock.toString(), false));

        html.append(detailsBlock("按品种发布细节（默认收起）", renderPublishDecisionTable(publish), false));

        StringBuilder metricDetailBlock = new StringBuilder();
        metricDetailBlock.append("<div class=\"grid\">")
                .append(statusCard("\u8fc7\u62df\u5408\u68c0\u67e5", isTrue(gates.get("overfitPass")) ? "\u901a\u8fc7\u8fc7\u62df\u5408\u68c0\u67e5" : "\u672a\u901a\u8fc7\u8fc7\u62df\u5408\u68c0\u67e5", s(gates.get("overfitReason")), isTrue(gates.get("overfitPass")) ? "pass" : "fail"))
                .append(statusCard("\u76c8\u5229\u53d1\u5e03\u95e8\u69db", isTrue(gates.get("publishEligible")) ? "\u6ee1\u8db3\u4e0a\u7ebf\u524d\u76c8\u5229\u95e8\u69db" : "\u4e0d\u6ee1\u8db3\u4e0a\u7ebf\u524d\u76c8\u5229\u95e8\u69db", s(gates.get("publishReason")), isTrue(gates.get("publishEligible")) ? "pass" : "warn"))
                .append(statusCard("\u5b9e\u76d8\u51c6\u5165\u7ed3\u679c", isTrue(gates.get("liveRegistryEntered")) ? "\u5df2\u8fdb\u5165\u5b9e\u76d8" : "\u672a\u8fdb\u5165\u5b9e\u76d8", s(gates.get("liveRegistryDetail")), isTrue(gates.get("liveRegistryEntered")) ? "pass" : "fail"))
                .append(metric("Fit \u6536\u76ca", summary.get("fitPnl")))
                .append(metric("Validate \u6536\u76ca", summary.get("validatePnl")))
                .append(metric("Forward \u6536\u76ca", summary.get("forwardPnl")))
                .append(metric("\u6263\u8d39 Validate \u6536\u76ca", summary.get("feeAdjustedValidatePnl")))
                .append(metric("\u6263\u8d39 Forward \u6536\u76ca", summary.get("feeAdjustedForwardPnl")))
                .append(metric("Validate \u4e3b\u5206", summary.get("validatePrimaryScore")))
                .append(metric("Forward \u8f85\u5206", summary.get("forwardAuxScore")))
                .append(metric("OOS \u901a\u8fc7", isTrue(summary.get("oosPass")) ? "\u662f" : "\u5426"))
                .append(metric("Forward Score", summary.get("forwardScore")))
                .append(metric("\u7a97\u53e3\u914d\u7f6e", s(summary.get("fitWindowDays")) + "/" + s(summary.get("validateWindowDays")) + "/" + s(summary.get("forwardWindowDays"))))
                .append(metric("\u6700\u5c0f Slice", summary.get("minSliceCount")))
                .append(metric("\u53c2\u6570\u6f02\u79fb", summary.get("sliceParamDriftScore")))
                .append(metric("\u8bd5\u9a8c\u8017\u65f6(ms)", summary.get("elapsedMs")))
                .append(metric("Validate \u4ea4\u6613\u7b14\u6570", summary.get("tradeCount")))
                .append(metric("Validate \u6700\u5927\u56de\u64a4", summary.get("maxDrawdownPct")))
                .append(metric("\u603b\u624b\u7eed\u8d39", summary.get("totalFee")))
                .append("</div>");
        html.append(detailsBlock("\u5ba1\u6838\u548c\u6307\u6807\u8be6\u60c5\uff08\u9ed8\u8ba4\u6536\u8d77\uff09", metricDetailBlock.toString(), false));

        StringBuilder optimizationBlock = new StringBuilder();
        optimizationBlock.append("<div class=\"grid\">")
                .append(metric("\u4f18\u5316\u6a21\u5f0f", optimization.get("optimizationMode")))
                .append(metric("\u4f18\u5316\u76ee\u6807", optimization.get("optimizationObjective")))
                .append(metric("\u4f18\u5316\u8bc1\u636e", optimization.get("evidenceStatus")))
                .append(metric("Trial \u6570", optimization.get("trialCount")))
                .append(metric("\u6700\u4f73\u6392\u540d", optimization.get("bestRank")))
                .append(metric("Forward \u8d21\u732e\u95e8\u69db", optimization.get("minForwardContribution")))
                .append(metric("\u6700\u4f73\u70b9\u8106\u5f31", isTrue(optimization.get("fragileBest")) ? "\u662f" : "\u5426"))
                .append(metric("OOS \u901a\u8fc7", isTrue(summary.get("oosPass")) ? "\u662f" : "\u5426"))
                .append(metric("\u53c2\u6570\u6f02\u79fb", summary.get("sliceParamDriftScore")))
                .append(metric("\u90bb\u57df\u5747\u503c\u6536\u76ca", optimization.get("neighborAvgPnl")))
                .append(metric("\u90bb\u57df\u6700\u5dee\u6536\u76ca", optimization.get("neighborWorstPnl")))
                .append(metric("\u6700\u4f73\u53c2\u6570\u96c6", compactJsonValue(optimization.get("bestParamSetJson"))))
                .append(metric("\u7a33\u5b9a\u53c2\u6570\u533a\u95f4", compactJsonValue(optimization.get("stableParamRangeJson"))))
                .append("</div>")
                .append(renderOptimizationQuality(optimization))
                .append(renderOptimizationParticipation(optimization))
                .append(renderHeatmapSection(optimization))
                .append(renderOptimizationCombinationSummary(optimization))
                .append(renderOptimizationTrialsV2(optimization));
        html.append(detailsBlock("参数搜索和优化细节（默认收起）", optimizationBlock.toString(), false));

        html.append(detailsBlock("\u5931\u8d25\u9879\u548c\u8865\u5145\u63d0\u793a\uff08\u9ed8\u8ba4\u6536\u8d77\uff09",
                "<div class=\"tips\">"
                        + (isTrue(gates.get("liveRegistryEntered")) ? "\u8be5\u7b56\u7565\u5df2\u6ee1\u8db3\u56de\u6d4b\u4e0e\u53d1\u5e03\u95e8\u69db\uff0c\u5e76\u5df2\u8fdb\u5165 live_registry\u3002" : "\u8be5\u7b56\u7565\u672a\u8fdb\u5165\u5b9e\u76d8\uff0c\u539f\u56e0\uff1a" + escape(s(gates.get("liveRegistryReason"))))
                        + "<br/>"
                        + "\u4f18\u5316\u8bc1\u636e\uff1a" + escape(s(optimization.get("evidenceMessage")))
                        + renderStringList("\u672a\u901a\u8fc7\u9879", (List<String>) gates.get("failedRules"))
                        + renderStringList("\u63d0\u793a", (List<String>) gates.get("warnings"))
                        + "</div>",
                false));

        for (Map<String, Object> item : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> itemSummary = (Map<String, Object>) item.get("summary");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> equityCurve = (List<Map<String, Object>>) item.get("equityCurve");
            @SuppressWarnings("unchecked")
            Map<String, Object> replay = (Map<String, Object>) item.get("symbolReplay");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> slices = (List<Map<String, Object>>) item.get("sliceDetails");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trades = (List<Map<String, Object>>) item.get("tradeDetails");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rejectReasons = (List<Map<String, Object>>) item.get("rejectReasons");
            @SuppressWarnings("unchecked")
            Map<String, Object> phaseWindow = replay == null ? null : (Map<String, Object>) replay.get("phaseWindow");

            html.append("<div class=\"section\"><h2>").append(escape(s(item.get("symbol")))).append(" / ").append(escape(s(item.get("text")))).append("</h2><div class=\"grid\">")
                    .append(metric("\u6263\u8d39\u540e\u9a8c\u8bc1\u6536\u76ca USDT", itemSummary.get("feeAdjustedValidatePnl")))
                    .append(metric("\u672a\u6765\u65f6\u6bb5\u6536\u76ca USDT", itemSummary.get("feeAdjustedForwardPnl")))
                    .append(metric("\u80dc\u7387", percentText(itemSummary.get("winRate"))))
                    .append(metric("\u6700\u5927\u56de\u64a4", percentText(itemSummary.get("maxDrawdownPct"))))
                    .append("</div></div>");

            html.append(detailsBlock(s(item.get("symbol")) + " 图形和切片细节（默认收起）",
                    "<div class=\"section\"><h2>\u8d26\u6237\u4f59\u989d\u53d8\u52a8\u66f2\u7ebf</h2><div class=\"svg-box\">"
                            + renderEquitySvg(equityCurve, phaseWindow)
                            + "</div></div>"
                            + "<div class=\"section\"><h2>\u5355\u54c1\u79cd\u56fe\u5f62\u590d\u76d8</h2><div class=\"svg-box\">"
                            + renderReplaySvg(replay)
                            + "</div></div>"
                            + "<div class=\"section\"><h2>Walk-forward \u5207\u7247\u660e\u7ec6</h2>"
                            + renderTable(new String[]{"\u5207\u7247", "Fit \u5f00\u59cb", "Fit \u7ed3\u675f", "Validate \u6536\u76ca", "Forward \u6536\u76ca", "Fit \u9009\u53c2\u5206", "Validate \u5206", "\u6700\u4f73\u53c2\u6570", "\u8106\u5f31", "\u9009\u53c2\u76ee\u6807"}, slices, new String[]{"sliceNo", "fitBegin", "fitEnd", "validatePnl", "forwardPnl", "fitScore", "validateScore", "bestParamSetJson", "fragileBest", "selectionObjective"})
                            + "<div class=\"section\"><h2>\u5355\u7b14\u4ea4\u6613\u660e\u7ec6</h2>"
                            + renderTable(new String[]{"\u7f16\u53f7", "\u65b9\u5411", "\u5f00\u4ed3\u65f6\u95f4", "\u5f00\u4ed3\u4ef7", "\u5e73\u4ed3\u65f6\u95f4", "\u5e73\u4ed3\u4ef7", "\u6536\u76ca", "\u624b\u7eed\u8d39", "\u9000\u51fa\u539f\u56e0"}, trades, new String[]{"tradeNo", "side", "entryTime", "entryPrice", "exitTime", "exitPrice", "pnl", "totalFee", "exitReason"})
                            + "<div class=\"section\"><h2>\u62d2\u5355\u539f\u56e0\u7edf\u8ba1</h2>"
                            + renderTable(new String[]{"\u539f\u56e0", "\u6b21\u6570"}, rejectReasons, new String[]{"reason", "count"}),
                    false));
        }

        html.append("</div></body></html>");
        return html.toString();
    }
    private String statusCard(String title, String value, String desc, String clazz) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"card\"><h3>").append(escape(title)).append("</h3>")
                .append("<div class=\"metric-value ").append(clazz).append("\">").append(escape(value)).append("</div>")
                .append("<div class=\"muted\" style=\"margin-top:10px;line-height:1.7;\">").append(escape(desc)).append("</div>")
                .append("</div>");
        return html.toString();
    }

    private String trackingRow(String label, Object value) {
        return "<tr><td>" + escape(label) + "</td><td>" + safeCell(value) + "</td></tr>";
    }

    private String metric(String label, Object value) {
        return "<div class=\"card\"><div class=\"metric-label\">" + escape(label)
                + "</div><div class=\"metric-value\">" + escape(s(value))
                + "</div></div>";
    }

    @SuppressWarnings("unchecked")
    private String renderAuditTable(Map<String, Object> audit) {
        if (audit == null) {
            return "";
        }
        List<Map<String, Object>> checks = (List<Map<String, Object>>) audit.get("checks");
        return renderTable(
                new String[]{"检查项", "规则", "实际值", "结果", "说明"},
                checks,
                new String[]{"name", "rule", "actual", "statusLabel", "message"});
    }

    @SuppressWarnings("unchecked")
    private String renderOptimizationQuality(Map<String, Object> optimization) {
        if (optimization == null) {
            return "";
        }
        Map<String, Object> quality = (Map<String, Object>) optimization.get("quality");
        if (quality == null) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"section\"><h2>参数质量检查</h2><div class=\"grid\">")
                .append(statusCard("参数质量状态",
                        s(quality.get("status")),
                        s(quality.get("message")),
                        optimizationQualityClass(s(quality.get("status")))))
                .append("</div>");
        List<String> reasons = (List<String>) quality.get("reasons");
        if (reasons != null && !reasons.isEmpty()) {
            html.append(renderStringList("质量说明", reasons));
        }
        html.append("</div>");
        return html.toString();
    }

    @SuppressWarnings("unchecked")
    private String renderHeatmapSection(Map<String, Object> optimization) {
        if (optimization == null) {
            return "";
        }
        List<Map<String, Object>> heatmaps = (List<Map<String, Object>>) optimization.get("heatmaps");
        if (heatmaps == null || heatmaps.isEmpty()) {
            Map<String, Object> heatmap = (Map<String, Object>) optimization.get("heatmap");
            if (heatmap == null) {
                return "";
            }
            heatmaps = Collections.singletonList(heatmap);
        }
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"section\"><h2>参数热力图区</h2>");
        for (Map<String, Object> heatmap : heatmaps) {
            if (heatmap == null) {
                continue;
            }
            String title = defaultIfBlank(s(heatmap.get("title")), "参数热力图");
            if (!isTrue(heatmap.get("enabled"))) {
                html.append("<div class=\"card\" style=\"margin-bottom:12px;\"><h3>")
                        .append(escape(title))
                        .append("</h3><div class=\"tips\">")
                        .append(escape(s(heatmap.get("note"))))
                        .append("</div></div>");
                continue;
            }
            html.append("<div class=\"card\" style=\"margin-bottom:12px;\"><h3>")
                    .append(escape(title))
                    .append("</h3>")
                    .append("<div class=\"muted\" style=\"margin-bottom:10px;\">搜索模式：")
                    .append(escape(s(heatmap.get("searchMode"))))
                    .append(" / 完整网格：")
                    .append(isTrue(heatmap.get("gridComplete")) ? "Y" : "N")
                    .append("</div>")
                    .append("<div class=\"muted\" style=\"margin-bottom:10px;\">指标：")
                    .append(escape(s(heatmap.get("metric"))))
                    .append(" / X：")
                    .append(escape(s(heatmap.get("xParam"))))
                    .append(" / Y：")
                    .append(escape(s(heatmap.get("yParam"))))
                    .append("</div>")
                    .append(StringUtils.isBlank(s(heatmap.get("note"))) ? "" : "<div class=\"muted\" style=\"margin-bottom:10px;\">" + escape(s(heatmap.get("note"))) + "</div>")
                    .append(renderHeatmapMatrix(heatmap))
                    .append("</div>");
        }
        html.append("</div>");
        return html.toString();
    }

    @SuppressWarnings("unchecked")
    private String renderOptimizationParticipation(Map<String, Object> optimization) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) optimization.get("paramParticipation");
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        return "<div class=\"section\"><h2>参与优化参数</h2>"
                + "<div class=\"muted\" style=\"margin-bottom:10px;\">这里展示本次实际参与回测优化的参数，以及每个参数实际出现过的取值集合。</div>"
                + renderTable(
                new String[]{"参数名", "取值数量", "实际取值"},
                rows,
                new String[]{"name", "valueCount", "valuesText"})
                + "</div>";
    }

    @SuppressWarnings("unchecked")
    private String renderOptimizationCombinationSummary(Map<String, Object> optimization) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) optimization.get("combinationSummary");
        if (rows == null || rows.isEmpty()) {
            return "<div class=\"section\"><h2>参数组合结果表</h2><div class=\"tips\">本次未产出可用参数组合结果。</div></div>";
        }
        return "<div class=\"section\"><h2>参数组合结果表</h2>"
                + "<div class=\"muted\" style=\"margin-bottom:10px;\">这里按完整参数组合汇总本次实际 trial 结果，帮助我们看到哪些参数组合真正参与了回测，以及大致表现区间。</div>"
                + renderTable(
                new String[]{"参数组合", "出现次数", "平均 Fit", "平均 Validate", "平均 Forward", "正 Fit 次数"},
                rows,
                new String[]{"paramSetJson", "count", "avgFitPnl", "avgValidatePnl", "avgForwardPnl", "positiveFitCount"})
                + "</div>";
    }

    @SuppressWarnings("unchecked")
    private String renderHeatmapMatrix(Map<String, Object> heatmap) {
        List<String> xValues = (List<String>) heatmap.get("xValues");
        List<String> yValues = (List<String>) heatmap.get("yValues");
        List<Map<String, Object>> cells = (List<Map<String, Object>>) heatmap.get("cells");
        if (xValues == null || xValues.isEmpty() || yValues == null || yValues.isEmpty() || cells == null || cells.isEmpty()) {
            return "<div class=\"tips\">热力图数据不足。</div>";
        }
        Map<String, Map<String, Object>> cellMap = new LinkedHashMap<String, Map<String, Object>>();
        BigDecimal maxAbs = BigDecimal.ZERO;
        for (Map<String, Object> cell : cells) {
            String key = s(cell.get("xValue")) + "\u0001" + s(cell.get("yValue"));
            cellMap.put(key, cell);
            maxAbs = maxAbs.max(n(cell.get("value")).abs());
        }
        if (maxAbs.compareTo(BigDecimal.ZERO) == 0) {
            maxAbs = BigDecimal.ONE;
        }
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"table-wrap\"><table><thead><tr><th>")
                .append(escape(s(heatmap.get("yParam"))))
                .append(" \\ ")
                .append(escape(s(heatmap.get("xParam"))))
                .append("</th>");
        for (String x : xValues) {
            html.append("<th>").append(escape(x)).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (String y : yValues) {
            html.append("<tr><td>").append(escape(y)).append("</td>");
            for (String x : xValues) {
                Map<String, Object> cell = cellMap.get(x + "\u0001" + y);
                if (cell == null) {
                    html.append("<td class=\"muted\">-</td>");
                    continue;
                }
                BigDecimal value = n(cell.get("value"));
                int count = nzInt(cell.get("count"));
                html.append("<td style=\"background:").append(heatColor(value, maxAbs)).append(";\">")
                        .append(escape(scale(value).toPlainString()))
                        .append("<div class=\"muted\">n=").append(count).append("</div></td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table></div>");
        return html.toString();
    }

    private static final class CombinationAgg {
        private final String paramSetJson;
        private int count;
        private int positiveFitCount;
        private BigDecimal fitPnl = BigDecimal.ZERO;
        private BigDecimal validatePnl = BigDecimal.ZERO;
        private BigDecimal forwardPnl = BigDecimal.ZERO;

        private CombinationAgg(String paramSetJson) {
            this.paramSetJson = paramSetJson;
        }
    }

    private static final class HeatmapPoint {
        private final String xValue;
        private final String yValue;
        private final BigDecimal value;

        private HeatmapPoint(String xValue, String yValue, BigDecimal value) {
            this.xValue = xValue;
            this.yValue = yValue;
            this.value = value == null ? BigDecimal.ZERO : value;
        }
    }

    private String heatColor(BigDecimal value, BigDecimal maxAbs) {
        if (value == null || maxAbs == null || maxAbs.compareTo(BigDecimal.ZERO) <= 0) {
            return "#f8fafc";
        }
        double ratio = Math.min(1D, value.abs().divide(maxAbs, 6, RoundingMode.HALF_UP).doubleValue());
        if (value.compareTo(BigDecimal.ZERO) > 0) {
            int red = (int) Math.round(236 - 42 * ratio);
            int green = (int) Math.round(253 - 18 * ratio);
            int blue = (int) Math.round(243 - 86 * ratio);
            return String.format(Locale.US, "#%02x%02x%02x", clampColor(red), clampColor(green), clampColor(blue));
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            int red = (int) Math.round(254 - 18 * ratio);
            int green = (int) Math.round(242 - 84 * ratio);
            int blue = (int) Math.round(242 - 70 * ratio);
            return String.format(Locale.US, "#%02x%02x%02x", clampColor(red), clampColor(green), clampColor(blue));
        }
        return "#f8fafc";
    }

    private int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private String renderTable(String[] headers, List<Map<String, Object>> rows, String[] keys) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"table-wrap\"><table><thead><tr>");
        for (String header : headers) {
            html.append("<th>").append(escape(header)).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        if (rows == null || rows.isEmpty()) {
            html.append("<tr><td colspan=\"").append(headers.length).append("\">\u6682\u65e0\u6570\u636e</td></tr>");
        } else {
            for (Map<String, Object> row : rows) {
                html.append("<tr>");
                for (String key : keys) {
                    html.append("<td>").append(safeCell(row.get(key))).append("</td>");
                }
                html.append("</tr>");
            }
        }
        html.append("</tbody></table></div>");
        return html.toString();
    }

    @SuppressWarnings("unchecked")
    private String renderPublishDecisionTable(Map<String, Object> publish) {
        if (publish == null) {
            return "";
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) publish.get("rows");
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"section\"><h2>按 Symbol 发布结果</h2>")
                .append("<div class=\"muted\" style=\"margin-bottom:10px;\">这里展示每个 Symbol 的独立发布判定结果。组合级审核失败或观察，并不妨碍其中部分 Symbol 满足门槛并进入实盘。</div>")
                .append("<div class=\"table-wrap\"><table><thead><tr>")
                .append("<th>Symbol</th><th>发布结果</th><th>原因</th><th>Validate收益</th><th>Forward收益</th><th>Validate主分</th><th>交易数</th><th>最大回撤</th><th>ProfitFactor</th><th>最佳参数集</th>")
                .append("</tr></thead><tbody>");
        for (Map<String, Object> row : rows) {
            html.append("<tr>")
                    .append("<td>").append(safeCell(row.get("symbol"))).append("</td>")
                    .append("<td><span class=\"").append(escape(s(row.get("publishStatusClass")))).append("\">").append(safeCell(row.get("publishStatus"))).append("</span></td>")
                    .append("<td>").append(safeCell(row.get("reason"))).append("</td>")
                    .append("<td>").append(safeCell(row.get("validatePnl"))).append("</td>")
                    .append("<td>").append(safeCell(row.get("forwardPnl"))).append("</td>")
                    .append("<td>").append(safeCell(row.get("validatePrimaryScore"))).append("</td>")
                    .append("<td>").append(safeCell(row.get("validateTradeCount"))).append("</td>")
                    .append("<td>").append(safeCell(row.get("validateMaxDrawdownPct"))).append("</td>")
                    .append("<td>").append(safeCell(row.get("validateProfitFactor"))).append("</td>")
                    .append("<td>").append(safeCell(compactJsonValue(row.get("bestParamSetJson")))).append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table></div></div>");
        return html.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendPublishDecisionMarkdown(StringBuilder md, Map<String, Object> publish) {
        if (publish == null) {
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) publish.get("rows");
        if (rows == null || rows.isEmpty()) {
            return;
        }
        md.append("\\n## \u6309 Symbol \u53d1\u5e03\u7ed3\u679c\\n\\n");
        md.append("> \u8bf4\u660e\uff1a\u7ec4\u5408\u7ea7\u5ba1\u6838\u4e0e Symbol \u7ea7\u53d1\u5e03\u662f\u4e24\u5c42\u53e3\u5f84\uff0c\u6240\u4ee5\u53ef\u80fd\u51fa\u73b0\u201c\u5ba1\u6838\u5931\u8d25/\u89c2\u5bdf\uff0c\u4f46\u5df2\u53d1\u5e03\u90e8\u5206 Symbol\u201d\u3002\\n\\n");
        md.append("| Symbol | 发布结果 | 原因 | Validate收益 | Forward收益 | Validate主分 | 交易数 | 最大回撤 | ProfitFactor | 最佳参数集 |\\n");
        md.append("|---|---|---|---:|---:|---:|---:|---:|---:|---|\\n");
        for (Map<String, Object> row : rows) {
            md.append("| ").append(s(row.get("symbol")))
                    .append(" | ").append(s(row.get("publishStatus")))
                    .append(" | ").append(s(row.get("reason")))
                    .append(" | ").append(s(row.get("validatePnl")))
                    .append(" | ").append(s(row.get("forwardPnl")))
                    .append(" | ").append(s(row.get("validatePrimaryScore")))
                    .append(" | ").append(s(row.get("validateTradeCount")))
                    .append(" | ").append(s(row.get("validateMaxDrawdownPct")))
                    .append(" | ").append(s(row.get("validateProfitFactor")))
                    .append(" | `").append(s(row.get("bestParamSetJson"))).append("` |\\n");
        }
    }

    private String renderEquitySvg(List<Map<String, Object>> points, Map<String, Object> phaseWindow) {
        if (points == null || points.isEmpty()) {
            return "<div class=\"muted\">\u6682\u65e0\u6743\u76ca\u66f2\u7ebf\u6570\u636e</div>";
        }
        int width = 1180;
        int height = 320;
        int paddingLeft = 60;
        int paddingRight = 20;
        int paddingTop = 40;
        int paddingBottom = 40;
        BigDecimal min = null;
        BigDecimal max = null;
        for (Map<String, Object> point : points) {
            BigDecimal v = n(point.get("equity"));
            min = min == null ? v : min.min(v);
            max = max == null ? v : max.max(v);
        }
        if (min == null || max == null) {
            min = BigDecimal.ZERO;
            max = BigDecimal.ONE;
        }
        if (min.compareTo(max) == 0) {
            max = max.add(BigDecimal.ONE);
        }
        StringBuilder polyline = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            Map<String, Object> point = points.get(i);
            double x = projectX(i, points.size(), width, paddingLeft, paddingRight);
            double y = projectY(n(point.get("equity")), min, max, height, paddingTop, paddingBottom);
            if (polyline.length() > 0) {
                polyline.append(" ");
            }
            polyline.append(format(x)).append(",").append(format(y));
        }
        String startLabel = safeCell(points.get(0).get("time"));
        String endLabel = safeCell(points.get(points.size() - 1).get("time"));
        long rangeBegin = toEpochMillis(s(points.get(0).get("time")));
        long rangeEnd = toEpochMillis(s(points.get(points.size() - 1).get("time")));

        return "<svg viewBox=\"0 0 " + width + " " + height + "\" width=\"100%\" height=\"320\">"
                + "<rect x=\"0\" y=\"0\" width=\"" + width + "\" height=\"" + height + "\" fill=\"#ffffff\"/>"
                + renderPhaseDecorationsByTime(phaseWindow, rangeBegin, rangeEnd, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom)
                + "<line x1=\"" + paddingLeft + "\" y1=\"" + (height - paddingBottom) + "\" x2=\"" + (width - paddingRight) + "\" y2=\"" + (height - paddingBottom) + "\" stroke=\"#cbd5e1\"/>"
                + "<line x1=\"" + paddingLeft + "\" y1=\"" + paddingTop + "\" x2=\"" + paddingLeft + "\" y2=\"" + (height - paddingBottom) + "\" stroke=\"#cbd5e1\"/>"
                + "<polyline fill=\"none\" stroke=\"#2563eb\" stroke-width=\"3\" points=\"" + polyline + "\"/>"
                + "<text x=\"" + paddingLeft + "\" y=\"" + (height - 12) + "\" font-size=\"12\" fill=\"#64748b\">" + startLabel + "</text>"
                + "<text x=\"" + (width - 220) + "\" y=\"" + (height - 12) + "\" font-size=\"12\" fill=\"#64748b\">" + endLabel + "</text>"
                + "<text x=\"10\" y=\"" + paddingTop + "\" font-size=\"12\" fill=\"#64748b\">" + safeCell(scale(max)) + " USDT</text>"
                + "<text x=\"10\" y=\"" + (height - paddingBottom) + "\" font-size=\"12\" fill=\"#64748b\">" + safeCell(scale(min)) + " USDT</text>"
                + "</svg>";
    }

    @SuppressWarnings("unchecked")
    private String renderReplaySvg(Map<String, Object> replay) {
        if (replay == null) {
            return "<div class=\"muted\">\u6682\u65e0\u56fe\u5f62\u590d\u76d8\u6570\u636e</div>";
        }
        List<Map<String, Object>> candles = (List<Map<String, Object>>) replay.get("candles");
        if (candles == null || candles.isEmpty()) {
            return "<div class=\"muted\">\u6682\u65e0\u56fe\u5f62\u590d\u76d8\u6570\u636e</div>";
        }

        int width = 1180;
        int height = 420;
        int paddingLeft = 70;
        int paddingRight = 20;
        int paddingTop = 40;
        int paddingBottom = 40;

        BigDecimal min = null;
        BigDecimal max = null;
        for (Map<String, Object> candle : candles) {
            BigDecimal high = n(candle.get("high"));
            BigDecimal low = n(candle.get("low"));
            min = min == null ? low : min.min(low);
            max = max == null ? high : max.max(high);
        }
        List<Map<String, Object>> openPoints = (List<Map<String, Object>>) replay.get("openPoints");
        List<Map<String, Object>> closePoints = (List<Map<String, Object>>) replay.get("closePoints");
        List<Map<String, Object>> stopLines = (List<Map<String, Object>>) (replay.containsKey("stopLines") ? replay.get("stopLines") : replay.get("stopPoints"));
        List<Map<String, Object>> takeLines = (List<Map<String, Object>>) (replay.containsKey("takeLines") ? replay.get("takeLines") : replay.get("takePoints"));
        @SuppressWarnings("unchecked")
        Map<String, Object> phaseWindow = (Map<String, Object>) replay.get("phaseWindow");

        for (List<Map<String, Object>> marks : new List[]{openPoints, closePoints, stopLines, takeLines}) {
            if (marks == null) {
                continue;
            }
            for (Map<String, Object> mark : marks) {
                BigDecimal price = n(mark.get("price"));
                min = min == null ? price : min.min(price);
                max = max == null ? price : max.max(price);
            }
        }
        if (min == null || max == null) {
            min = BigDecimal.ZERO;
            max = BigDecimal.ONE;
        }
        if (min.compareTo(max) == 0) {
            max = max.add(BigDecimal.ONE);
        }

        StringBuilder body = new StringBuilder();
        double candleWidth = Math.max(2D, ((double) (width - paddingLeft - paddingRight) / Math.max(1, candles.size())) * 0.7D);
        for (int i = 0; i < candles.size(); i++) {
            Map<String, Object> candle = candles.get(i);
            double x = projectX(i, candles.size(), width, paddingLeft, paddingRight);
            BigDecimal open = n(candle.get("open"));
            BigDecimal high = n(candle.get("high"));
            BigDecimal low = n(candle.get("low"));
            BigDecimal close = n(candle.get("close"));
            double yHigh = projectY(high, min, max, height, paddingTop, paddingBottom);
            double yLow = projectY(low, min, max, height, paddingTop, paddingBottom);
            double yOpen = projectY(open, min, max, height, paddingTop, paddingBottom);
            double yClose = projectY(close, min, max, height, paddingTop, paddingBottom);
            String color = close.compareTo(open) >= 0 ? "#16a34a" : "#dc2626";
            double rectY = Math.min(yOpen, yClose);
            double rectH = Math.max(1D, Math.abs(yOpen - yClose));
            body.append("<line x1=\"").append(format(x)).append("\" y1=\"").append(format(yHigh)).append("\" x2=\"").append(format(x)).append("\" y2=\"").append(format(yLow)).append("\" stroke=\"").append(color).append("\" stroke-width=\"1.2\"/>");
            body.append("<rect x=\"").append(format(x - candleWidth / 2D)).append("\" y=\"").append(format(rectY)).append("\" width=\"").append(format(candleWidth)).append("\" height=\"").append(format(rectH)).append("\" fill=\"").append(color).append("\" opacity=\"0.75\"/>");
        }

        long rangeBegin = toEpochMillis(candleTime(candles.get(0)));
        long rangeEnd = toEpochMillis(candleTime(candles.get(candles.size() - 1)));
        StringBuilder marks = new StringBuilder();
        renderReplayMarks(marks, candles, stopLines, min, max, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom, "#dc2626");
        renderReplayMarks(marks, candles, takeLines, min, max, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom, "#7c3aed");
        renderReplayMarks(marks, candles, openPoints, min, max, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom, "#2563eb");
        renderReplayMarks(marks, candles, closePoints, min, max, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom, "#f59e0b");

        String startLabel = safeCell(candleTime(candles.get(0)));
        String endLabel = safeCell(candleTime(candles.get(candles.size() - 1)));
        return "<svg viewBox=\"0 0 " + width + " " + height + "\" width=\"100%\" height=\"420\">"
                + "<rect x=\"0\" y=\"0\" width=\"" + width + "\" height=\"" + height + "\" fill=\"#ffffff\"/>"
                + renderPhaseDecorationsByTime(phaseWindow, rangeBegin, rangeEnd, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom)
                + "<line x1=\"" + paddingLeft + "\" y1=\"" + (height - paddingBottom) + "\" x2=\"" + (width - paddingRight) + "\" y2=\"" + (height - paddingBottom) + "\" stroke=\"#cbd5e1\"/>"
                + "<line x1=\"" + paddingLeft + "\" y1=\"" + paddingTop + "\" x2=\"" + paddingLeft + "\" y2=\"" + (height - paddingBottom) + "\" stroke=\"#cbd5e1\"/>"
                + body + marks
                + "<text x=\"" + paddingLeft + "\" y=\"" + (height - 12) + "\" font-size=\"12\" fill=\"#64748b\">" + startLabel + "</text>"
                + "<text x=\"" + (width - 220) + "\" y=\"" + (height - 12) + "\" font-size=\"12\" fill=\"#64748b\">" + endLabel + "</text>"
                + "<text x=\"10\" y=\"" + paddingTop + "\" font-size=\"12\" fill=\"#64748b\">" + safeCell(scale(max)) + "</text>"
                + "<text x=\"10\" y=\"" + (height - paddingBottom) + "\" font-size=\"12\" fill=\"#64748b\">" + safeCell(scale(min)) + "</text>"
                + "</svg>";
    }

    private void renderReplayMarks(StringBuilder svg,
                                   List<Map<String, Object>> candles,
                                   List<Map<String, Object>> marks,
                                   BigDecimal min,
                                   BigDecimal max,
                                   int width,
                                   int height,
                                   int paddingLeft,
                                   int paddingRight,
                                   int paddingTop,
                                   int paddingBottom,
                                   String color) {
        if (marks == null || marks.isEmpty()) {
            return;
        }
        for (Map<String, Object> mark : marks) {
            int idx = findNearestIndex(candles, s(mark.get("time")));
            double x = projectX(idx, candles.size(), width, paddingLeft, paddingRight);
            double y = projectY(n(mark.get("price")), min, max, height, paddingTop, paddingBottom);
            String tooltip = safeCell(mark.get("label")) + " / " + safeCell(mark.get("time")) + " / " + safeCell(mark.get("price"));
            svg.append("<g><title>").append(tooltip).append("</title><circle cx=\"").append(format(x)).append("\" cy=\"").append(format(y)).append("\" r=\"4\" fill=\"").append(color).append("\"/></g>");
        }
    }

    private String renderPhaseDecorationsByTime(Map<String, Object> phaseWindow,
                                                long rangeBegin,
                                                long rangeEnd,
                                                int width,
                                                int height,
                                                int paddingLeft,
                                                int paddingRight,
                                                int paddingTop,
                                                int paddingBottom) {
        if (phaseWindow == null || phaseWindow.isEmpty() || rangeBegin < 0 || rangeEnd < 0 || rangeBegin >= rangeEnd) {
            return "";
        }
        StringBuilder svg = new StringBuilder();
        appendPhaseBand(svg, phaseWindow, "fitBegin", "fitEnd", "Fit", "#dbeafe", "#93c5fd", rangeBegin, rangeEnd, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom);
        appendPhaseBand(svg, phaseWindow, "validateBegin", "validateEnd", "Validate", "#ffedd5", "#fdba74", rangeBegin, rangeEnd, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom);
        appendPhaseBand(svg, phaseWindow, "forwardBegin", "forwardEnd", "Forward", "#dcfce7", "#86efac", rangeBegin, rangeEnd, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom);
        appendPhaseBoundary(svg, phaseWindow.get("validateBegin"), rangeBegin, rangeEnd, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom);
        appendPhaseBoundary(svg, phaseWindow.get("forwardBegin"), rangeBegin, rangeEnd, width, height, paddingLeft, paddingRight, paddingTop, paddingBottom);
        svg.append("<g font-size=\"12\" fill=\"#475569\">");
        svg.append("<rect x=\"").append(width - 290).append("\" y=\"10\" width=\"10\" height=\"10\" fill=\"#dbeafe\" stroke=\"#93c5fd\"/>").append("<text x=\"").append(width - 274).append("\" y=\"19\">Fit</text>");
        svg.append("<rect x=\"").append(width - 220).append("\" y=\"10\" width=\"10\" height=\"10\" fill=\"#ffedd5\" stroke=\"#fdba74\"/>").append("<text x=\"").append(width - 204).append("\" y=\"19\">Validate</text>");
        svg.append("<rect x=\"").append(width - 122).append("\" y=\"10\" width=\"10\" height=\"10\" fill=\"#dcfce7\" stroke=\"#86efac\"/>").append("<text x=\"").append(width - 106).append("\" y=\"19\">Forward</text>");
        svg.append("</g>");
        return svg.toString();
    }

    private void appendPhaseBand(StringBuilder svg,
                                 Map<String, Object> phaseWindow,
                                 String beginKey,
                                 String endKey,
                                 String label,
                                 String fill,
                                 String stroke,
                                 long rangeBegin,
                                 long rangeEnd,
                                 int width,
                                 int height,
                                 int paddingLeft,
                                 int paddingRight,
                                 int paddingTop,
                                 int paddingBottom) {
        long phaseBegin = toEpochMillis(s(phaseWindow.get(beginKey)));
        long phaseEnd = toEpochMillis(s(phaseWindow.get(endKey)));
        if (phaseBegin < 0 || phaseEnd < 0 || phaseEnd <= rangeBegin || phaseBegin >= rangeEnd) {
            return;
        }
        long clippedBegin = Math.max(phaseBegin, rangeBegin);
        long clippedEnd = Math.min(phaseEnd, rangeEnd);
        double x1 = projectTimeX(clippedBegin, rangeBegin, rangeEnd, width, paddingLeft, paddingRight);
        double x2 = projectTimeX(clippedEnd, rangeBegin, rangeEnd, width, paddingLeft, paddingRight);
        if (x2 < x1) {
            double swap = x1;
            x1 = x2;
            x2 = swap;
        }
        double plotHeight = height - paddingTop - paddingBottom;
        svg.append("<rect x=\"").append(format(x1)).append("\" y=\"").append(paddingTop).append("\" width=\"").append(format(Math.max(1D, x2 - x1))).append("\" height=\"").append(format(plotHeight)).append("\" fill=\"").append(fill).append("\" stroke=\"").append(stroke).append("\" stroke-width=\"1\" opacity=\"0.35\"/>");
        svg.append("<text x=\"").append(format(x1 + 6D)).append("\" y=\"").append(paddingTop + 16).append("\" font-size=\"12\" fill=\"#334155\">").append(label).append("</text>");
    }

    private void appendPhaseBoundary(StringBuilder svg,
                                     Object boundaryTime,
                                     long rangeBegin,
                                     long rangeEnd,
                                     int width,
                                     int height,
                                     int paddingLeft,
                                     int paddingRight,
                                     int paddingTop,
                                     int paddingBottom) {
        long boundary = toEpochMillis(s(boundaryTime));
        if (boundary < 0 || boundary <= rangeBegin || boundary >= rangeEnd) {
            return;
        }
        double x = projectTimeX(boundary, rangeBegin, rangeEnd, width, paddingLeft, paddingRight);
        svg.append("<line x1=\"").append(format(x)).append("\" y1=\"").append(paddingTop).append("\" x2=\"").append(format(x)).append("\" y2=\"").append(height - paddingBottom).append("\" stroke=\"#64748b\" stroke-width=\"1.5\" stroke-dasharray=\"5 4\"/>");
    }

    private String candleTime(Map<String, Object> candle) {
        if (candle == null) {
            return "";
        }
        String time = s(candle.get("time"));
        if (StringUtils.isNotBlank(time)) {
            return time;
        }
        return displayTime(s(candle.get("start_time")));
    }
    private int findNearestIndex(List<Map<String, Object>> candles, String time) {
        if (candles == null || candles.isEmpty()) {
            return 0;
        }
        long target = toEpochMillis(time);
        if (target < 0) {
            return 0;
        }
        int best = 0;
        long bestGap = Long.MAX_VALUE;
        for (int i = 0; i < candles.size(); i++) {
            long current = toEpochMillis(s(candles.get(i).get("time")));
            long gap = Math.abs(current - target);
            if (gap < bestGap) {
                bestGap = gap;
                best = i;
            }
        }
        return best;
    }

    private String buildMarkdown(Map<String, Object> report) {
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) report.get("reportMeta");
        @SuppressWarnings("unchecked")
        Map<String, Object> tracking = (Map<String, Object>) report.get("tracking");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> userSummary = (Map<String, Object>) report.get("userSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> gates = (Map<String, Object>) report.get("gates");
        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) report.get("audit");
        @SuppressWarnings("unchecked")
        Map<String, Object> publish = (Map<String, Object>) report.get("publish");
        @SuppressWarnings("unchecked")
        Map<String, Object> optimization = (Map<String, Object>) report.get("optimization");

        StringBuilder md = new StringBuilder();
        md.append("# ").append(s(meta.get("strategyLabel"))).append(" \u56de\u6d4b\u62a5\u544a\\n\\n");
        md.append("- \u6807\u7684\uff1a").append(joinSymbols(meta.get("symbols"))).append("\\n");
        md.append("- \u5468\u671f\uff1a").append(s(meta.get("text"))).append("\\n");
        md.append("- \u65f6\u95f4\u8303\u56f4\uff1a").append(s(meta.get("beginDate"))).append(" ~ ").append(s(meta.get("endDate"))).append("\\n");
        md.append("- SID\uff1a").append(s(tracking.get("sid"))).append("\\n");
        md.append("- Candidate ID\uff1a").append(s(tracking.get("candidateId"))).append("\\n");
        md.append("- Pipeline Run ID\uff1a").append(s(tracking.get("pipelineRunId"))).append("\\n");
        md.append("- \u573a\u666f\uff1a").append(s(tracking.get("scene"))).append("\\n");
        md.append("- \u7b56\u7565\u903b\u8f91\u63cf\u8ff0\uff1a").append(s(tracking.get("candidateDescription"))).append("\\n");
        md.append("- \u751f\u6210\u7c7b\u578b\uff1a").append(s(tracking.get("generationType"))).append("\\n");
        md.append("- \u8fd0\u884c\u7c7b\u578b\uff1a").append(s(tracking.get("runtimeType"))).append("\\n");
        md.append("- \u5f53\u524d Live \u7248\u672c\uff1a").append(s(tracking.get("currentLiveVersion"))).append("\\n");
        md.append("- \u6700\u65b0\u53d1\u5e03\u4e8b\u4ef6\uff1a").append(s(tracking.get("releaseEventType"))).append("\\n");
        md.append("- \u5df2\u53d1\u5e03 Symbol\uff1a").append(s(tracking.get("publishedSymbols"))).append(" (").append(s(tracking.get("publishedCount"))).append(")\\n");
        md.append("- \u8df3\u8fc7 Symbol\uff1a").append(s(tracking.get("skippedSymbols"))).append(" (").append(s(tracking.get("skippedCount"))).append(")\\n");
        md.append("- \u8fc7\u62df\u5408\u68c0\u67e5\uff1a").append(isTrue(gates.get("overfitPass")) ? "\u901a\u8fc7" : "\u672a\u901a\u8fc7")
                .append("\uff1b\u539f\u56e0\uff1a").append(s(gates.get("overfitReason"))).append("\\n");
        md.append("- \u76c8\u5229\u53d1\u5e03\u95e8\u69db\uff1a").append(isTrue(gates.get("publishEligible")) ? "\u6ee1\u8db3" : "\u4e0d\u6ee1\u8db3")
                .append("\uff1b\u539f\u56e0\uff1a").append(s(gates.get("publishReason"))).append("\\n");
        md.append("- \u5b9e\u76d8\u51c6\u5165\u7ed3\u679c\uff1a").append(isTrue(gates.get("liveRegistryEntered")) ? "\u5df2\u8fdb\u5165\u5b9e\u76d8" : "\u672a\u8fdb\u5165\u5b9e\u76d8")
                .append("\uff1b\u539f\u56e0\uff1a").append(s(gates.get("liveRegistryDetail"))).append("\\n");
        md.append("- \u8bf4\u660e\uff1a\u5ba1\u6838\u7ed3\u8bba\u6309\u7ec4\u5408/\u6574\u4f53\u56de\u6d4b\u4efb\u52a1\u7ed9\u51fa\uff1b\u5b9e\u76d8\u53d1\u5e03\u6309 Symbol \u72ec\u7acb\u5224\u5b9a\uff0c\u56e0\u6b64\u53ef\u80fd\u51fa\u73b0\u201c\u5ba1\u6838\u5931\u8d25\u6216\u89c2\u5bdf\uff0c\u4f46\u5df2\u53d1\u5e03\u90e8\u5206 Symbol\u201d\u3002\\n\\n");
        appendUserSummaryMarkdown(md, userSummary);
        md.append("## \u6c47\u603b\u6307\u6807\\n\\n");
        md.append("| \u6307\u6807 | \u6570\u503c |\\n|---|---|\\n");
        md.append("| Fit \u6536\u76ca | ").append(s(summary.get("fitPnl"))).append(" |\\n");
        md.append("| Validate \u6536\u76ca | ").append(s(summary.get("validatePnl"))).append(" |\\n");
        md.append("| Forward \u6536\u76ca | ").append(s(summary.get("forwardPnl"))).append(" |\\n");
        md.append("| \u6263\u8d39 Forward \u6536\u76ca | ").append(s(summary.get("feeAdjustedForwardPnl"))).append(" |\\n");
        md.append("| Validate \u4e3b\u5206 | ").append(s(summary.get("validatePrimaryScore"))).append(" |\\n");
        md.append("| Forward \u8f85\u5206 | ").append(s(summary.get("forwardAuxScore"))).append(" |\\n");
        md.append("| \u6263\u8d39 Validate \u6536\u76ca | ").append(s(summary.get("feeAdjustedValidatePnl"))).append(" |\\n");
        md.append("| OOS \u901a\u8fc7 | ").append(isTrue(summary.get("oosPass")) ? "Y" : "N").append(" |\\n");
        md.append("| \u53c2\u6570\u6f02\u79fb | ").append(s(summary.get("sliceParamDriftScore"))).append(" |\\n");
        md.append("| Forward Score | ").append(s(summary.get("forwardScore"))).append(" |\\n");
        md.append("| \u603b\u624b\u7eed\u8d39 | ").append(s(summary.get("totalFee"))).append(" |\\n");
        md.append("| \u4f18\u5316\u6a21\u5f0f | ").append(s(optimization.get("optimizationMode"))).append(" |\\n");
        md.append("| \u4f18\u5316\u8bc1\u636e | ").append(s(optimization.get("evidenceStatus"))).append(" |\\n");
        md.append("| Trial \u6570 | ").append(s(optimization.get("trialCount"))).append(" |\\n");
        md.append("| \u6700\u4f73\u6392\u540d | ").append(s(optimization.get("bestRank"))).append(" |\\n");
        md.append("| \u6700\u4f73\u70b9\u8106\u5f31 | ").append(isTrue(optimization.get("fragileBest")) ? "Y" : "N").append(" |\\n");
        md.append("| \u6700\u4f73\u53c2\u6570\u96c6 | `").append(s(optimization.get("bestParamSetJson"))).append("` |\\n");
        md.append("| \u4f18\u5316\u8bc1\u636e\u8bf4\u660e | ").append(s(optimization.get("evidenceMessage"))).append(" |\\n");
        md.append("\\n## \u5ba1\u6838\u68c0\u67e5\\n\\n");
        md.append("- \u5ba1\u6838\u7ed3\u8bba\uff1a").append(s(audit.get("finalDecisionLabel"))).append("\\n");
        md.append("- \u7ed3\u8bba\u6458\u8981\uff1a").append(s(audit.get("summary"))).append("\\n\\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) audit.get("checks");
        if (checks != null && !checks.isEmpty()) {
            md.append("| \u68c0\u67e5\u9879 | \u89c4\u5219 | \u5b9e\u9645\u503c | \u7ed3\u679c | \u8bf4\u660e |\\n|---|---|---|---|---|\\n");
            for (Map<String, Object> check : checks) {
                md.append("| ").append(s(check.get("name")))
                        .append(" | ").append(s(check.get("rule")))
                        .append(" | ").append(s(check.get("actual")))
                        .append(" | ").append(s(check.get("statusLabel")))
                        .append(" | ").append(s(check.get("message")))
                        .append(" |\\n");
            }
        }
        appendPublishDecisionMarkdown(md, publish);
        @SuppressWarnings("unchecked")
        Map<String, Object> quality = (Map<String, Object>) optimization.get("quality");
        if (quality != null) {
            md.append("\\n## \u53c2\u6570\u8d28\u91cf\\n\\n");
            md.append("- \u72b6\u6001\uff1a").append(s(quality.get("status"))).append("\\n");
            md.append("- \u8bf4\u660e\uff1a").append(s(quality.get("message"))).append("\\n");
            @SuppressWarnings("unchecked")
            List<String> qualityReasons = (List<String>) quality.get("reasons");
            if (qualityReasons != null && !qualityReasons.isEmpty()) {
                for (String item : qualityReasons) {
                    md.append("  - ").append(item).append("\\n");
                }
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> participation = (List<Map<String, Object>>) optimization.get("paramParticipation");
        if (participation != null && !participation.isEmpty()) {
            md.append("\\n## 参与优化参数\\n\\n");
            md.append("| 参数名 | 取值数量 | 实际取值 |\\n|---|---:|---|\\n");
            for (Map<String, Object> row : participation) {
                md.append("| ").append(s(row.get("name")))
                        .append(" | ").append(s(row.get("valueCount")))
                        .append(" | ").append(s(row.get("valuesText")))
                        .append(" |\\n");
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heatmaps = (List<Map<String, Object>>) optimization.get("heatmaps");
        if ((heatmaps == null || heatmaps.isEmpty()) && optimization.get("heatmap") instanceof Map) {
            heatmaps = Collections.singletonList((Map<String, Object>) optimization.get("heatmap"));
        }
        if (heatmaps != null && !heatmaps.isEmpty()) {
            md.append("\\n## \u53c2\u6570\u70ed\u529b\u56fe\\u533a\\n\\n");
            for (Map<String, Object> heatmap : heatmaps) {
                if (heatmap == null) {
                    continue;
                }
                md.append("### ").append(s(heatmap.get("title"))).append("\\n\\n");
                md.append("- \u542f\u7528\uff1a").append(isTrue(heatmap.get("enabled")) ? "Y" : "N").append("\\n");
                md.append("- 图层：").append(heatmapViewTypeLabel(s(heatmap.get("viewType")))).append("\n");
                md.append("- \u6307\u6807\uff1a").append(s(heatmap.get("metric"))).append("\\n");
                md.append("- X \u8f74\uff1a").append(s(heatmap.get("xParam"))).append("\\n");
                md.append("- Y \\u8f74\\uff1a").append(s(heatmap.get("yParam"))).append("\\n");
                md.append("- 搜索模式：").append(s(heatmap.get("searchMode"))).append("\\n");
                md.append("- 完整网格：").append(isTrue(heatmap.get("gridComplete")) ? "Y" : "N").append("\\n");
                @SuppressWarnings("unchecked")
                List<String> aggregatedParams = (List<String>) heatmap.get("aggregatedParams");
                if (aggregatedParams != null && !aggregatedParams.isEmpty()) {
                    md.append("- \u5176\u4f59\u805a\u5408\u53c2\u6570\uff1a").append(StringUtils.join(aggregatedParams, ", ")).append("\\n");
                }
                md.append("- \u8bf4\u660e\uff1a").append(s(heatmap.get("note"))).append("\\n\\n");
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> combinationSummary = (List<Map<String, Object>>) optimization.get("combinationSummary");
        if (combinationSummary != null && !combinationSummary.isEmpty()) {
            md.append("\\n## 参数组合结果表\\n\\n");
            md.append("| 参数组合 | 次数 | 平均 Fit | 平均 Validate | 平均 Forward | 正 Fit 次数 |\\n|---|---:|---:|---:|---:|---:|\\n");
            for (Map<String, Object> row : combinationSummary) {
                md.append("| `").append(s(row.get("paramSetJson")))
                        .append("` | ").append(s(row.get("count")))
                        .append(" | ").append(s(row.get("avgFitPnl")))
                        .append(" | ").append(s(row.get("avgValidatePnl")))
                        .append(" | ").append(s(row.get("avgForwardPnl")))
                        .append(" | ").append(s(row.get("positiveFitCount")))
                        .append(" |\\n");
            }
        }
        @SuppressWarnings("unchecked")
        List<String> failedRules = (List<String>) gates.get("failedRules");
        if (failedRules != null && !failedRules.isEmpty()) {
            md.append("\\n## \u672a\u901a\u8fc7\u9879\\n\\n");
            for (String item : failedRules) {
                md.append("- ").append(item).append("\\n");
            }
        }
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) gates.get("warnings");
        if (warnings != null && !warnings.isEmpty()) {
            md.append("\\n## \u63d0\u793a\\n\\n");
            for (String item : warnings) {
                md.append("- ").append(item).append("\\n");
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trials = (List<Map<String, Object>>) optimization.get("trials");
        if (trials != null && !trials.isEmpty()) {
            md.append("\\n## \u53c2\u6570\u4f18\u5316 Top Trials\\n\\n");
            md.append("| Trial | Phase | Rank | Total PnL | Forward Score | Max DD | Elapsed(ms) | Fragile | Param Set |\\n|---|---|---:|---:|---:|---:|---:|---|---|\\n");
            for (Map<String, Object> trial : trials) {
                md.append("| ").append(s(trial.get("trialNo")))
                        .append(" | ").append(s(trial.get("phase")))
                        .append(" | ").append(s(trial.get("rank")))
                        .append(" | ").append(s(trial.get("totalPnl")))
                        .append(" | ").append(s(trial.get("forwardScore")))
                        .append(" | ").append(s(trial.get("maxDrawdownPct")))
                        .append(" | ").append(s(trial.get("elapsedMs")))
                        .append(" | ").append(nzInt(trial.get("fragileBest")) > 0 ? "Y" : "N")
                        .append(" | `").append(s(trial.get("paramSetJson"))).append("` |\\n");
            }
        }
        return md.toString();
    }

    @SuppressWarnings("unchecked")
    private String renderSimpleUserSummarySection(Map<String, Object> userSummary) {
        if (userSummary == null || userSummary.isEmpty()) {
            return "";
        }
        String statusClass = s(userSummary.get("statusClass"));
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"section\"><h2>\u4e00\u5206\u949f\u770b\u61c2\u56de\u6d4b</h2><div class=\"verdict\">")
                .append("<div class=\"verdict-main\"><div class=\"metric-label\">\u7cfb\u7edf\u7ed3\u8bba</div>")
                .append("<div class=\"metric-value ").append(escape(statusClass)).append("\">")
                .append(escape(s(userSummary.get("headline")))).append("</div>")
                .append("<div class=\"muted\" style=\"margin-top:12px;line-height:1.7\">\u4e0b\u4e00\u6b65\uff1a")
                .append(escape(s(userSummary.get("actionLabel")))).append("</div></div>")
                .append(compactMetric("\u6263\u8d39\u540e\u573a\u666f\u9a8c\u8bc1\u6536\u76ca", userSummary.get("keyFeeAdjustedValidatePnl"), " USDT"))
                .append(compactMetric("\u573a\u666f\u524d\u77bb\u6536\u76ca", userSummary.get("keyFeeAdjustedForwardPnl"), " USDT"))
                .append(compactMetric("\u6700\u5927\u56de\u64a4", percentText(userSummary.get("keyDrawdown")), ""))
                .append(compactMetric("\u573a\u666f\u9a8c\u8bc1\u4ea4\u6613", userSummary.get("keyTradeCount"), " \u7b14"))
                .append("</div>");
        html.append(renderPlainList("\u4e3a\u4ec0\u4e48\u662f\u8fd9\u4e2a\u7ed3\u8bba", (List<String>) userSummary.get("reasons")));
        html.append(renderPlainList("\u7cfb\u7edf\u63a5\u4e0b\u6765\u4f1a\u505a\u4ec0\u4e48", (List<String>) userSummary.get("nextSteps")));
        html.append("</div>");
        return html.toString();
    }

    @SuppressWarnings("unchecked")
    private String renderSceneShadowSection(Map<String, Object> sceneShadow) {
        List<Map<String, Object>> rows = sceneShadow == null
                ? null : (List<Map<String, Object>>) sceneShadow.get("rows");
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        String status = s(sceneShadow.get("status"));
        String statusText = "QUALIFIED".equals(status)
                ? "\u573a\u666f\u8d44\u683c\u901a\u8fc7" : "QUALIFICATION_FAILED".equals(status)
                ? "\u573a\u666f\u8d44\u683c\u672a\u901a\u8fc7" : "\u573a\u666f\u8bc1\u636e\u79ef\u7d2f\u4e2d";
        String body = new StringBuilder("<div class=\"grid\">")
                .append(statusCard("\u573a\u666f\u5185\u8868\u73b0", statusText,
                        "\u53ea\u7edf\u8ba1\u5e02\u573a\u573a\u666f\u4e0e\u7b56\u7565\u5339\u914d\u7684\u65f6\u6bb5\uff0c\u7ed3\u679c\u4f1a\u53c2\u4e0e\u53d1\u5e03\u8d44\u683c\u5224\u65ad\u3002",
                        "QUALIFIED".equals(status) ? "pass" : "warn"))
                .append(metric("\u573a\u666f\u8bb0\u5f55", sceneShadow.get("sceneRecordCount")))
                .append(metric("\u5339\u914d\u65f6\u6bb5\u4ea4\u6613", sceneShadow.get("tradeCount")))
                .append(metric("\u573a\u666f\u5185\u6536\u76ca USDT", sceneShadow.get("totalPnl")))
                .append(metric("\u62e6\u622a\u4e0d\u5339\u914d\u4fe1\u53f7", sceneShadow.get("blockedSignalCount")))
                .append(metric("\u573a\u666f\u5207\u6362\u9000\u51fa", sceneShadow.get("forcedExitCount")))
                .append("</div>")
                .append(renderTable(
                        new String[]{"\u54c1\u79cd", "\u76ee\u6807\u573a\u666f", "\u6570\u636e\u72b6\u6001", "\u4ea4\u6613\u7b14\u6570", "\u6536\u76ca", "\u6700\u5927\u56de\u64a4", "\u62e6\u622a\u4fe1\u53f7"},
                        rows,
                        new String[]{"symbol", "strategyScene", "message", "tradeCount", "totalPnl", "maxDrawdownPct", "blockedSignalCount"}))
                .toString();
        return detailsBlock("\u573a\u666f\u56de\u6d4b\u8d44\u683c\uff08\u6b63\u5f0f\u51c6\u5165\uff09", body, false);
    }

    private String compactMetric(String label, Object value, String suffix) {
        return "<div class=\"card\"><div class=\"metric-label\">" + escape(label)
                + "</div><div class=\"metric-value\">" + escape(s(value)) + escape(suffix) + "</div></div>";
    }

    private String percentText(Object value) {
        BigDecimal raw = n(value);
        return raw.multiply(BigDecimal.valueOf(100D)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String renderPlainList(String title, List<String> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<div class=\"card\" style=\"margin-top:12px\"><h3>")
                .append(escape(title)).append("</h3><ul class=\"plain-list\">");
        for (String row : rows) {
            html.append("<li>").append(escape(row)).append("</li>");
        }
        return html.append("</ul></div>").toString();
    }

    @SuppressWarnings("unchecked")
    private String renderUserSummarySection(Map<String, Object> userSummary) {
        if (userSummary == null || userSummary.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"section\"><h2>给用户的结论</h2><div class=\"grid\">")
                .append(statusCard("回测结论", s(userSummary.get("headline")), s(userSummary.get("auditSummary")), s(userSummary.get("statusClass"))))
                .append(statusCard("现在该怎么做", s(userSummary.get("actionLabel")), "只看这一条即可判断下一步动作。", s(userSummary.get("statusClass"))))
                .append(metric("扣费 Validate 收益", userSummary.get("keyFeeAdjustedValidatePnl")))
                .append(metric("扣费 Forward 收益", userSummary.get("keyFeeAdjustedForwardPnl")))
                .append(metric("最大回撤", userSummary.get("keyDrawdown")))
                .append(metric("交易笔数", userSummary.get("keyTradeCount")))
                .append("</div>");
        html.append(renderStringList("为什么是这个结论", (List<String>) userSummary.get("reasons")));
        html.append(renderStringList("下一步建议", (List<String>) userSummary.get("nextSteps")));
        html.append("</div>");
        return html.toString();
    }

    private String detailsBlock(String title, String body, boolean open) {
        StringBuilder html = new StringBuilder();
        html.append("<details class=\"fold\"");
        if (open) {
            html.append(" open");
        }
        html.append("><summary>").append(escape(title)).append("</summary><div class=\"fold-body\">")
                .append(body == null ? "" : body)
                .append("</div></details>");
        return html.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendUserSummaryMarkdown(StringBuilder md, Map<String, Object> userSummary) {
        if (md == null || userSummary == null || userSummary.isEmpty()) {
            return;
        }
        md.append("## 给用户的结论\\n\\n");
        md.append("- 回测结论：").append(s(userSummary.get("headline"))).append("\\n");
        md.append("- 现在该怎么做：").append(s(userSummary.get("actionLabel"))).append("\\n");
        md.append("- 扣费 Validate 收益：").append(s(userSummary.get("keyFeeAdjustedValidatePnl"))).append("\\n");
        md.append("- 扣费 Forward 收益：").append(s(userSummary.get("keyFeeAdjustedForwardPnl"))).append("\\n");
        md.append("- 最大回撤：").append(s(userSummary.get("keyDrawdown"))).append("\\n");
        md.append("- 交易笔数：").append(s(userSummary.get("keyTradeCount"))).append("\\n");
        List<String> reasons = (List<String>) userSummary.get("reasons");
        if (reasons != null && !reasons.isEmpty()) {
            md.append("\\n### 为什么是这个结论\\n\\n");
            for (String item : reasons) {
                md.append("- ").append(item).append("\\n");
            }
        }
        List<String> nextSteps = (List<String>) userSummary.get("nextSteps");
        if (nextSteps != null && !nextSteps.isEmpty()) {
            md.append("\\n### 下一步建议\\n\\n");
            for (String item : nextSteps) {
                md.append("- ").append(item).append("\\n");
            }
        }
        md.append("\\n");
    }

    @SuppressWarnings("unchecked")
    private String renderOptimizationTrials(Map<String, Object> optimization) {
        if (optimization == null) {
            return "";
        }
        List<Map<String, Object>> trials = (List<Map<String, Object>>) optimization.get("trials");
        if (trials == null || trials.isEmpty()) {
            return "<div class=\"table-wrap\"><table><thead><tr><th>说明</th></tr></thead><tbody><tr><td>暂无参数优化试验明细</td></tr></tbody></table></div>";
        }
        return renderTable(
                new String[]{"Trial", "Phase", "Rank", "Fit", "Validate", "Forward", "Total", "Forward Score", "Max DD", "参数集"},
                trials,
                new String[]{"trialNo", "phase", "rank", "fitPnl", "validatePnl", "forwardPnl", "totalPnl", "forwardScore", "maxDrawdownPct", "paramSetJson"});
    }

    @SuppressWarnings("unchecked")
    private String renderOptimizationTrialsV2(Map<String, Object> optimization) {
        if (optimization == null) {
            return "";
        }
        List<Map<String, Object>> trials = (List<Map<String, Object>>) optimization.get("trials");
        if (trials == null || trials.isEmpty()) {
            return "<div class=\"table-wrap\"><table><thead><tr><th>说明</th></tr></thead><tbody><tr><td>暂无参数优化试验明细</td></tr></tbody></table></div>";
        }
        return renderTable(
                new String[]{"Trial", "Phase", "Rank", "Fit", "Validate", "Forward", "Total", "Forward Score", "Max DD", "Elapsed(ms)", "Fragile", "参数集"},
                trials,
                new String[]{"trialNo", "phase", "rank", "fitPnl", "validatePnl", "forwardPnl", "totalPnl", "forwardScore", "maxDrawdownPct", "elapsedMs", "fragileBest", "paramSetJson"});
    }

    private String compactJsonValue(Object value) {
        String text = s(value);
        if (StringUtils.isBlank(text)) {
            return "{}";
        }
        if (text.length() <= 72) {
            return text;
        }
        return text.substring(0, 69) + "...";
    }

    private String auditDecisionClass(String decision) {
        if ("PASS".equalsIgnoreCase(decision)) {
            return "pass";
        }
        if ("WATCH".equalsIgnoreCase(decision)) {
            return "warn";
        }
        return "fail";
    }

    private String auditDecisionLabelText(String decision) {
        if ("PASS".equalsIgnoreCase(decision)) {
            return "通过";
        }
        if ("WATCH".equalsIgnoreCase(decision)) {
            return "观察";
        }
        return "失败";
    }

    private String auditStatusLabel(String status) {
        if ("PASS".equalsIgnoreCase(status)) {
            return "通过";
        }
        if ("WARN".equalsIgnoreCase(status) || "WATCH".equalsIgnoreCase(status)) {
            return "观察";
        }
        if ("N/A".equalsIgnoreCase(status)) {
            return "不适用";
        }
        return "失败";
    }

    private String optimizationQualityClass(String status) {
        if ("GOOD".equalsIgnoreCase(status)) {
            return "pass";
        }
        if ("WATCH".equalsIgnoreCase(status) || "N/A".equalsIgnoreCase(status)) {
            return "warn";
        }
        return "fail";
    }

    private String optimizationQualityLabel(String status) {
        if ("GOOD".equalsIgnoreCase(status)) {
            return "良好";
        }
        if ("WATCH".equalsIgnoreCase(status)) {
            return "观察";
        }
        if ("WEAK".equalsIgnoreCase(status)) {
            return "偏弱";
        }
        if ("MISSING".equalsIgnoreCase(status)) {
            return "缺失";
        }
        if ("N/A".equalsIgnoreCase(status)) {
            return "不适用";
        }
        return "失败";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (StringUtils.isBlank(json) || "{}".equals(StringUtils.trim(json))) {
            return Collections.emptyMap();
        }
        try {
            Object parsed = JsonUtils.Deserialize(json, Map.class);
            if (parsed instanceof Map) {
                return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
            }
        } catch (Exception ignore) {
        }
        return Collections.emptyMap();
    }

    private int compareParamValueStrings(String a, String b) {
        BigDecimal left = parseBigDecimalOrNull(a);
        BigDecimal right = parseBigDecimalOrNull(b);
        if (left != null && right != null) {
            return left.compareTo(right);
        }
        return StringUtils.defaultString(a).compareTo(StringUtils.defaultString(b));
    }

    private BigDecimal parseBigDecimalOrNull(String value) {
        if (StringUtils.isBlank(value) || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String renderStringList(String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"margin-top:10px;\"><strong>").append(escape(title)).append(":</strong><ul style=\"margin:6px 0 0 18px; padding:0;\">");
        for (String item : items) {
            html.append("<li>").append(escape(s(item))).append("</li>");
        }
        html.append("</ul></div>");
        return html.toString();
    }

    private void addUnique(List<String> rows, String value) {
        if (rows == null || StringUtils.isBlank(value)) {
            return;
        }
        for (String item : rows) {
            if (StringUtils.equalsIgnoreCase(StringUtils.trimToEmpty(item), StringUtils.trimToEmpty(value))) {
                return;
            }
        }
        rows.add(value.trim());
    }

    private boolean isPublishEligible(BacktestResponse response, StrategyCandidateRow candidate) {
        return response != null
                && BacktestModels.SCENE_CONDITIONED_WINDOW_MODE.equalsIgnoreCase(s(response.windowMode))
                && allResultsUseCurrentExecutionModel(response.results)
                && allFullPeriodSafetyPassed(response.results)
                && allSceneQualificationsPassed(response.results)
                && hasOptimizationEvidence(response, candidate)
                && response.overfitPass != null
                && response.overfitPass.intValue() > 0
                && response.oosPass != null
                && response.oosPass.intValue() > 0
                && gt(preferredValidateScore(response), BigDecimal.ZERO)
                && sumTradeCount(response.results) >= Math.max(1, minValidateTrades)
                && lte(maxDrawdownPct(response.results), BigDecimal.valueOf(maxValidateDrawdownPct))
                && gte(avgProfitFactor(response.results), BigDecimal.valueOf(minValidateProfitFactor))
                && gt(preferredFeeAdjustedValidate(response), BigDecimal.ZERO)
                && gte(response.forwardPnl, BigDecimal.ZERO)
                && gt(avgForwardScore(response.results), BigDecimal.ZERO)
                && gte(forwardContribution(response.forwardPnl, response.totalPnl), nz(response.minForwardContribution))
                && !isTrue(response.fragileBest)
                && candidate != null
                && StringUtils.isNotBlank(candidate.description);
    }

    private String publishReason(BacktestResponse response, StrategyCandidateRow candidate) {
        if (candidate == null) {
            return "candidate \u4e0d\u5b58\u5728";
        }
        if (StringUtils.isBlank(candidate.description)) {
            return "candidate.description \u4e3a\u7a7a";
        }
        if (response == null) {
            return "\u5c1a\u672a\u751f\u6210\u56de\u6d4b\u7ed3\u679c";
        }
        if (!BacktestModels.SCENE_CONDITIONED_WINDOW_MODE.equalsIgnoreCase(s(response.windowMode))) {
            return "\u56de\u6d4b\u672a\u4f7f\u7528\u573a\u666f\u6761\u4ef6\u5316\u6eda\u52a8\u9a8c\u8bc1";
        }
        if (!allResultsUseCurrentExecutionModel(response.results)) {
            return "\u56de\u6d4b\u6267\u884c\u6a21\u578b\u7248\u672c\u8fc7\u65e7";
        }
        if (!allFullPeriodSafetyPassed(response.results)) {
            return "\u5b8c\u6574\u5468\u671f\u6267\u884c\u5b89\u5168\u68c0\u67e5\u672a\u901a\u8fc7";
        }
        if (!allSceneQualificationsPassed(response.results)) {
            return "\u573a\u666f\u56de\u6d4b\u8d44\u683c\u672a\u901a\u8fc7";
        }
        if (!hasOptimizationEvidence(response, candidate)) {
            return "缺少优化证据";
        }
        if (response.overfitPass == null || response.overfitPass.intValue() <= 0) {
            return "\u672a\u901a\u8fc7\u8fc7\u62df\u5408\u68c0\u67e5";
        }
        if (response.oosPass == null || response.oosPass.intValue() <= 0) {
            return "OOS 门槛未通过";
        }
        if (!gt(preferredValidateScore(response), BigDecimal.ZERO)) {
            return "Validate 主分 <= 0";
        }
        if (sumTradeCount(response.results) < Math.max(1, minValidateTrades)) {
            return "Validate 交易数低于阈值";
        }
        if (!lte(maxDrawdownPct(response.results), BigDecimal.valueOf(maxValidateDrawdownPct))) {
            return "Validate 最大回撤超过阈值";
        }
        if (!gte(avgProfitFactor(response.results), BigDecimal.valueOf(minValidateProfitFactor))) {
            return "Validate Profit Factor 低于阈值";
        }
        if (!gt(preferredFeeAdjustedValidate(response), BigDecimal.ZERO)) {
            return "扣费后 Validate 收益 <= 0";
        }
        if (!gte(response.forwardPnl, BigDecimal.ZERO)) {
            return "Forward 收益 < 0";
        }
        if (isTrue(response.fragileBest)) {
            return "最优参数呈现脆弱特征";
        }
        if (!gt(avgForwardScore(response.results), BigDecimal.ZERO)) {
            return "Forward Score <= 0";
        }
        if (!gte(forwardContribution(response.forwardPnl, response.totalPnl), nz(response.minForwardContribution))) {
            return "Forward 贡献度低于阈值";
        }
        return "\u6ee1\u8db3\u4e0a\u7ebf\u524d\u76c8\u5229\u95e8\u69db";
    }

    private boolean expectsOptimizationEvidence(StrategyCandidateRow candidate) {
        return candidate != null && StrategyParametersSupport.isOptimizationSupported(candidate.parametersJson);
    }

    private boolean hasOptimizationEvidence(BacktestResponse response, StrategyCandidateRow candidate) {
        if (!expectsOptimizationEvidence(candidate)) {
            return true;
        }
        return response != null
                && response.trialCount != null
                && response.trialCount.intValue() > 0
                && response.trials != null
                && !response.trials.isEmpty();
    }

    private String optimizationEvidenceStatus(BacktestResponse response, StrategyCandidateRow candidate) {
        if (!expectsOptimizationEvidence(candidate)) {
            return "NOT_REQUIRED";
        }
        return hasOptimizationEvidence(response, candidate) ? "PRESENT" : "MISSING";
    }

    private String optimizationEvidenceMessage(BacktestResponse response, StrategyCandidateRow candidate) {
        if (!expectsOptimizationEvidence(candidate)) {
            return "\u672c\u6b21\u5019\u9009\u672a\u542f\u7528\u53c2\u6570\u4f18\u5316";
        }
        if (hasOptimizationEvidence(response, candidate)) {
            return "\u5df2\u4ea7\u51fa slice fit trial \u8bc1\u636e\u4e0e\u53c2\u6570\u6392\u540d";
        }
        return "\u672c\u6b21\u56de\u6d4b\u672a\u4ea7\u51fa\u53ef\u7528\u7684 optimization trial \u8bc1\u636e";
    }

    private List<String> buildFailedRules(BacktestResponse response, StrategyCandidateRow candidate) {
        List<String> rows = new ArrayList<String>();
        if (response == null) {
            rows.add("\u672a\u751f\u6210\u56de\u6d4b\u7ed3\u679c");
            return rows;
        }
        if (!BacktestModels.SCENE_CONDITIONED_WINDOW_MODE.equalsIgnoreCase(s(response.windowMode))) {
            rows.add("\u672a\u4f7f\u7528\u573a\u666f\u6761\u4ef6\u5316\u6eda\u52a8\u9a8c\u8bc1");
        }
        if (!allResultsUseCurrentExecutionModel(response.results)) {
            rows.add("\u56de\u6d4b\u6267\u884c\u6a21\u578b\u7248\u672c\u8fc7\u65e7");
        }
        if (!allFullPeriodSafetyPassed(response.results)) {
            rows.add("\u5b8c\u6574\u5468\u671f\u6267\u884c\u5b89\u5168\u68c0\u67e5\u672a\u901a\u8fc7");
        }
        if (!allSceneQualificationsPassed(response.results)) {
            rows.add("\u573a\u666f\u56de\u6d4b\u8d44\u683c\u672a\u901a\u8fc7");
        }
        if (!hasOptimizationEvidence(response, candidate)) {
            rows.add("缺少优化证据");
        }
        if (!isTrue(response.overfitPass)) {
            rows.add("未通过过拟合检查");
        }
        if (!isTrue(response.oosPass)) {
            rows.add("OOS 门槛未通过");
        }
        if (!gt(preferredValidateScore(response), BigDecimal.ZERO)) {
            rows.add("Validate 主分 <= 0");
        }
        if (!gte(response.forwardPnl, BigDecimal.ZERO)) {
            rows.add("Forward 收益 < 0");
        }
        if (isTrue(response.fragileBest)) {
            rows.add("最优参数呈现脆弱特征");
        }
        return rows;
    }

    private List<String> buildWarnings(BacktestResponse response, StrategyCandidateRow candidate) {
        List<String> rows = new ArrayList<String>();
        if (response == null) {
            return rows;
        }
        if (expectsOptimizationEvidence(candidate) && !hasOptimizationEvidence(response, candidate)) {
            rows.add("\u4f18\u5316\u8bc1\u636e\u7f3a\u5931\uff0c\u53c2\u6570\u7a33\u5b9a\u6027\u6307\u6807\u4ec5\u4f9b\u53c2\u8003");
        }
        if (response.sliceParamDriftScore != null
                && response.sliceParamDriftScore.compareTo(BigDecimal.ZERO) == 0
                && expectsOptimizationEvidence(candidate)
                && !hasOptimizationEvidence(response, candidate)) {
            rows.add("\u53c2\u6570\u6f02\u79fb\u4e3a 0 \u53ef\u80fd\u4ec5\u56e0\u4e3a trial \u7f3a\u5931\uff0c\u4e0d\u4ee3\u8868\u771f\u6b63\u7a33\u5b9a");
        }
        return rows;
    }

    private BigDecimal preferredValidateScore(BacktestResponse response) {
        if (response == null) {
            return BigDecimal.ZERO;
        }
        if (gt(response.validatePrimaryScore, BigDecimal.ZERO)) {
            return response.validatePrimaryScore;
        }
        return nz(response.validatePnl);
    }

    private boolean allResultsUseCurrentExecutionModel(List<BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (BacktestResult result : results) {
            if (result == null || !BacktestModels.EXECUTION_MODEL_VERSION.equals(result.executionModelVersion)) {
                return false;
            }
        }
        return true;
    }

    private boolean allFullPeriodSafetyPassed(List<BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (BacktestResult result : results) {
            if (result == null || result.fullPeriodSafety == null
                    || !Boolean.TRUE.equals(result.fullPeriodSafety.passed)) {
                return false;
            }
        }
        return true;
    }

    private boolean allSceneQualificationsPassed(List<BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (BacktestResult result : results) {
            BacktestModels.SceneShadowMetrics metrics = result == null ? null : result.sceneShadow;
            if (metrics == null
                    || !BacktestModels.SCENE_CONDITIONED_WINDOW_MODE.equals(metrics.mode)
                    || !Boolean.TRUE.equals(metrics.qualificationPass)) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal preferredFeeAdjustedValidate(BacktestResponse response) {
        if (response == null) {
            return BigDecimal.ZERO;
        }
        if (response.feeAdjustedValidatePnl != null
                && response.feeAdjustedValidatePnl.compareTo(BigDecimal.ZERO) != 0) {
            return response.feeAdjustedValidatePnl;
        }
        return nz(response.validatePnl);
    }

    private int sumTradeCount(List<BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (BacktestResult result : results) {
            total += nzInt(result == null ? null : result.tradeCount);
        }
        return total;
    }

    private BigDecimal maxDrawdownPct(List<BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal max = BigDecimal.ZERO;
        for (BacktestResult result : results) {
            max = max.max(nz(result == null ? null : result.maxDrawdownPct));
        }
        return max;
    }

    private BigDecimal avgProfitFactor(List<BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (BacktestResult result : results) {
            if (result == null) {
                continue;
            }
            sum = sum.add(nz(result.profitFactor));
            count++;
        }
        if (count <= 0) {
            return BigDecimal.ZERO;
        }
        return sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal avgForwardScore(List<BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (BacktestResult result : results) {
            if (result == null) {
                continue;
            }
            sum = sum.add(nz(result.forwardScore));
            count++;
        }
        if (count <= 0) {
            return BigDecimal.ZERO;
        }
        return sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal forwardContribution(BigDecimal forwardPnl, BigDecimal totalPnl) {
        if (totalPnl == null || totalPnl.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return nz(forwardPnl).divide(totalPnl, 6, RoundingMode.HALF_UP);
    }

    private String translateReason(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String value = raw.trim();
        if ("candidate description is blank".equalsIgnoreCase(value)) {
            return "candidate.description \u4e3a\u7a7a";
        }
        if ("window_mode is not WALK_FORWARD".equalsIgnoreCase(value)) {
            return "window_mode \u4e0d\u662f WALK_FORWARD";
        }
        if ("window_mode is not SCENE_CONDITIONED_WALK_FORWARD".equalsIgnoreCase(value)) {
            return "\u56de\u6d4b\u672a\u4f7f\u7528\u573a\u666f\u6761\u4ef6\u5316\u6eda\u52a8\u9a8c\u8bc1";
        }
        if ("unsupported backtest execution model".equalsIgnoreCase(value)) {
            return "\u56de\u6d4b\u6267\u884c\u6a21\u578b\u7248\u672c\u8fc7\u65e7";
        }
        if ("full-period safety evidence missing".equalsIgnoreCase(value)) {
            return "\u7f3a\u5c11\u5b8c\u6574\u5468\u671f\u6267\u884c\u5b89\u5168\u8bc1\u636e";
        }
        if ("full-period safety check failed".equalsIgnoreCase(value)) {
            return "\u5b8c\u6574\u5468\u671f\u6267\u884c\u5b89\u5168\u68c0\u67e5\u672a\u901a\u8fc7";
        }
        if ("full-period execution found signals without dynamic stop/take".equalsIgnoreCase(value)) {
            return "\u5b8c\u6574\u5468\u671f\u6267\u884c\u53d1\u73b0\u7f3a\u5c11\u52a8\u6001\u6b62\u635f\u6b62\u76c8\u7684\u4fe1\u53f7";
        }
        if ("full-period execution produced no bars".equalsIgnoreCase(value)) {
            return "\u5b8c\u6574\u5468\u671f\u6267\u884c\u6ca1\u6709\u53ef\u7528 K \u7ebf";
        }
        if ("signal_economics_target_too_close".equalsIgnoreCase(value)) {
            return "\u6b62\u76c8\u7a7a\u95f4\u592a\u5c0f\uff0c\u65e0\u6cd5\u8986\u76d6\u624b\u7eed\u8d39\u548c\u6ed1\u70b9";
        }
        if ("slice_count < 3".equalsIgnoreCase(value)) {
            return "slice_count < 3";
        }
        if ("overfit gate not passed".equalsIgnoreCase(value)) {
            return "\u672a\u901a\u8fc7\u8fc7\u62df\u5408\u68c0\u67e5";
        }
        if ("oos gate not passed".equalsIgnoreCase(value)) {
            return "OOS \u95e8\u69db\u672a\u901a\u8fc7";
        }
        if ("publishable best param set missing for multi-symbol result".equalsIgnoreCase(value)) {
            return "\u591a symbol \u7ed3\u679c\u7f3a\u5c11\u53ef\u53d1\u5e03\u7684\u7edf\u4e00\u6700\u4f73\u53c2\u6570\u96c6";
        }
        if ("validate primary score <= 0".equalsIgnoreCase(value)) {
            return "validate \u4e3b\u5206 <= 0";
        }
        if ("validate trade count below threshold".equalsIgnoreCase(value)) {
            return "validate \u4ea4\u6613\u6570\u4f4e\u4e8e\u95e8\u69db";
        }
        if ("validate drawdown above threshold".equalsIgnoreCase(value)) {
            return "validate \u56de\u64a4\u8d85\u8fc7\u95e8\u69db";
        }
        if ("validate profit factor below threshold".equalsIgnoreCase(value)) {
            return "validate Profit Factor \u4f4e\u4e8e\u95e8\u69db";
        }
        if ("fee adjusted validate pnl <= 0".equalsIgnoreCase(value)) {
            return "\u6263\u8d39\u540e validate \u6536\u76ca <= 0";
        }
        if ("forward_pnl < 0".equalsIgnoreCase(value)) {
            return "forward_pnl < 0";
        }
        if ("fragile best param".equalsIgnoreCase(value)) {
            return "\u6700\u4f73\u53c2\u6570\u70b9\u8fc7\u4e8e\u8106\u5f31";
        }
        if ("validate_pnl <= 0".equalsIgnoreCase(value)) {
            return "validate_pnl <= 0";
        }
        if ("forward_pnl <= 0".equalsIgnoreCase(value)) {
            return "forward_pnl <= 0";
        }
        if ("total_pnl <= 0".equalsIgnoreCase(value)) {
            return "total_pnl <= 0";
        }
        if ("forward_score <= 0".equalsIgnoreCase(value)) {
            return "forward_score <= 0";
        }
        if ("forward contribution below threshold".equalsIgnoreCase(value)) {
            return "forward \u8d21\u732e\u5ea6\u4f4e\u4e8e\u95e8\u69db";
        }
        if ("forward_score not better than active baseline".equalsIgnoreCase(value)) {
            return "\u672a\u4f18\u4e8e\u5f53\u524d ACTIVE \u57fa\u7ebf\u7684 forward_score";
        }
        if ("validate score not better than active baseline".equalsIgnoreCase(value)) {
            return "\u672a\u4f18\u4e8e\u5f53\u524d ACTIVE \u57fa\u7ebf\u7684 validate \u4e3b\u5206";
        }
        if ("total_pnl not better than active baseline".equalsIgnoreCase(value)) {
            return "\u672a\u4f18\u4e8e\u5f53\u524d ACTIVE \u57fa\u7ebf\u7684 total_pnl";
        }
        if ("same version already active".equalsIgnoreCase(value)) {
            return "\u540c\u7248\u672c\u5df2\u7ecf\u5904\u4e8e ACTIVE";
        }
        if ("baseline backtest summary missing".equalsIgnoreCase(value)) {
            return "\u7f3a\u5c11\u5f53\u524d ACTIVE \u57fa\u7ebf\u56de\u6d4b\u7ed3\u679c";
        }
        if ("promote profitable walk-forward first version".equalsIgnoreCase(value)) {
            return "\u9996\u4e2a\u76c8\u5229 walk-forward \u7248\u672c\uff0c\u5141\u8bb8\u53d1\u5e03";
        }
        if ("replace active version with stronger backtest result".equalsIgnoreCase(value)) {
            return "\u65b0\u7248\u672c\u4f18\u4e8e\u5f53\u524d ACTIVE\uff0c\u5141\u8bb8\u66ff\u6362";
        }
        if (value.startsWith("auto publish error:")) {
            return "\u81ea\u52a8\u53d1\u5e03\u6267\u884c\u5931\u8d25\uff1a" + value.substring("auto publish error:".length()).trim();
        }
        return value;
    }

    private String buildBaseName(BacktestResponse response) {
        return "BacktestReport-"
                + safeFilePart(response == null ? null : response.strategyName)
                + "-"
                + safeFilePart(defaultIfBlank(response == null ? null : response.symbol, joinSymbols(response == null ? null : response.symbols)))
                + "-"
                + safeFilePart(response == null ? null : response.text)
                + "-\u4e2d\u6587-"
                + FILE_TIME.format(LocalDateTime.now());
    }

    private String strategyLabel(String strategyName, String strategyVersion) {
        if (StringUtils.isBlank(strategyName) && StringUtils.isBlank(strategyVersion)) {
            return "";
        }
        if (StringUtils.isBlank(strategyVersion)) {
            return StringUtils.defaultString(strategyName);
        }
        return StringUtils.defaultString(strategyName) + "@" + StringUtils.defaultString(strategyVersion);
    }

    private String joinSymbols(Object symbolsObj) {
        if (symbolsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> symbols = (List<Object>) symbolsObj;
            StringJoiner joiner = new StringJoiner(", ");
            for (Object item : symbols) {
                if (item != null && StringUtils.isNotBlank(item.toString())) {
                    joiner.add(item.toString().trim());
                }
            }
            return joiner.toString();
        }
        return s(symbolsObj);
    }

    private String joinStrings(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (String item : items) {
            if (StringUtils.isNotBlank(item)) {
                joiner.add(item.trim());
            }
        }
        return joiner.toString();
    }

    private String joinActiveSymbols(List<StrategyLiveRegistryPublishRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(",");
        for (StrategyLiveRegistryPublishRow row : rows) {
            if (row == null || StringUtils.isBlank(row.symbolScope)) {
                continue;
            }
            joiner.add(row.symbolScope.trim());
        }
        return joiner.toString();
    }

    private String displayTime(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        long epochMillis = toEpochMillis(value);
        if (epochMillis >= 0L) {
            return LocalDateTime.ofEpochSecond(epochMillis / 1000L, 0, ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        String trim = value.trim().replace('T', ' ');
        trim = trim.replaceAll("Z\\[[^\\]]+\\]$", "");
        if (trim.endsWith("Z")) {
            trim = trim.substring(0, trim.length() - 1);
        }
        int dot = trim.indexOf('.');
        if (dot > 0) {
            trim = trim.substring(0, dot);
        }
        if (trim.length() == 16) {
            trim = trim + ":00";
        }
        if (trim.length() > 19) {
            trim = trim.substring(0, 19);
        }
        return trim;
    }

    private long toEpochMillis(String value) {
        if (StringUtils.isBlank(value)) {
            return -1L;
        }
        String trim = value.trim();
        try {
            return java.time.ZonedDateTime.parse(trim).toInstant().toEpochMilli();
        } catch (Exception ignore) {
        }
        try {
            return java.time.OffsetDateTime.parse(trim).toInstant().toEpochMilli();
        } catch (Exception ignore) {
        }
        String normalized = trim.replace('T', ' ');
        normalized = normalized.replaceAll("Z\\[[^\\]]+\\]$", "");
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int dot = normalized.indexOf('.');
        if (dot > 0) {
            normalized = normalized.substring(0, dot);
        }
        try {
            if (normalized.length() == 10) {
                return LocalDate.parse(normalized).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            }
            if (normalized.length() == 16) {
                normalized = normalized + ":00";
            }
            if (normalized.length() > 19) {
                normalized = normalized.substring(0, 19);
            }
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1L;
        }
    }
    private double projectX(int index, int size, int width, int paddingLeft, int paddingRight) {
        if (size <= 1) {
            return paddingLeft;
        }
        double usable = width - paddingLeft - paddingRight;
        return paddingLeft + usable * ((double) index / (double) (size - 1));
    }

    private double projectTimeX(long value, long begin, long end, int width, int paddingLeft, int paddingRight) {
        if (begin < 0L || end <= begin) {
            return paddingLeft;
        }
        double usable = width - paddingLeft - paddingRight;
        double ratio = (double) (value - begin) / (double) (end - begin);
        ratio = Math.max(0D, Math.min(1D, ratio));
        return paddingLeft + usable * ratio;
    }
    private double projectY(BigDecimal value, BigDecimal min, BigDecimal max, int height, int paddingTop, int paddingBottom) {
        BigDecimal spread = max.subtract(min);
        if (spread.compareTo(BigDecimal.ZERO) == 0) {
            return paddingTop;
        }
        double usable = height - paddingTop - paddingBottom;
        double ratio = value.subtract(min).divide(spread, 12, RoundingMode.HALF_UP).doubleValue();
        return paddingTop + usable - (usable * ratio);
    }

    private String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String safeCell(Object value) {
        return escape(s(value));
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String safeFilePart(String value) {
        if (StringUtils.isBlank(value)) {
            return "NA";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal n(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean gt(BigDecimal left, BigDecimal right) {
        return nz(left).compareTo(nz(right)) > 0;
    }

    private boolean gte(BigDecimal left, BigDecimal right) {
        return nz(left).compareTo(nz(right)) >= 0;
    }

    private boolean lte(BigDecimal left, BigDecimal right) {
        return nz(left).compareTo(nz(right)) <= 0;
    }

    private boolean isTrue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue() > 0;
        }
        if (value instanceof String) {
            return "1".equals(value) || "true".equalsIgnoreCase((String) value);
        }
        return false;
    }

    private Integer nzInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private BigDecimal scale(Object value) {
        return scale(n(value));
    }

    private BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(6, RoundingMode.HALF_UP);
    }

    private String s(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String heatmapViewTypeLabel(String viewType) {
        if ("TRIAL_SEARCH".equalsIgnoreCase(viewType)) {
            return "trial 搜索落点";
        }
        if ("SLICE_BEST_AGGREGATION".equalsIgnoreCase(viewType)) {
            return "slice 最优参数投影";
        }
        if ("DISABLED".equalsIgnoreCase(viewType)) {
            return "不可用";
        }
        return "未标注";
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value.trim();
    }

    private static class ReplayWindow {
        final String begin;
        final String end;
        final String fitBegin;
        final String fitEnd;
        final String validateBegin;
        final String validateEnd;
        final String forwardBegin;
        final String forwardEnd;

        private ReplayWindow(String begin,
                             String end,
                             String fitBegin,
                             String fitEnd,
                             String validateBegin,
                             String validateEnd,
                             String forwardBegin,
                             String forwardEnd) {
            this.begin = begin;
            this.end = end;
            this.fitBegin = fitBegin;
            this.fitEnd = fitEnd;
            this.validateBegin = validateBegin;
            this.validateEnd = validateEnd;
            this.forwardBegin = forwardBegin;
            this.forwardEnd = forwardEnd;
        }
    }
}


