package com.app.dc.service.simulation.runtime;

import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.pipeline.StrategyPipelineModels;
import com.app.dc.pipeline.StrategyPipelineService;
import com.app.dc.service.dao.BacktestResultClickHouseDao;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestReportService;
import com.app.dc.service.simulation.scene.SceneGatedShadowBacktestService;
import com.app.dc.service.simulation.BacktestService;
import com.app.dc.service.simulation.KlineSupportedTextProvider;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Value("${strategy.backtest.task.maxRuntimeMinutes:240}")
    private int maxRuntimeMinutes;

    @Value("${strategy.backtest.task.maxIdleProgressMinutes:30}")
    private int maxIdleProgressMinutes;

    @Autowired
    private StrategyBacktestTaskDao taskDao;

    @Autowired
    private BacktestService backtestService;

    @Autowired(required = false)
    private KlineSupportedTextProvider klineSupportedTextProvider;

    @Autowired
    private BacktestReportService backtestReportService;

    @Autowired
    private BacktestResultClickHouseDao backtestResultClickHouseDao;

    @Autowired
    private SceneGatedShadowBacktestService sceneGatedShadowBacktestService;

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
    private final Map<String, RunningTaskContext> runningTaskContexts =
            new ConcurrentHashMap<String, RunningTaskContext>();

    @Scheduled(cron = "${strategy.backtest.task.cron:0 */1 * * * ?}")
    public void run() {
        if (!enabled) {
            return;
        }
        sweepTimedOutTasks();
        int effectiveParallelism = Math.max(1, parallelism);
        int currentInFlight = inFlightTaskIds.size();
        int availableSlots = Math.max(0, effectiveParallelism - currentInFlight);
        log.info("StrategyBacktestPullJob dispatch tick, parallelism:{}, inFlight:{}, availableSlots:{}",
                effectiveParallelism, currentInFlight, availableSlots);
        if (availableSlots <= 0) {
            return;
        }
        int fetchLimit = computeFetchLimit(batchSize, effectiveParallelism, currentInFlight);
        String reclaimRunningBefore = computeReclaimRunningBefore();
        List<StrategyBacktestTaskRow> tasks = taskDao.pullRunnable(fetchLimit, reclaimRunningBefore);
        int pulledCount = tasks == null ? 0 : tasks.size();
        log.info("StrategyBacktestPullJob pulled tasks, requested:{}, pulled:{}, reclaimRunningBefore:{}",
                fetchLimit, pulledCount, reclaimRunningBefore);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        int dispatched = 0;
        for (StrategyBacktestTaskRow task : tasks) {
            if (dispatched >= availableSlots) {
                break;
            }
            if (dispatchTask(task)) {
                dispatched++;
            }
        }
    }

    static int computeFetchLimit(int batchSize, int parallelism, int currentInFlight) {
        int safeParallelism = Math.max(1, parallelism);
        int availableSlots = Math.max(0, safeParallelism - Math.max(0, currentInFlight));
        if (availableSlots <= 0) {
            return 0;
        }
        // Stale RUNNING rows can sort ahead of PENDING rows. Fetch enough rows to skip every
        // locally active duplicate while still dispatching no more than the available slots.
        int candidateWindow = availableSlots + Math.max(0, currentInFlight);
        return Math.min(Math.max(Math.max(1, batchSize), safeParallelism), candidateWindow);
    }

    private boolean dispatchTask(final StrategyBacktestTaskRow task) {
        if (task == null || isBlank(task.id)) {
            return false;
        }
        if (!inFlightTaskIds.add(task.id)) {
            log.info("StrategyBacktestPullJob skip duplicate in-flight task:{}, strategy:{}@{}",
                    task.id, task.strategyName, task.strategyVersion);
            return false;
        }
        final RunningTaskContext context = new RunningTaskContext(task);
        runningTaskContexts.put(task.id, context);
        try {
            Future<?> future = strategyBacktestTaskExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    handleTask(task);
                }
            });
            context.future = future;
            return true;
        } catch (RejectedExecutionException e) {
            runningTaskContexts.remove(task.id);
            inFlightTaskIds.remove(task.id);
            log.warn("StrategyBacktestPullJob rejected task dispatch, task:{}, strategy:{}@{}",
                    task.id, task.strategyName, task.strategyVersion, e);
            return false;
        } catch (Exception e) {
            runningTaskContexts.remove(task.id);
            inFlightTaskIds.remove(task.id);
            log.error("StrategyBacktestPullJob dispatch error, task:{}, strategy:{}@{}",
                    task.id, task.strategyName, task.strategyVersion, e);
            return false;
        }
    }

    private void handleTask(StrategyBacktestTaskRow task) {
        long startNs = System.nanoTime();
        String threadName = Thread.currentThread().getName();
        LocalDateTime backtestStart = LocalDateTime.now();
        final BacktestParam[] resolvedParamHolder = new BacktestParam[1];
        final RunningTaskContext context = task == null ? null : runningTaskContexts.get(task.id);
        if (context != null) {
            context.bindThread(Thread.currentThread());
        }
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

            resolvedParamHolder[0] = buildParam(task, candidate);
            BacktestModels.BacktestResponse response = backtestService.run(resolvedParamHolder[0],
                    task.fitWindowDays == null ? 120 : task.fitWindowDays.intValue(),
                    task.validateWindowDays == null ? 30 : task.validateWindowDays.intValue(),
                    task.forwardWindowDays == null ? 14 : task.forwardWindowDays.intValue(),
                    new BacktestService.ProgressListener() {
                        @Override
                        public void onProgress(Map<String, Object> progress) {
                            if (context != null) {
                                context.touch();
                            }
                            try {
                                taskDao.refreshRunningProgress(task.id, buildRunningPayload(task, resolvedParamHolder[0], progress));
                            } catch (Exception e) {
                                log.warn("StrategyBacktestPullJob refreshRunningProgress ignored, task:{}",
                                        task == null ? null : task.id, e);
                            }
                        }
                    });
            normalizeResponseFromTask(task, response);
            sceneGatedShadowBacktestService.enrich(response, candidate, resolvedParamHolder[0]);
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
            String reportPath = backtestReportService.writeReport(task.id, response, null);
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
            backtestResultClickHouseDao.insertResultsRequired(task.id, reportPath, response);
            log.info("StrategyBacktestPullJob result persistence finished, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}",
                    task.id, task.generationTaskId, firstNotBlank(task.candidateId, candidate.id),
                    candidate.strategyName, candidate.strategyVersion, threadName);
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
            // Rewrite the deterministic report after publishing so it includes the final release decision.
            reportPath = backtestReportService.writeReport(task.id, response, publishDecision);

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
            taskResult.put("sceneShadow", collectSceneShadow(response));
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
            taskResult.put("sceneQualificationPass", publishDecision.sceneQualificationPass);
            taskResult.put("sceneQualificationReason", publishDecision.sceneQualificationReason);
            taskResult.put("sceneRecordCount", publishDecision.sceneRecordCount);
            taskResult.put("sceneMatchedBarCount", publishDecision.sceneMatchedBarCount);
            taskResult.put("sceneTradeCount", publishDecision.sceneTradeCount);
            taskResult.put("scenePnl", publishDecision.scenePnl);
            taskResult.put("sceneProfitFactor", publishDecision.sceneProfitFactor);
            taskResult.put("sceneMaxDrawdownPct", publishDecision.sceneMaxDrawdownPct);
            taskDao.markSuccess(task.id, buildSuccessPayload(task, resolvedParamHolder[0], taskResult),
                    publishDecision != null && publishDecision.published);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String failureReason = resolveInterruptedFailureReason(task, context, e);
            log.warn("StrategyBacktestPullJob task interrupted, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, reason:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    threadName,
                    failureReason,
                    memorySummary());
            Map<String, Object> pipelinePayload = resolvePipelinePayload(task, null);
            pipelinePayload.put("error", failureReason);
            if (context != null && !isBlank(context.cancelReason)) {
                pipelinePayload.put("cancelReason", context.cancelReason);
            }
            markPipeline(task, null, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.FAILED,
                    failureReason, backtestStart, pipelinePayload);
            taskDao.markFailed(task == null ? null : task.id, failureReason);
            log.info("StrategyBacktestPullJob task status -> FAILED, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, error:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    threadName,
                    failureReason,
                    memorySummary());
        } catch (BacktestTaskSuspendedException e) {
            if (isNonRetryableSuspend(e)) {
                String failureReason = summarizeSuspendFailure(e);
                log.warn("StrategyBacktestPullJob non-retryable suspend -> FAILED, task:{}, generationTaskId:{}, candidateId:{}, reason:{}, detail:{}, heap:{}",
                        task == null ? null : task.id,
                        task == null ? null : task.generationTaskId,
                        task == null ? null : task.candidateId,
                        e.getReason(), JsonUtils.Serializer(e.getDetail()), memorySummary());
                Map<String, Object> pipelinePayload = resolvePipelinePayload(task, null);
                pipelinePayload.put("suspendDetail", e.getDetail());
                pipelinePayload.put("error", failureReason);
                markPipeline(task, null, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.FAILED,
                        failureReason, backtestStart, pipelinePayload);
                taskDao.markFailed(task == null ? null : task.id, failureReason);
                log.info("StrategyBacktestPullJob task status -> FAILED, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, error:{}, heap:{}",
                        task == null ? null : task.id,
                        task == null ? null : task.generationTaskId,
                        task == null ? null : task.candidateId,
                        threadName, failureReason, memorySummary());
                return;
            }
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
            if (autofillResult != null && autofillResult.terminal) {
                String failureReason = isBlank(autofillResult.failureReason)
                        ? summarizeSuspendFailure(e)
                        : autofillResult.failureReason;
                Map<String, Object> failedPayload = resolvePipelinePayload(task, null);
                failedPayload.put("suspendDetail", e.getDetail());
                failedPayload.put("recoveryPlan", buildRecoveryPlan(e, nextRetryTime, autofillResult, autofillError));
                failedPayload.put("error", failureReason);
                markPipeline(task, null, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.FAILED,
                        failureReason, backtestStart, failedPayload);
                taskDao.markFailed(task == null ? null : task.id, failureReason);
                log.info("StrategyBacktestPullJob task status -> FAILED after autofill terminal decision, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, error:{}, heap:{}",
                        task == null ? null : task.id,
                        task == null ? null : task.generationTaskId,
                        task == null ? null : task.candidateId,
                        threadName,
                        failureReason,
                        memorySummary());
                return;
            }
            taskDao.markSuspended(task == null ? null : task.id,
                    e.getReason(),
                    buildSuspendPayload(task, resolvedParamHolder[0], e, nextRetryTime, autofillResult, autofillError),
                    nextRetryTime);
            log.info("StrategyBacktestPullJob task status -> SUSPENDED, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, nextRetryTime:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    threadName,
                    nextRetryTime,
                    memorySummary());
        } catch (Exception e) {
            String failureReason = summarizeThrowable(e);
            if (isGeneratedStrategyRuntimeFailure(e)) {
                log.info("StrategyBacktestPullJob generated strategy runtime failure, task:{}, generationTaskId:{}, candidateId:{}, error:{}, heap:{}",
                        task == null ? null : task.id,
                        task == null ? null : task.generationTaskId,
                        task == null ? null : task.candidateId,
                        failureReason,
                        memorySummary());
            } else {
                log.error("StrategyBacktestPullJob handleTask error, task:{}, generationTaskId:{}, candidateId:{}, heap:{}",
                        task == null ? null : task.id,
                        task == null ? null : task.generationTaskId,
                        task == null ? null : task.candidateId,
                        memorySummary(), e);
            }
            Map<String, Object> pipelinePayload = resolvePipelinePayload(task, null);
            pipelinePayload.put("error", failureReason);
            markPipeline(task, null, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.FAILED,
                    failureReason, backtestStart, pipelinePayload);
            taskDao.markFailed(task == null ? null : task.id, failureReason);
            log.info("StrategyBacktestPullJob task status -> FAILED, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, error:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    threadName, failureReason, memorySummary());
        } catch (Throwable t) {
            String failureReason = summarizeThrowable(t);
            log.error("StrategyBacktestPullJob handleTask throwable, task:{}, generationTaskId:{}, candidateId:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    memorySummary(), t);
            try {
                Map<String, Object> pipelinePayload = resolvePipelinePayload(task, null);
                pipelinePayload.put("error", failureReason);
                markPipeline(task, null, StrategyPipelineModels.BACKTEST, StrategyPipelineModels.FAILED,
                        failureReason, backtestStart, pipelinePayload);
                taskDao.markFailed(task == null ? null : task.id, failureReason);
            } catch (Exception inner) {
                log.error("StrategyBacktestPullJob secondary failure while marking throwable state, task:{}",
                        task == null ? null : task.id, inner);
            }
            log.info("StrategyBacktestPullJob task status -> FAILED, task:{}, generationTaskId:{}, candidateId:{}, thread:{}, error:{}, heap:{}",
                    task == null ? null : task.id,
                    task == null ? null : task.generationTaskId,
                    task == null ? null : task.candidateId,
                    threadName, failureReason, memorySummary());
        } finally {
            if (task != null) {
                runningTaskContexts.remove(task.id);
            }
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

    private boolean isNonRetryableSuspend(BacktestTaskSuspendedException e) {
        if (e == null) {
            return false;
        }
        Map<String, Object> detail = e.getDetail();
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        return false;
    }

    private String summarizeSuspendFailure(BacktestTaskSuspendedException e) {
        if (e == null) {
            return "回测任务不可恢复";
        }
        Map<String, Object> detail = e.getDetail();
        if (detail != null && !detail.isEmpty()) {
            String message = detail.get("message") == null ? "" : String.valueOf(detail.get("message")).trim();
            String symbol = detail.get("symbol") == null ? "" : String.valueOf(detail.get("symbol")).trim();
            String text = detail.get("text") == null ? "" : String.valueOf(detail.get("text")).trim();
            String begin = detail.get("requiredBeginDate") == null ? "" : String.valueOf(detail.get("requiredBeginDate")).trim();
            String end = detail.get("requiredEndDate") == null ? "" : String.valueOf(detail.get("requiredEndDate")).trim();
            if (WalkForwardBacktestRunner.INSUFFICIENT_WINDOW_SLICES.equalsIgnoreCase(e.getReason())
                    || WalkForwardBacktestRunner.INSUFFICIENT_WINDOW_SLICES.equalsIgnoreCase(String.valueOf(detail.get("reason")))) {
                return String.format("时间范围不足，无法构造最小回测窗口（%s %s %s~%s，%s）",
                        symbol,
                        text,
                        begin,
                        end,
                        isBlank(message) ? "window slices insufficient" : message);
            }
            if (!isBlank(message)) {
                return message;
            }
        }
        return isBlank(e.getReason()) ? "回测任务不可恢复" : e.getReason();
    }

    private BacktestParam buildParam(StrategyBacktestTaskRow task, StrategyCandidateRow candidate) {
        BacktestParam param = null;
        if (task != null && task.payload != null && !task.payload.trim().isEmpty()) {
            try {
                StrategyBacktestTaskPayloadEnvelope envelope =
                        JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
                if (envelope != null && envelope.backtestParam != null) {
                    param = envelope.backtestParam;
                }
            } catch (Exception e) {
                log.warn("StrategyBacktestPullJob envelope parse fallback, task:{}", task.id, e);
            }
            if (param == null || (isBlank(param.strategyName) && isBlank(param.strategyVersion)
                    && isBlank(param.symbol) && isBlank(param.symbols) && isBlank(param.text))) {
                try {
                    param = JsonUtils.Deserialize(task.payload, BacktestParam.class);
                } catch (Exception e) {
                    log.warn("StrategyBacktestPullJob payload parse fallback, task:{}", task.id, e);
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
        if (isBlank(param.symbol) && !isBlank(param.symbols)) {
            param.symbol = firstSymbol(param.symbols);
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
                                       BacktestParam resolvedParam,
                                       BacktestTaskSuspendedException error,
                                       String nextRetryTime,
                                       BinanceKlineAutofillService.AutofillTriggerResult autofillResult,
                                       String autofillError) {
        StrategyBacktestTaskPayloadEnvelope envelope = new StrategyBacktestTaskPayloadEnvelope();
        envelope.backtestParam = extractCompactBacktestParam(task, resolvedParam);
        envelope.suspendDetail = error.getDetail();
        envelope.recoveryPlan = buildRecoveryPlan(error, nextRetryTime, autofillResult, autofillError);
        return JsonUtils.Serializer(envelope);
    }

    private List<Map<String, Object>> collectSceneShadow(BacktestModels.BacktestResponse response) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (response == null || response.results == null) {
            return rows;
        }
        for (BacktestModels.BacktestResult result : response.results) {
            if (result == null || result.sceneShadow == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("symbol", result.symbol);
            row.put("metrics", result.sceneShadow);
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private String buildSuccessPayload(StrategyBacktestTaskRow task, Map<String, Object> taskResult) {
        return buildSuccessPayload(task, null, taskResult);
    }

    @SuppressWarnings("unchecked")
    private String buildSuccessPayload(StrategyBacktestTaskRow task, BacktestParam resolvedParam, Map<String, Object> taskResult) {
        Map<String, Object> merged = extractCompactPayloadMap(task, resolvedParam);
        merged.remove("runningProgress");
        merged.put("taskResult", taskResult == null ? new LinkedHashMap<String, Object>() : taskResult);
        if (taskResult != null) {
            if (!merged.containsKey("reportPath") && taskResult.get("reportPath") != null) {
                merged.put("reportPath", taskResult.get("reportPath"));
            }
            if (!merged.containsKey("compareReportPath") && taskResult.get("compareReportPath") != null) {
                merged.put("compareReportPath", taskResult.get("compareReportPath"));
            }
            if (!merged.containsKey("resultCount") && taskResult.get("resultCount") != null) {
                merged.put("resultCount", taskResult.get("resultCount"));
            }
        }
        return JsonUtils.Serializer(merged);
    }

    private String buildRunningPayload(StrategyBacktestTaskRow task, Map<String, Object> runningProgress) {
        return buildRunningPayload(task, null, runningProgress);
    }

    private String buildRunningPayload(StrategyBacktestTaskRow task, BacktestParam resolvedParam, Map<String, Object> runningProgress) {
        StrategyBacktestTaskPayloadEnvelope envelope = new StrategyBacktestTaskPayloadEnvelope();
        if (task != null && task.payload != null && !task.payload.trim().isEmpty()) {
            try {
                StrategyBacktestTaskPayloadEnvelope existing =
                        JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
                if (existing != null) {
                    envelope.suspendDetail = existing.suspendDetail;
                    envelope.recoveryPlan = existing.recoveryPlan;
                }
            } catch (Exception ignore) {
            }
        }
        envelope.backtestParam = extractCompactBacktestParam(task, resolvedParam);
        envelope.runningProgress = runningProgress == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(runningProgress);
        return JsonUtils.Serializer(envelope);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractCompactPayloadMap(StrategyBacktestTaskRow task, BacktestParam resolvedParam) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>();
        if (task == null || isBlank(task.payload)) {
            BacktestParam param = compactBacktestParam(resolvedParam);
            if (param != null) {
                merged.put("backtestParam", param);
            }
            return merged;
        }
        try {
            StrategyBacktestTaskPayloadEnvelope envelope =
                    JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
            if (envelope != null) {
                if (envelope.backtestParam != null) {
                    merged.put("backtestParam", compactBacktestParam(envelope.backtestParam));
                }
                if (envelope.suspendDetail != null && !envelope.suspendDetail.isEmpty()) {
                    merged.put("suspendDetail", envelope.suspendDetail);
                }
                if (envelope.recoveryPlan != null && !envelope.recoveryPlan.isEmpty()) {
                    merged.put("recoveryPlan", envelope.recoveryPlan);
                }
                if (envelope.runningProgress != null && !envelope.runningProgress.isEmpty()) {
                    merged.put("runningProgress", envelope.runningProgress);
                }
                if (!merged.isEmpty()) {
                    return merged;
                }
            }
        } catch (Exception ignore) {
        }
        try {
            Map<String, Object> existing = JsonUtils.Deserialize(task.payload, Map.class);
            if (existing != null && !existing.isEmpty()) {
                merged.putAll(existing);
                Object backtestParamObj = merged.get("backtestParam");
                if (backtestParamObj instanceof Map) {
                    ((Map<String, Object>) backtestParamObj).remove("strategyPayload");
                }
                merged.remove("strategyPayload");
            }
        } catch (Exception e) {
            log.warn("extractCompactPayloadMap parse payload fallback, task:{}", task.id, e);
        }
        BacktestParam compact = extractCompactBacktestParam(task, resolvedParam);
        if (compact != null && !merged.containsKey("backtestParam")) {
            merged.put("backtestParam", compact);
        }
        return merged;
    }

    private BacktestParam extractCompactBacktestParam(StrategyBacktestTaskRow task, BacktestParam resolvedParam) {
        if (resolvedParam != null) {
            return compactBacktestParam(resolvedParam);
        }
        if (task == null || isBlank(task.payload)) {
            return null;
        }
        try {
            StrategyBacktestTaskPayloadEnvelope existing =
                    JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
            if (existing != null && existing.backtestParam != null) {
                return compactBacktestParam(existing.backtestParam);
            }
        } catch (Exception ignore) {
        }
        try {
            BacktestParam param = JsonUtils.Deserialize(task.payload, BacktestParam.class);
            if (param != null && (!isBlank(param.strategyName) || !isBlank(param.symbol) || !isBlank(param.text))) {
                return compactBacktestParam(param);
            }
        } catch (Exception e) {
            log.warn("extractCompactBacktestParam parse payload fallback, task:{}", task.id, e);
        }
        return null;
    }

    private BacktestParam compactBacktestParam(BacktestParam source) {
        if (source == null) {
            return null;
        }
        BacktestParam copy = new BacktestParam();
        copy.strategyName = source.strategyName;
        copy.strategyVersion = source.strategyVersion;
        copy.baselineVersion = source.baselineVersion;
        copy.runtimeType = source.runtimeType;
        copy.scene = source.scene;
        copy.strategyPayload = "";
        copy.strategyParams = source.strategyParams == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(source.strategyParams);
        copy.symbol = source.symbol;
        copy.symbols = source.symbols;
        copy.text = source.text;
        copy.beginDate = source.beginDate;
        copy.endDate = source.endDate;
        copy.initialCapital = source.initialCapital;
        copy.feeRatePct = source.feeRatePct;
        copy.entryMakerFeeRatePct = source.entryMakerFeeRatePct;
        copy.exitTakerFeeRatePct = source.exitTakerFeeRatePct;
        copy.fallbackStopLossPct = source.fallbackStopLossPct;
        copy.fallbackTakeProfitPct = source.fallbackTakeProfitPct;
        copy.maxHoldBars = source.maxHoldBars;
        copy.ignoreSentimentGuard = source.ignoreSentimentGuard;
        copy.allowMissingStageAnalysis = source.allowMissingStageAnalysis;
        return copy;
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

    private void sweepTimedOutTasks() {
        if (runningTaskContexts.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long maxRuntimeMs = Math.max(1L, maxRuntimeMinutes) * 60_000L;
        long maxIdleMs = Math.max(1L, maxIdleProgressMinutes) * 60_000L;
        for (RunningTaskContext context : runningTaskContexts.values()) {
            if (context == null || !context.shouldCancel(now, maxRuntimeMs, maxIdleMs)) {
                continue;
            }
            Future<?> future = context.future;
            boolean cancelIssued = future != null && future.cancel(true);
            log.warn("StrategyBacktestPullJob watchdog cancel, task:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, thread:{}, reason:{}, runtimeMinutes:{}, idleMinutes:{}, cancelIssued:{}",
                    context.taskId,
                    context.generationTaskId,
                    context.candidateId,
                    context.strategyName,
                    context.strategyVersion,
                    context.threadName,
                    context.cancelReason,
                    context.runtimeMinutes(now),
                    context.idleMinutes(now),
                    cancelIssued);
        }
    }

    private String resolveInterruptedFailureReason(StrategyBacktestTaskRow task,
                                                   RunningTaskContext context,
                                                   InterruptedException error) {
        if (context != null && !isBlank(context.cancelReason)) {
            return "backtest watchdog interrupted: " + context.cancelReason;
        }
        String message = error == null ? "" : error.getMessage();
        if (!isBlank(message)) {
            return message;
        }
        return "backtest interrupted: " + (task == null ? "" : task.id);
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

    private String firstSymbol(String symbols) {
        if (isBlank(symbols)) {
            return "";
        }
        String[] tokens = symbols.trim().split("[,|;\\s]+");
        return tokens.length == 0 ? "" : tokens[0].trim().toUpperCase(java.util.Locale.ENGLISH);
    }

    private String defaultText(String scene) {
        if (klineSupportedTextProvider != null) {
            return klineSupportedTextProvider.getDefaultText();
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

    private void normalizeResponseFromTask(StrategyBacktestTaskRow task,
                                           BacktestModels.BacktestResponse response) {
        if (task == null || response == null) {
            return;
        }
        int fitWindowDays = task.fitWindowDays == null ? 120 : task.fitWindowDays.intValue();
        int validateWindowDays = task.validateWindowDays == null ? 30 : task.validateWindowDays.intValue();
        int forwardWindowDays = task.forwardWindowDays == null ? 14 : task.forwardWindowDays.intValue();
        response.fitWindowDays = fitWindowDays;
        response.validateWindowDays = validateWindowDays;
        response.forwardWindowDays = forwardWindowDays;
        if (response.results != null) {
            for (BacktestModels.BacktestResult result : response.results) {
                if (result == null) {
                    continue;
                }
                result.fitWindowDays = fitWindowDays;
                result.validateWindowDays = validateWindowDays;
                result.forwardWindowDays = forwardWindowDays;
            }
        }
        if (response.trials != null) {
            for (BacktestModels.OptimizationTrial trial : response.trials) {
                if (trial == null) {
                    continue;
                }
                trial.fitWindowDays = fitWindowDays;
                trial.validateWindowDays = validateWindowDays;
                trial.forwardWindowDays = forwardWindowDays;
            }
        }
    }

    private String summarizeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        StringBuilder builder = new StringBuilder();
        if (root.getClass() != null) {
            builder.append(root.getClass().getSimpleName());
        }
        String message = root.getMessage();
        if (isBlank(message) && root != throwable) {
            message = throwable.getMessage();
        }
        if (!isBlank(message)) {
            if (builder.length() > 0) {
                builder.append(": ");
            }
            builder.append(message.trim());
        }
        if (builder.length() == 0) {
            builder.append(throwable.getClass().getSimpleName());
        }
        return builder.toString();
    }

    private boolean isGeneratedStrategyRuntimeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (containsGeneratedStrategyFrame(current) || isNegativeIndexMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsGeneratedStrategyFrame(Throwable throwable) {
        StackTraceElement[] stack = throwable == null ? null : throwable.getStackTrace();
        if (stack == null) {
            return false;
        }
        for (StackTraceElement element : stack) {
            if (element == null) {
                continue;
            }
            String className = element.getClassName();
            if (className != null && className.startsWith("com.app.dc.generated.autogen.")) {
                return true;
            }
        }
        return false;
    }

    private boolean isNegativeIndexMessage(String message) {
        return !isBlank(message) && message.contains("index = -");
    }

    static boolean isActivelyComputing(Thread.State state) {
        return state == Thread.State.RUNNABLE;
    }

    private static final class RunningTaskContext {
        private final String taskId;
        private final String generationTaskId;
        private final String candidateId;
        private final String strategyName;
        private final String strategyVersion;
        private final long startedAtMs = System.currentTimeMillis();
        private volatile long lastProgressAtMs = startedAtMs;
        private volatile String threadName = "";
        private volatile Thread workerThread;
        private volatile String cancelReason = "";
        private volatile Future<?> future;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

        private RunningTaskContext(StrategyBacktestTaskRow task) {
            this.taskId = task == null ? "" : task.id;
            this.generationTaskId = task == null ? "" : task.generationTaskId;
            this.candidateId = task == null ? "" : task.candidateId;
            this.strategyName = task == null ? "" : task.strategyName;
            this.strategyVersion = task == null ? "" : task.strategyVersion;
        }

        private void bindThread(Thread thread) {
            this.workerThread = thread;
            this.threadName = thread == null ? "" : thread.getName();
        }

        private void touch() {
            this.lastProgressAtMs = System.currentTimeMillis();
        }

        private boolean shouldCancel(long now, long maxRuntimeMs, long maxIdleMs) {
            if (cancelRequested.get()) {
                return false;
            }
            long runtimeMs = Math.max(0L, now - startedAtMs);
            long idleMs = Math.max(0L, now - lastProgressAtMs);
            if (runtimeMs >= maxRuntimeMs) {
                cancelReason = "runtime_exceeded_" + (maxRuntimeMs / 60000L) + "m";
                return cancelRequested.compareAndSet(false, true);
            }
            if (idleMs >= maxIdleMs) {
                Thread worker = workerThread;
                if (worker != null && worker.isAlive() && isActivelyComputing(worker.getState())) {
                    lastProgressAtMs = now;
                    return false;
                }
                cancelReason = "no_progress_exceeded_" + (maxIdleMs / 60000L) + "m";
                return cancelRequested.compareAndSet(false, true);
            }
            return false;
        }

        private long runtimeMinutes(long now) {
            return Math.max(0L, (now - startedAtMs) / 60000L);
        }

        private long idleMinutes(long now) {
            return Math.max(0L, (now - lastProgressAtMs) / 60000L);
        }
    }
}
