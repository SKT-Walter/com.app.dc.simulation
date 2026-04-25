package com.app.dc.service.simulation.runtime;

import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.pipeline.StrategyPipelineModels;
import com.app.dc.pipeline.StrategyPipelineService;
import com.app.dc.service.dao.BacktestResultClickHouseDao;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestReportService;
import com.app.dc.service.simulation.BacktestService;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Component
@Slf4j
public class StrategyBacktestPullJob {

    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final LocalDateTime processBootAt = LocalDateTime.now();
    private final String processBootTime = CLICKHOUSE_TIME.format(processBootAt);

    @Value("${strategy.backtest.task.enabled:false}")
    private boolean enabled;

    @Value("${strategy.backtest.task.batchSize:10}")
    private int batchSize;

    @Value("${strategy.backtest.parallelism:10}")
    private int parallelism;

    @Value("${strategy.backtest.task.runningReclaimMinutes:30}")
    private int runningReclaimMinutes;

    @Value("${strategy.backtest.task.suspendedRetryMinutes:30}")
    private int suspendedRetryMinutes;

    @Autowired
    private StrategyBacktestTaskDao taskDao;

    @Autowired
    private BacktestService backtestService;

    @Autowired
    private BacktestReportService backtestReportService;

    @Autowired
    private BacktestResultClickHouseDao backtestResultClickHouseDao;

    @Autowired
    private StrategyAutoPublishService strategyAutoPublishService;

    @Autowired
    private BinanceKlineAutofillService binanceKlineAutofillService;

    @Autowired(required = false)
    private StrategyPipelineService strategyPipelineService;

    @Autowired
    @Qualifier("strategyBacktestTaskExecutor")
    private ThreadPoolTaskExecutor strategyBacktestTaskExecutor;

    private final Set<String> inFlightTaskIds = ConcurrentHashMap.newKeySet();

    @Scheduled(cron = "${strategy.backtest.task.cron:0 */1 * * * ?}")
    public void run() {
        if (!enabled) {
            return;
        }
        int effectiveParallelism = Math.max(1, parallelism);
        int currentInFlight = inFlightTaskIds.size();
        int availableSlots = Math.max(0, effectiveParallelism - currentInFlight);
        log.info("StrategyBacktestPullJob dispatch tick, parallelism:{}, inFlight:{}, availableSlots:{}",
                effectiveParallelism, currentInFlight, availableSlots);
        if (availableSlots <= 0) {
            return;
        }
        int fetchLimit = Math.min(Math.max(1, batchSize), availableSlots);
        String reclaimRunningBefore = computeReclaimRunningBefore();
        List<StrategyBacktestTaskRow> tasks = taskDao.pullRunnable(fetchLimit, reclaimRunningBefore);
        int pulledCount = tasks == null ? 0 : tasks.size();
        log.info("StrategyBacktestPullJob pulled tasks, requested:{}, pulled:{}, reclaimRunningBefore:{}",
                fetchLimit, pulledCount, reclaimRunningBefore);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (StrategyBacktestTaskRow task : tasks) {
            dispatchTask(task);
        }
    }

    private void dispatchTask(final StrategyBacktestTaskRow task) {
        if (task == null || isBlank(task.id)) {
            return;
        }
        if (!inFlightTaskIds.add(task.id)) {
            log.info("StrategyBacktestPullJob skip duplicate in-flight task:{}, strategy:{}@{}",
                    task.id, task.strategyName, task.strategyVersion);
            return;
        }
        try {
            strategyBacktestTaskExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    handleTask(task);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlightTaskIds.remove(task.id);
            log.warn("StrategyBacktestPullJob rejected task dispatch, task:{}, strategy:{}@{}",
                    task.id, task.strategyName, task.strategyVersion, e);
        } catch (Exception e) {
            inFlightTaskIds.remove(task.id);
            log.error("StrategyBacktestPullJob dispatch error, task:{}, strategy:{}@{}",
                    task.id, task.strategyName, task.strategyVersion, e);
        }
    }

    private void handleTask(StrategyBacktestTaskRow task) {
        long startNs = System.nanoTime();
        String threadName = Thread.currentThread().getName();
        LocalDateTime backtestStart = LocalDateTime.now();
        try {
            log.info("StrategyBacktestPullJob task start, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}, fromStatus:{}, heap:{}",
                    task.id, task.generationTaskId, task.candidateId, task.strategyName, task.strategyVersion,
                    threadName, task.status, memorySummary());
            if ("RUNNING".equalsIgnoreCase(task.status)) {
                log.warn("StrategyBacktestPullJob reclaim stale RUNNING task, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, lastUpdate:{}, processBootTime:{}, reclaimWindowMinutes:{}",
                        task.id, task.generationTaskId, task.candidateId, task.strategyName, task.strategyVersion,
                        task.updateTime, processBootTime, Math.max(1, runningReclaimMinutes));
            }
            taskDao.markRunning(task.id);
            log.info("StrategyBacktestPullJob task status -> RUNNING, task:{}, generationTaskId:{}, candidateId:{}, thread:{}",
                    task.id, task.generationTaskId, task.candidateId, threadName);
            StrategyCandidateRow candidate = taskDao.loadCandidate(task.strategyName, task.strategyVersion);
            if (candidate == null) {
                throw new IllegalStateException("candidate not found: " + task.strategyName + "@" + task.strategyVersion);
            }
            Map<String, Object> pipelinePayload = resolvePipelinePayload(task, candidate);
            markPipeline(task, candidate, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.RUNNING,
                    "", backtestStart, pipelinePayload);

            BacktestParam param = buildParam(task, candidate);
            BacktestModels.BacktestResponse response = backtestService.run(param,
                    task.fitWindowDays == null ? 120 : task.fitWindowDays.intValue(),
                    task.validateWindowDays == null ? 30 : task.validateWindowDays.intValue(),
                    task.forwardWindowDays == null ? 14 : task.forwardWindowDays.intValue());
            log.info("StrategyBacktestPullJob backtest run finished, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}, resultCount:{}, trialCount:{}, optimizationMode:{}, bestRank:{}",
                    task.id,
                    task.generationTaskId,
                    firstNotBlank(task.candidateId, candidate.id),
                    candidate.strategyName,
                    candidate.strategyVersion,
                    threadName,
                    response == null || response.results == null ? 0 : response.results.size(),
                    response == null ? 0 : response.trialCount,
                    response == null ? "" : response.optimizationMode,
                    response == null ? 0 : response.bestRank);
            Map<String, Object> optimizePayload = new LinkedHashMap<String, Object>(pipelinePayload);
            optimizePayload.put("trialCount", response == null ? 0 : response.trialCount);
            optimizePayload.put("optimizationMode", response == null ? "" : response.optimizationMode);
            optimizePayload.put("bestRank", response == null ? 0 : response.bestRank);
            optimizePayload.put("bestParamSetJson", response == null ? "{}" : response.bestParamSetJson);
            markPipeline(task, candidate, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.SUCCESS,
                    "", backtestStart, optimizePayload);
            markPipeline(task, candidate, StrategyPipelineModels.OPTIMIZE, StrategyPipelineModels.SUCCESS,
                    "", backtestStart, optimizePayload);
            StrategyAutoPublishDecision publishDecision =
                    strategyAutoPublishService.maybePublish(task, candidate, response);
            log.info("StrategyBacktestPullJob publish decision finished, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}, published:{}, action:{}, reason:{}",
                    task.id,
                    task.generationTaskId,
                    firstNotBlank(task.candidateId, candidate.id),
                    candidate.strategyName,
                    candidate.strategyVersion,
                    threadName,
                    publishDecision != null && publishDecision.published,
                    publishDecision == null ? "" : publishDecision.action,
                    publishDecision == null ? "" : publishDecision.reason);
            String reportPath = backtestReportService.writeReport(task.id, response, publishDecision);
            String compareReportPath = backtestReportService.writeCompareReport(response);
            log.info("StrategyBacktestPullJob report generation finished, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}, reportPath:{}, compareReportPath:{}",
                    task.id,
                    task.generationTaskId,
                    firstNotBlank(task.candidateId, candidate.id),
                    candidate.strategyName,
                    candidate.strategyVersion,
                    threadName,
                    reportPath,
                    compareReportPath);
            backtestResultClickHouseDao.insertResults(task.id, reportPath, response);
            log.info("StrategyBacktestPullJob result persistence finished, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}",
                    task.id, task.generationTaskId, firstNotBlank(task.candidateId, candidate.id),
                    candidate.strategyName, candidate.strategyVersion, threadName);

            Map<String, Object> taskResult = new LinkedHashMap<String, Object>();
            taskResult.put("taskId", task.id);
            taskResult.put("generationTaskId", task.generationTaskId);
            taskResult.put("candidateId", firstNotBlank(task.candidateId, candidate.id));
            taskResult.put("strategyName", candidate.strategyName);
            taskResult.put("strategyVersion", candidate.strategyVersion);
            taskResult.put("runtimeType", candidate.runtimeType);
            taskResult.put("scene", candidate.scene);
            taskResult.put("reportPath", reportPath);
            taskResult.put("compareReportPath", compareReportPath);
            taskResult.put("resultCount", response.results == null ? 0 : response.results.size());
            taskResult.put("windowMode", response.windowMode);
            taskResult.put("sliceCount", response.sliceCount);
            taskResult.put("symbolCount", response.symbolCount);
            taskResult.put("fitWindowDays", response.fitWindowDays);
            taskResult.put("validateWindowDays", response.validateWindowDays);
            taskResult.put("forwardWindowDays", response.forwardWindowDays);
            taskResult.put("minSliceCount", response.minSliceCount);
            taskResult.put("fitPnl", response.fitPnl);
            taskResult.put("validatePnl", response.validatePnl);
            taskResult.put("forwardPnl", response.forwardPnl);
            taskResult.put("totalPnl", response.totalPnl);
            taskResult.put("optimizationObjective", response.optimizationObjective);
            taskResult.put("minForwardContribution", response.minForwardContribution);
            taskResult.put("trialCount", response.trialCount);
            taskResult.put("trialBudget", response.trialBudget);
            taskResult.put("trialBudgetUsed", response.trialBudgetUsed);
            taskResult.put("trialBudgetHit", response.trialBudgetHit);
            taskResult.put("coarseCandidateCount", response.coarseCandidateCount);
            taskResult.put("fineCandidateCount", response.fineCandidateCount);
            taskResult.put("overfitPass", response.overfitPass);
            taskResult.put("overfitReason", response.overfitReason);
            taskResult.put("elapsedMs", response.elapsedMs);
            taskResult.put("fragileBest", response.fragileBest);
            taskResult.put("stableParamRangeJson", response.stableParamRangeJson);
            taskResult.put("neighborAvgPnl", response.neighborAvgPnl);
            taskResult.put("neighborWorstPnl", response.neighborWorstPnl);
            taskResult.put("autoPublishAction", publishDecision.action);
            taskResult.put("autoPublished", publishDecision.published);
            taskResult.put("autoPublishReason", publishDecision.reason);
            taskResult.put("baselineVersion", publishDecision.baselineVersion);
            taskResult.put("currentTotalPnl", publishDecision.currentTotalPnl);
            taskResult.put("currentValidatePnl", publishDecision.currentValidatePnl);
            taskResult.put("currentForwardPnl", publishDecision.currentForwardPnl);
            taskResult.put("currentForwardScore", publishDecision.currentForwardScore);
            taskResult.put("baselineTotalPnl", publishDecision.baselineTotalPnl);
            taskResult.put("baselineForwardScore", publishDecision.baselineForwardScore);
            taskDao.markSuccess(task.id, JsonUtils.Serializer(taskResult));
            log.info("StrategyBacktestPullJob task state persistence finished, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}",
                    task.id, task.generationTaskId, firstNotBlank(task.candidateId, candidate.id),
                    candidate.strategyName, candidate.strategyVersion, threadName);
            Map<String, Object> publishPayload = new LinkedHashMap<String, Object>(pipelinePayload);
            publishPayload.put("reportPath", reportPath);
            publishPayload.put("compareReportPath", compareReportPath);
            publishPayload.put("publishDecision", JsonUtils.Deserialize(JsonUtils.Serializer(publishDecision), Map.class));
            markPipeline(task, candidate, StrategyPipelineModels.PUBLISH,
                    publishDecision != null && publishDecision.published
                            ? StrategyPipelineModels.SUCCESS
                            : StrategyPipelineModels.SKIPPED,
                    publishDecision == null ? "" : publishDecision.reason,
                    backtestStart,
                    publishPayload);
            log.info("StrategyBacktestPullJob task status -> SUCCESS, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}, autoPublish:{}, reason:{}",
                    task.id, task.generationTaskId, firstNotBlank(task.candidateId, candidate.id),
                    candidate.strategyName, candidate.strategyVersion, threadName,
                    publishDecision.published, publishDecision.reason);
        } catch (BacktestTaskSuspendedException e) {
            log.warn("StrategyBacktestPullJob suspend task:{}, generationTaskId:{}, candidateId:{}, reason:{}, detail:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    e.getReason(), JsonUtils.Serializer(e.getDetail()), memorySummary());
            Map<String, Object> pipelinePayload = resolvePipelinePayload(task, null);
            pipelinePayload.put("suspendDetail", e.getDetail());
            markPipeline(task, null, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.SUSPENDED,
                    e.getReason(), backtestStart, pipelinePayload);
            String nextRetryTime = CLICKHOUSE_TIME.format(LocalDateTime.now().plusMinutes(Math.max(1, suspendedRetryMinutes)));
            BinanceKlineAutofillService.AutofillTriggerResult autofillResult = null;
            String autofillError = "";
            try {
                autofillResult = binanceKlineAutofillService.triggerIfNeeded(task, e);
                log.info("StrategyBacktestPullJob suspend recovery plan, task:{}, generationTaskId:{}, candidateId:{}, reason:{}, nextRetryTime:{}, autofillTriggered:{}, duplicate:{}, autofillKey:{}, requiredBeginDate:{}, requiredEndDate:{}, requiredBars:{}, actualBars:{}, missingBars:{}, message:{}",
                        task == null ? null : task.id,
                        task == null ? null : task.generationTaskId,
                        task == null ? null : task.candidateId,
                        e.getReason(),
                        nextRetryTime,
                        autofillResult != null && autofillResult.triggered,
                        autofillResult != null && autofillResult.duplicate,
                        autofillResult == null ? "" : autofillResult.key,
                        autofillResult == null ? "" : autofillResult.requiredBeginDate,
                        autofillResult == null ? "" : autofillResult.requiredEndDate,
                        autofillResult == null ? 0 : autofillResult.requiredBars,
                        autofillResult == null ? 0 : autofillResult.actualBars,
                        autofillResult == null ? 0 : autofillResult.missingBars,
                        autofillResult == null ? "" : autofillResult.message);
            } catch (Exception autofillEx) {
                autofillError = autofillEx.getMessage();
                log.warn("StrategyBacktestPullJob autofill trigger ignored, task:{}, reason:{}",
                        task == null ? null : task.id,
                        autofillEx.getMessage(),
                        autofillEx);
            }
            taskDao.markSuspended(task == null ? null : task.id,
                    e.getReason(),
                    buildSuspendPayload(task, e, nextRetryTime, autofillResult, autofillError),
                    nextRetryTime);
            log.info("StrategyBacktestPullJob task status -> SUSPENDED, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, nextRetryTime:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    threadName,
                    nextRetryTime,
                    memorySummary());
        } catch (Exception e) {
            log.error("StrategyBacktestPullJob handleTask error, task:{}, generationTaskId:{}, candidateId:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    memorySummary(), e);
            Map<String, Object> pipelinePayload = resolvePipelinePayload(task, null);
            pipelinePayload.put("error", e.getMessage());
            markPipeline(task, null, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.FAILED,
                    e.getMessage(), backtestStart, pipelinePayload);
            taskDao.markFailed(task == null ? null : task.id, e.getMessage());
            log.info("StrategyBacktestPullJob task status -> FAILED, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, error:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    threadName, e.getMessage(), memorySummary());
        } catch (Throwable t) {
            log.error("StrategyBacktestPullJob handleTask throwable, task:{}, generationTaskId:{}, candidateId:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    memorySummary(), t);
            try {
                Map<String, Object> pipelinePayload = resolvePipelinePayload(task, null);
                pipelinePayload.put("error", t.getMessage());
                markPipeline(task, null, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.FAILED,
                        t.getMessage(), backtestStart, pipelinePayload);
                taskDao.markFailed(task == null ? null : task.id, t.getMessage());
            } catch (Exception inner) {
                log.error("StrategyBacktestPullJob secondary failure while marking throwable state, task:{}",
                        task == null ? null : task.id, inner);
            }
            log.info("StrategyBacktestPullJob task status -> FAILED, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, error:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    threadName, t.getMessage(), memorySummary());
        } finally {
            inFlightTaskIds.remove(task == null ? null : task.id);
            long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            log.info("StrategyBacktestPullJob task end, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}, elapsedMs:{}, inFlight:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    task == null ? null : task.strategyName,
                    task == null ? null : task.strategyVersion,
                    threadName,
                    elapsedMs,
                    inFlightTaskIds.size(),
                    memorySummary());
        }
    }

    private BacktestParam buildParam(StrategyBacktestTaskRow task, StrategyCandidateRow candidate) {
        BacktestParam param = null;
        if (task != null && task.payload != null && !task.payload.trim().isEmpty()) {
            try {
                param = JsonUtils.Deserialize(task.payload, BacktestParam.class);
            } catch (Exception e) {
                log.warn("StrategyBacktestPullJob payload parse fallback, task:{}", task.id, e);
            }
            if (param == null || (isBlank(param.strategyName) && isBlank(param.strategyVersion)
                    && isBlank(param.symbol) && isBlank(param.symbols) && isBlank(param.text))) {
                try {
                    StrategyBacktestTaskPayloadEnvelope envelope =
                            JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
                    if (envelope != null && envelope.backtestParam != null) {
                        param = envelope.backtestParam;
                    }
                } catch (Exception e) {
                    log.warn("StrategyBacktestPullJob envelope parse fallback, task:{}", task.id, e);
                }
            }
        }
        if (param == null) {
            param = new BacktestParam();
        }
        if (isBlank(param.strategyName)) {
            param.strategyName = candidate.strategyName;
        }
        if (isBlank(param.strategyVersion)) {
            param.strategyVersion = candidate.strategyVersion;
        }
        if (isBlank(param.baselineVersion)) {
            param.baselineVersion = task.baselineVersion;
        }
        if (isBlank(param.runtimeType)) {
            param.runtimeType = candidate.runtimeType;
        }
        if (isBlank(param.scene)) {
            param.scene = candidate.scene;
        }
        if (isBlank(param.strategyPayload)) {
            param.strategyPayload = candidate.payload;
        }
        if (isBlank(param.symbol) && isBlank(param.symbols)) {
            param.symbol = defaultSymbol(candidate.scene);
            param.symbols = param.symbol;
        }
        if (isBlank(param.text)) {
            param.text = defaultText(candidate.scene);
        }
        if (isBlank(param.endDate)) {
            param.endDate = LocalDate.now().minusDays(1).toString();
        }
        if (isBlank(param.beginDate)) {
            int fitDays = task.fitWindowDays == null ? 120 : task.fitWindowDays.intValue();
            int validateDays = task.validateWindowDays == null ? 30 : task.validateWindowDays.intValue();
            int forwardDays = task.forwardWindowDays == null ? 14 : task.forwardWindowDays.intValue();
            int totalDays = Math.max(30, fitDays + validateDays + (forwardDays * 3));
            param.beginDate = LocalDate.parse(param.endDate).minusDays(totalDays).toString();
        }
        return param;
    }

    private String buildSuspendPayload(StrategyBacktestTaskRow task,
                                       BacktestTaskSuspendedException error,
                                       String nextRetryTime,
                                       BinanceKlineAutofillService.AutofillTriggerResult autofillResult,
                                       String autofillError) {
        StrategyBacktestTaskPayloadEnvelope envelope = new StrategyBacktestTaskPayloadEnvelope();
        if (task != null && task.payload != null && !task.payload.trim().isEmpty()) {
            try {
                BacktestParam param = JsonUtils.Deserialize(task.payload, BacktestParam.class);
                if (param != null && (!isBlank(param.strategyName) || !isBlank(param.symbol) || !isBlank(param.text))) {
                    envelope.backtestParam = param;
                }
            } catch (Exception e) {
                log.warn("buildSuspendPayload parse payload fallback, task:{}", task.id, e);
            }
            if (envelope.backtestParam == null) {
                try {
                    StrategyBacktestTaskPayloadEnvelope existing =
                            JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
                    if (existing != null && existing.backtestParam != null) {
                        envelope.backtestParam = existing.backtestParam;
                    }
                } catch (Exception e) {
                    log.warn("buildSuspendPayload parse envelope fallback, task:{}", task.id, e);
                }
            }
        }
        envelope.suspendDetail = error.getDetail();
        envelope.recoveryPlan = buildRecoveryPlan(error, nextRetryTime, autofillResult, autofillError);
        return JsonUtils.Serializer(envelope);
    }

    private Map<String, Object> buildRecoveryPlan(BacktestTaskSuspendedException error,
                                                  String nextRetryTime,
                                                  BinanceKlineAutofillService.AutofillTriggerResult autofillResult,
                                                  String autofillError) {
        Map<String, Object> recoveryPlan = new LinkedHashMap<String, Object>();
        recoveryPlan.put("reason", error == null ? "" : error.getReason());
        recoveryPlan.put("nextRetryTime", nextRetryTime);
        recoveryPlan.put("autofillTriggered", autofillResult != null && autofillResult.triggered);
        recoveryPlan.put("autofillDuplicate", autofillResult != null && autofillResult.duplicate);
        recoveryPlan.put("autofillKey", autofillResult == null ? "" : autofillResult.key);
        recoveryPlan.put("requiredBeginDate", autofillResult == null ? "" : autofillResult.requiredBeginDate);
        recoveryPlan.put("requiredEndDate", autofillResult == null ? "" : autofillResult.requiredEndDate);
        recoveryPlan.put("requiredBars", autofillResult == null ? 0 : autofillResult.requiredBars);
        recoveryPlan.put("actualBars", autofillResult == null ? 0 : autofillResult.actualBars);
        recoveryPlan.put("missingBars", autofillResult == null ? 0 : autofillResult.missingBars);
        recoveryPlan.put("autofillMessage", autofillResult == null ? "" : autofillResult.message);
        recoveryPlan.put("autofillError", isBlank(autofillError) ? "" : autofillError);
        recoveryPlan.put("recoverable", true);
        return recoveryPlan;
    }

    private String computeReclaimRunningBefore() {
        LocalDateTime staleCutoff = LocalDateTime.now().minusMinutes(Math.max(1, runningReclaimMinutes));
        LocalDateTime reclaimBefore = staleCutoff.isAfter(processBootAt) ? staleCutoff : processBootAt;
        return CLICKHOUSE_TIME.format(reclaimBefore);
    }

    private String defaultSymbol(String scene) {
        if ("trend".equalsIgnoreCase(scene)) {
            return "BTCUSDT";
        }
        if ("channel".equalsIgnoreCase(scene)) {
            return "SOLUSDT";
        }
        return "ETHUSDT";
    }

    private String defaultText(String scene) {
        if ("trend".equalsIgnoreCase(scene)) {
            return "1h";
        }
        return "15m";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String memorySummary() {
        Runtime runtime = Runtime.getRuntime();
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        long totalMb = runtime.totalMemory() / (1024L * 1024L);
        long freeMb = runtime.freeMemory() / (1024L * 1024L);
        long usedMb = totalMb - freeMb;
        return "usedMb=" + usedMb + ", freeMb=" + freeMb + ", totalMb=" + totalMb + ", maxMb=" + maxMb;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolvePipelinePayload(StrategyBacktestTaskRow task, StrategyCandidateRow candidate) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (strategyPipelineService != null && task != null && !isBlank(task.payload)) {
            payload.putAll(strategyPipelineService.parsePayload(task.payload));
        }
        if (candidate != null) {
            payload.putAll(candidate.payloadMap());
        }
        payload.put("taskId", task == null ? "" : task.id);
        payload.put("strategyName", candidate == null ? (task == null ? "" : task.strategyName) : candidate.strategyName);
        payload.put("strategyVersion", candidate == null ? (task == null ? "" : task.strategyVersion) : candidate.strategyVersion);
        return payload;
    }

    private void markPipeline(StrategyBacktestTaskRow task,
                              StrategyCandidateRow candidate,
                              String stage,
                              String status,
                              String reason,
                              LocalDateTime stageStart,
                              Map<String, Object> payload) {
        if (strategyPipelineService == null) {
            return;
        }
        String sourceType = payload == null || payload.get("sourceType") == null
                ? ""
                : String.valueOf(payload.get("sourceType"));
        String sourceRef = payload == null || payload.get("sourceRef") == null
                ? ""
                : String.valueOf(payload.get("sourceRef"));
        String strategyName = candidate == null ? (task == null ? "" : task.strategyName) : candidate.strategyName;
        String strategyVersion = candidate == null ? (task == null ? "" : task.strategyVersion) : candidate.strategyVersion;
        strategyPipelineService.markStage(
                sourceType,
                sourceRef,
                strategyName,
                strategyVersion,
                stage,
                status,
                reason,
                stageStart,
                payload);
    }

    private String firstNotBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return second;
    }
}
