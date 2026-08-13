package com.app.dc.service.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.signal.StrategyParametersSupport;
import com.app.dc.service.simulation.BacktestModels.OptimizationTrial;
import com.app.dc.service.simulation.BacktestModels.BacktestResponse;
import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskDao;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import com.app.dc.service.simulation.runtime.SliceOptimizedWalkForwardRunner;
import com.app.dc.service.simulation.scene.DeepSeekSceneTimelineService;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

@Service
@Slf4j
public class BacktestService {

    public interface ProgressListener {
        void onProgress(Map<String, Object> progress);
    }

    @Autowired
    private BacktestQueryService queryService;

    @Autowired
    private BacktestSupportService supportService;

    @Autowired(required = false)
    private KlineSupportedTextProvider klineSupportedTextProvider;

    @Autowired
    private StrategyBacktestTaskDao strategyBacktestTaskDao;

    @Autowired
    private SliceOptimizedWalkForwardRunner sliceOptimizedWalkForwardRunner;

    @Autowired
    private DeepSeekSceneTimelineService sceneTimelineService;

    @Autowired
    private BacktestOptimizationService backtestOptimizationService;

    @Autowired(required = false)
    @Qualifier("strategyBacktestSymbolExecutor")
    private ThreadPoolTaskExecutor strategyBacktestSymbolExecutor;

    @Value("${strategy.backtest.maxTrialsPerTask:1000}")
    private int maxTrialsPerTask;

    public BacktestResponse run(BacktestParam param) throws Exception {
        return run(param, 120, 30, 14, null);
    }

    public BacktestResponse run(BacktestParam param,
                                int fitWindowDays,
                                int validateWindowDays,
                                int forwardWindowDays) throws Exception {
        return run(param, fitWindowDays, validateWindowDays, forwardWindowDays, null);
    }

    public BacktestResponse run(BacktestParam param,
                                int fitWindowDays,
                                int validateWindowDays,
                                int forwardWindowDays,
                                ProgressListener progressListener) throws Exception {
        BacktestParam req = param == null ? new BacktestParam() : param;
        if (req.strategyName == null || req.strategyName.trim().isEmpty()) {
            throw new IllegalArgumentException("strategyName is required");
        }
        if (req.strategyVersion == null || req.strategyVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("strategyVersion is required");
        }
        StrategyCandidateRow candidate = strategyBacktestTaskDao.loadCandidate(req.strategyName, req.strategyVersion);
        if (candidate == null) {
            throw new IllegalArgumentException("strategy candidate not found: " + req.strategyName + "@" + req.strategyVersion);
        }
        req = normalizeParam(req);
        List<String> symbols = supportService.resolveSymbols(req.symbols, req.symbol);
        req.runtimeType = candidate.runtimeType;
        req.scene = candidate.scene;
        req.strategyPayload = candidate.payload;
        if (req.strategyParams == null) {
            req.strategyParams = new LinkedHashMap<String, Object>();
        }

        BacktestOptimizationService.OptimizationPlan plan =
                backtestOptimizationService.buildPlan(candidate.parametersJson);
        WindowConfig windowConfig = resolveWindowConfig(plan, fitWindowDays, validateWindowDays, forwardWindowDays);
        log.info("BacktestService run start, strategy:{}@{}, symbols:{}, text:{}, range:{}~{}, runtimeType:{}, scene:{}, optimizationSupported:{}, optimizationMode:{}, objective:{}, window:{}/{}/{}, minSliceCount:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                symbols,
                req.text,
                req.beginDate,
                req.endDate,
                candidate.runtimeType,
                candidate.scene,
                plan.optimizationSupported,
                plan.optimizationMode,
                plan.objective,
                windowConfig.fitWindowDays,
                windowConfig.validateWindowDays,
                windowConfig.forwardWindowDays,
                windowConfig.minSliceCount);
        Map<String, List<TTbookOhlc>> ohlcCache = new ConcurrentHashMap<String, List<TTbookOhlc>>();
        int trialBudget = Math.max(1, maxTrialsPerTask);
        emitProgress(progressListener, buildProgressPayload(
                candidate, req, plan, "SLICE_OPTIMIZED",
                0, 0, 0, 0, 0, 0, trialBudget));
        BacktestResponse response = runSingle(candidate, req, symbols, Collections.<String, Object>emptyMap(),
                windowConfig, plan, ohlcCache, trialBudget);
        response.optimizationMode = plan.optimizationMode;
        response.optimizationObjective = plan.objective;
        response.minForwardContribution = plan.minForwardContribution;
        response.trials = collectOptimizationTrials(response.results);
        response.trialCount = response.trials == null ? 0 : response.trials.size();
        response.trialBudget = trialBudget;
        response.trialBudgetUsed = response.trialCount;
        response.trialBudgetHit = response.trialCount >= trialBudget ? 1 : 0;
        response.coarseCandidateCount = countTrialsByPhase(response.trials, "COARSE");
        response.fineCandidateCount = countTrialsByPhase(response.trials, "FINE");
        response.bestRank = minRank(response.trials);
        response.elapsedMs = nzInt(response.elapsedMs);
        response.symbolCount = nzInt(response.symbolCount);
        response.fitWindowDays = windowConfig.fitWindowDays;
        response.validateWindowDays = windowConfig.validateWindowDays;
        response.forwardWindowDays = windowConfig.forwardWindowDays;
        response.minSliceCount = windowConfig.minSliceCount;
        response.fragileBest = aggregateFragileBest(response.results);
        response.stableParamRangeJson = aggregateStableParamRange(response.results);
        response.neighborAvgPnl = BigDecimal.ZERO;
        response.neighborWorstPnl = BigDecimal.ZERO;
        if (response.results != null) {
            for (BacktestResult result : response.results) {
                if (result == null) {
                    continue;
                }
                result.optimizationMode = response.optimizationMode;
                result.optimizationObjective = response.optimizationObjective;
                result.minForwardContribution = response.minForwardContribution;
                result.fitWindowDays = response.fitWindowDays;
                result.validateWindowDays = response.validateWindowDays;
                result.forwardWindowDays = response.forwardWindowDays;
                result.minSliceCount = response.minSliceCount;
            }
        }
        log.info("BacktestService run end, strategy:{}@{}, trialCount:{}, totalPnl:{}, validatePnl:{}, forwardPnl:{}, sliceCount:{}, elapsedMs:{}, fragileBest:{}, oosPass:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                response.trialCount,
                response.totalPnl,
                response.validatePnl,
                response.forwardPnl,
                response.sliceCount,
                response.elapsedMs,
                response.fragileBest,
                response.oosPass);
        return response;
    }

    private List<TrialExecution> executeTrials(String phase,
                                               int startTrialNo,
                                               StrategyCandidateRow candidate,
                                               BacktestParam baseParam,
                                               List<String> symbols,
                                               List<Map<String, Object>> paramSets,
                                               WindowConfig windowConfig,
                                               BacktestOptimizationService.OptimizationPlan plan,
                                               Map<String, List<TTbookOhlc>> ohlcCache,
                                               ProgressListener progressListener,
                                               int coarseCandidateCount,
                                               int fineCandidateCount,
                                               int trialBudget,
                                               int completedBeforePhase,
                                               int plannedCoarseTrials) throws Exception {
        if (paramSets == null || paramSets.isEmpty()) {
            return Collections.emptyList();
        }
        log.info("BacktestService phase start, strategy:{}@{}, phase:{}, startTrialNo:{}, paramSetCount:{}, symbols:{}",
                candidate.strategyName, candidate.strategyVersion, phase, startTrialNo, paramSets.size(), symbols);
        List<TrialExecution> executions = new ArrayList<TrialExecution>();
        int trialNo = startTrialNo;
        for (Map<String, Object> paramSet : paramSets) {
            checkInterrupted("backtest_execute_trials_loop");
            long trialStartNs = System.nanoTime();
            log.info("BacktestService trial start, strategy:{}@{}, phase:{}, trialNo:{}, params:{}",
                    candidate.strategyName, candidate.strategyVersion, phase, trialNo, JsonUtils.Serializer(paramSet));
            BacktestResponse response = runSingle(candidate, baseParam, symbols, paramSet,
                    windowConfig, plan, ohlcCache, trialBudget);
            OptimizationTrial trial = backtestOptimizationService.buildTrial(trialNo, phase,
                    candidate.strategyName, candidate.strategyVersion,
                    response.symbol, response.text, paramSet, response);
            TrialExecution execution = new TrialExecution();
            execution.trial = trial;
            execution.paramSet = paramSet == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(paramSet);
            executions.add(execution);
            emitProgress(progressListener, buildProgressPayload(
                    candidate,
                    baseParam,
                    plan,
                    phase,
                    coarseCandidateCount,
                    fineCandidateCount,
                    plannedCoarseTrials,
                    "FINE".equalsIgnoreCase(phase) ? paramSets.size() : 0,
                    completedBeforePhase + executions.size(),
                    trialNo,
                    trialBudget));
            log.info("BacktestService trial end, strategy:{}@{}, phase:{}, trialNo:{}, totalPnl:{}, validatePnl:{}, forwardPnl:{}, overfitPass:{}, sliceCount:{}, elapsedMs:{}",
                    candidate.strategyName,
                    candidate.strategyVersion,
                    phase,
                    trialNo,
                    response.totalPnl,
                    response.validatePnl,
                    response.forwardPnl,
                    response.overfitPass,
                    response.sliceCount,
                    Math.max(0L, (System.nanoTime() - trialStartNs) / 1_000_000L));
            trialNo++;
        }
        log.info("BacktestService phase end, strategy:{}@{}, phase:{}, executedTrials:{}, heap:{}",
                candidate.strategyName, candidate.strategyVersion, phase, executions.size(), heapSummary());
        return executions;
    }

    private void emitProgress(ProgressListener progressListener, Map<String, Object> progress) {
        if (progressListener == null || progress == null || progress.isEmpty()) {
            return;
        }
        try {
            progressListener.onProgress(progress);
        } catch (Exception e) {
            log.warn("BacktestService progress callback error", e);
        }
    }

    private Map<String, Object> buildProgressPayload(StrategyCandidateRow candidate,
                                                     BacktestParam req,
                                                     BacktestOptimizationService.OptimizationPlan plan,
                                                     String phase,
                                                     int coarseCandidateCount,
                                                     int fineCandidateCount,
                                                     int coarseTrialCount,
                                                     int fineTrialCount,
                                                     int completedTrialCount,
                                                     int currentTrialNo,
                                                     int trialBudget) {
        Map<String, Object> progress = new LinkedHashMap<String, Object>();
        progress.put("phase", phase);
        progress.put("strategyName", candidate == null ? "" : candidate.strategyName);
        progress.put("strategyVersion", candidate == null ? "" : candidate.strategyVersion);
        progress.put("symbol", req == null ? "" : req.symbol);
        progress.put("symbols", req == null ? "" : req.symbols);
        progress.put("text", req == null ? "" : req.text);
        progress.put("optimizationMode", plan == null ? "" : plan.optimizationMode);
        progress.put("coarseCandidateCount", coarseCandidateCount);
        progress.put("fineCandidateCount", fineCandidateCount);
        progress.put("coarseTrialCount", coarseTrialCount);
        progress.put("fineTrialCount", fineTrialCount);
        progress.put("plannedTrialCount", coarseTrialCount + fineTrialCount);
        progress.put("completedTrialCount", completedTrialCount);
        progress.put("currentTrialNo", currentTrialNo);
        progress.put("trialBudget", trialBudget);
        return progress;
    }

    private List<OptimizationTrial> collectTrials(List<TrialExecution> executions) {
        List<OptimizationTrial> trials = new ArrayList<OptimizationTrial>();
        if (executions == null) {
            return trials;
        }
        for (TrialExecution execution : executions) {
            if (execution != null && execution.trial != null) {
                trials.add(execution.trial);
            }
        }
        return trials;
    }

    private TrialExecution bestTrial(List<TrialExecution> executions) {
        TrialExecution best = null;
        if (executions == null) {
            return null;
        }
        for (TrialExecution execution : executions) {
            if (execution == null || execution.trial == null) {
                continue;
            }
            if (best == null) {
                best = execution;
                continue;
            }
            int currentRank = execution.trial.rank == null ? Integer.MAX_VALUE : execution.trial.rank.intValue();
            int bestRank = best.trial.rank == null ? Integer.MAX_VALUE : best.trial.rank.intValue();
            if (currentRank < bestRank) {
                best = execution;
            }
        }
        return best;
    }

    private List<Map<String, Object>> limitTrialSets(List<Map<String, Object>> paramSets,
                                                     int budget,
                                                     String phase,
                                                     StrategyCandidateRow candidate) {
        if (paramSets == null || paramSets.isEmpty()) {
            return Collections.emptyList();
        }
        int safeBudget = Math.max(0, budget);
        if (paramSets.size() <= safeBudget) {
            return paramSets;
        }
        log.warn("BacktestService trial budget cap, strategy:{}@{}, phase:{}, budgetRemaining:{}, planned:{}, trimmed:{}",
                candidate == null ? "" : candidate.strategyName,
                candidate == null ? "" : candidate.strategyVersion,
                phase,
                safeBudget,
                paramSets.size(),
                Math.max(0, paramSets.size() - safeBudget));
        if (safeBudget == 0) {
            return Collections.emptyList();
        }
        return new ArrayList<Map<String, Object>>(paramSets.subList(0, safeBudget));
    }

    private BacktestResponse runSingle(StrategyCandidateRow candidate,
                                       BacktestParam req,
                                       List<String> symbols,
                                       Map<String, Object> trialParams,
                                       WindowConfig windowConfig,
                                       BacktestOptimizationService.OptimizationPlan plan,
                                       Map<String, List<TTbookOhlc>> ohlcCache,
                                       int trialBudget) throws Exception {
        long startNs = System.nanoTime();
        BacktestResponse response = new BacktestResponse();
        response.symbol = symbols.size() == 1 ? symbols.get(0) : "MULTI";
        response.symbols = symbols;
        response.symbolCount = symbols == null ? 0 : symbols.size();
        response.text = req.text;
        response.beginDate = req.beginDate;
        response.endDate = req.endDate;
        response.strategyName = candidate.strategyName;
        response.strategyVersion = candidate.strategyVersion;
        response.baselineVersion = req.baselineVersion;
        response.runtimeType = candidate.runtimeType;
        response.scene = candidate.scene;
        response.windowMode = BacktestModels.SCENE_CONDITIONED_WINDOW_MODE;
        response.fitWindowDays = windowConfig.fitWindowDays;
        response.validateWindowDays = windowConfig.validateWindowDays;
        response.forwardWindowDays = windowConfig.forwardWindowDays;
        response.minSliceCount = windowConfig.minSliceCount;
        response.optimizationMode = plan == null ? "" : plan.optimizationMode;
        response.optimizationObjective = plan == null ? "" : plan.objective;
        response.minForwardContribution = plan == null ? BigDecimal.ZERO : plan.minForwardContribution;
        log.info("BacktestService runSingle start, strategy:{}@{}, symbols:{}, trialParams:{}",
                candidate.strategyName, candidate.strategyVersion, symbols, JsonUtils.Serializer(trialParams));
        checkInterrupted("backtest_run_single_start");

        List<BacktestResult> results = new ArrayList<BacktestResult>();
        BigDecimal fitPnl = BigDecimal.ZERO;
        BigDecimal validatePnl = BigDecimal.ZERO;
        BigDecimal forwardPnl = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        BigDecimal validatePrimaryScore = BigDecimal.ZERO;
        BigDecimal forwardAuxScore = BigDecimal.ZERO;
        BigDecimal feeAdjustedValidatePnl = BigDecimal.ZERO;
        BigDecimal feeAdjustedForwardPnl = BigDecimal.ZERO;
        BigDecimal sliceParamDriftScore = BigDecimal.ZERO;
        int sliceCount = Integer.MAX_VALUE;
        List<BacktestResult> symbolResults = executeSymbols(candidate, req, symbols, trialParams, windowConfig, plan, ohlcCache, trialBudget);
        symbolResults.sort(new Comparator<BacktestResult>() {
            @Override
            public int compare(BacktestResult left, BacktestResult right) {
                return safeString(left == null ? null : left.symbol).compareTo(safeString(right == null ? null : right.symbol));
            }
        });
        for (BacktestResult result : symbolResults) {
            checkInterrupted("backtest_run_single_merge_results");
            if (result == null) {
                continue;
            }
            results.add(result);
            fitPnl = fitPnl.add(nz(result.fitPnl));
            validatePnl = validatePnl.add(nz(result.validatePnl));
            forwardPnl = forwardPnl.add(nz(result.forwardPnl));
            totalPnl = totalPnl.add(nz(result.totalPnl));
            validatePrimaryScore = validatePrimaryScore.add(nz(result.validatePrimaryScore));
            forwardAuxScore = forwardAuxScore.add(nz(result.forwardAuxScore));
            feeAdjustedValidatePnl = feeAdjustedValidatePnl.add(nz(result.feeAdjustedValidatePnl));
            feeAdjustedForwardPnl = feeAdjustedForwardPnl.add(nz(result.feeAdjustedForwardPnl));
            sliceParamDriftScore = sliceParamDriftScore.add(nz(result.sliceParamDriftScore));
            sliceCount = Math.min(sliceCount, result.sliceCount == null ? 0 : result.sliceCount.intValue());
        }
        response.results = results.isEmpty() ? Collections.<BacktestResult>emptyList() : results;
        response.fitPnl = scale(fitPnl.doubleValue());
        response.validatePnl = scale(validatePnl.doubleValue());
        response.forwardPnl = scale(forwardPnl.doubleValue());
        response.totalPnl = scale(totalPnl.doubleValue());
        response.validatePrimaryScore = scale(validatePrimaryScore.doubleValue());
        response.forwardAuxScore = results.isEmpty() ? BigDecimal.ZERO : scale(forwardAuxScore.doubleValue() / Math.max(1, results.size()));
        response.forwardScore = response.forwardAuxScore;
        response.feeAdjustedValidatePnl = scale(feeAdjustedValidatePnl.doubleValue());
        response.feeAdjustedForwardPnl = scale(feeAdjustedForwardPnl.doubleValue());
        response.sliceParamDriftScore = results.isEmpty() ? BigDecimal.ZERO : scale(sliceParamDriftScore.doubleValue() / Math.max(1, results.size()));
        response.sliceCount = results.isEmpty() ? 0 : sliceCount;
        GateDecision gate = evaluateAggregateGate(response.fitPnl, response.validatePnl, response.forwardPnl, sumTradeCount(results));
        response.overfitPass = results.isEmpty() ? 0 : (gate.overfitPass ? 1 : 0);
        response.overfitReason = gate.reason;
        response.elapsedMs = elapsedMs(startNs);
        response.oosPass = gate.oosPass ? 1 : 0;
        response.bestParamSetJson = aggregateBestParamSets(results, trialParams);
        response.trials = collectOptimizationTrials(results);
        response.trialCount = response.trials == null ? 0 : response.trials.size();
        log.info("BacktestService runSingle end, strategy:{}@{}, totalPnl:{}, validatePnl:{}, forwardPnl:{}, sliceCount:{}, elapsedMs:{}",
                candidate.strategyName, candidate.strategyVersion,
                response.totalPnl, response.validatePnl, response.forwardPnl, response.sliceCount, response.elapsedMs);
        return response;
    }

    private GateDecision evaluateAggregateGate(BigDecimal fitPnl,
                                               BigDecimal validatePnl,
                                               BigDecimal forwardPnl,
                                               int tradeCount) {
        GateDecision gate = new GateDecision();
        gate.overfitPass = true;
        gate.oosPass = false;
        gate.reason = "";
        if (nz(fitPnl).compareTo(BigDecimal.ZERO) > 0
                && nz(validatePnl).compareTo(BigDecimal.ZERO) <= 0) {
            gate.overfitPass = false;
            gate.reason = "fit_pnl > 0 but validate_pnl <= 0";
        } else if (nz(validatePnl).compareTo(BigDecimal.ZERO) > 0
                && nz(forwardPnl).compareTo(BigDecimal.ZERO) <= 0) {
            gate.overfitPass = false;
            gate.reason = "validate_pnl > 0 but forward_pnl <= 0";
        }
        gate.oosPass = gate.overfitPass
                && nz(validatePnl).compareTo(BigDecimal.ZERO) > 0
                && nz(forwardPnl).compareTo(BigDecimal.ZERO) > 0
                && tradeCount > 0;
        return gate;
    }

    private List<BacktestResult> executeSymbols(final StrategyCandidateRow candidate,
                                                final BacktestParam req,
                                                List<String> symbols,
                                                final Map<String, Object> trialParams,
                                                final WindowConfig windowConfig,
                                                final BacktestOptimizationService.OptimizationPlan plan,
                                                final Map<String, List<TTbookOhlc>> ohlcCache,
                                                final int trialBudget) throws Exception {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyList();
        }
        if (strategyBacktestSymbolExecutor == null || symbols.size() <= 1 || requiresSerialSymbolExecution(candidate)) {
            log.info("BacktestService executeSymbols serial, strategy:{}@{}, symbolCount:{}, reason:{}",
                    candidate.strategyName,
                    candidate.strategyVersion,
                    symbols.size(),
                    strategyBacktestSymbolExecutor == null ? "executor_null"
                            : (symbols.size() <= 1 ? "single_symbol" : "shared_runtime"));
            return executeSymbolsSerial(candidate, req, symbols, trialParams, windowConfig, plan, ohlcCache, trialBudget);
        }
        log.info("BacktestService executeSymbols parallel, strategy:{}@{}, symbolCount:{}, executorActive:{}, executorPool:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                    symbols.size(),
                    strategyBacktestSymbolExecutor.getActiveCount(),
                    strategyBacktestSymbolExecutor.getPoolSize());
        List<Future<BacktestResult>> futures = new ArrayList<Future<BacktestResult>>();
        final int perSymbolBudget = Math.max(1, trialBudget / Math.max(1, symbols.size()));
        for (final String symbol : symbols) {
            futures.add(strategyBacktestSymbolExecutor.submit(new Callable<BacktestResult>() {
                @Override
                public BacktestResult call() throws Exception {
                    return runSingleSymbol(candidate, req, symbol, trialParams, windowConfig, plan, ohlcCache, perSymbolBudget);
                }
            }));
        }
        List<BacktestResult> results = new ArrayList<BacktestResult>();
        for (Future<BacktestResult> future : futures) {
            try {
                checkInterrupted("backtest_execute_symbols_parallel_wait");
                results.add(future.get());
            } catch (InterruptedException e) {
                cancelFutures(futures);
                Thread.currentThread().interrupt();
                throw e;
            } catch (CancellationException e) {
                cancelFutures(futures);
                throw new InterruptedException("backtest interrupted while waiting symbol future");
            } catch (Exception e) {
                cancelFutures(futures);
                throw e;
            }
        }
        return results;
    }

    private boolean requiresSerialSymbolExecution(StrategyCandidateRow candidate) {
        if (candidate == null) {
            return true;
        }
        // Versioned/JAR strategies are loaded into a shared BuySellSignalFacade registry.
        // Parallel symbol execution can unload/reload the same strategy handle mid-run.
        return "JAR".equalsIgnoreCase(candidate.runtimeType);
    }

    private List<BacktestResult> executeSymbolsSerial(StrategyCandidateRow candidate,
                                                      BacktestParam req,
                                                      List<String> symbols,
                                                      Map<String, Object> trialParams,
                                                      WindowConfig windowConfig,
                                                      BacktestOptimizationService.OptimizationPlan plan,
                                                      Map<String, List<TTbookOhlc>> ohlcCache,
                                                      int trialBudget) throws Exception {
        List<BacktestResult> results = new ArrayList<BacktestResult>();
        int perSymbolBudget = Math.max(1, trialBudget / Math.max(1, symbols.size()));
        for (String symbol : symbols) {
            checkInterrupted("backtest_execute_symbols_serial_loop");
            results.add(runSingleSymbol(candidate, req, symbol, trialParams, windowConfig, plan, ohlcCache, perSymbolBudget));
        }
        return results;
    }

    private void cancelFutures(List<Future<BacktestResult>> futures) {
        if (futures == null) {
            return;
        }
        for (Future<BacktestResult> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private void checkInterrupted(String stage) throws InterruptedException {
        com.app.dc.service.simulation.runtime.BacktestExecutionGuard.checkInterrupted(stage);
    }

    private BacktestResult runSingleSymbol(StrategyCandidateRow candidate,
                                           BacktestParam req,
                                           String symbol,
                                           Map<String, Object> trialParams,
                                           WindowConfig windowConfig,
                                           BacktestOptimizationService.OptimizationPlan plan,
                                           Map<String, List<TTbookOhlc>> ohlcCache,
                                           int trialBudget) throws Exception {
        List<TTbookOhlc> ohlcList = loadOhlc(req, symbol, ohlcCache);
        BacktestParam symbolParam = copyParamForSymbol(req, symbol);
        applyTrialParamOverrides(symbolParam, trialParams);
        DeepSeekSceneTimelineService.Timeline timeline = sceneTimelineService.load(
                symbol, symbolParam.beginDate, symbolParam.endDate);
        if (timeline.hasError()) {
            throw new IllegalStateException("scene timeline query failed: " + timeline.error());
        }
        BacktestResult result = sliceOptimizedWalkForwardRunner.runSceneConditioned(
                candidate, symbolParam, ohlcList, plan,
                windowConfig.fitWindowDays, windowConfig.validateWindowDays,
                windowConfig.forwardWindowDays, windowConfig.minSliceCount, trialBudget, timeline);
        result.symbolCount = 1;
        result.fitWindowDays = windowConfig.fitWindowDays;
        result.validateWindowDays = windowConfig.validateWindowDays;
        result.forwardWindowDays = windowConfig.forwardWindowDays;
        result.minSliceCount = windowConfig.minSliceCount;
        result.trialBudget = trialBudget;
        result.trialBudgetUsed = result.trialCount;
        result.trialBudgetHit = nzInt(result.trialCount) >= trialBudget ? 1 : 0;
        return result;
    }

    private List<TTbookOhlc> loadOhlc(BacktestParam req, String symbol, Map<String, List<TTbookOhlc>> ohlcCache) throws Exception {
        String cacheKey = buildOhlcCacheKey(symbol, req.text, req.beginDate, req.endDate);
        List<TTbookOhlc> cached = ohlcCache == null ? null : ohlcCache.get(cacheKey);
        if (cached != null) {
            log.info("BacktestService loadOhlc cache hit, symbol:{}, text:{}, range:{}~{}, bars:{}",
                    symbol, req.text, req.beginDate, req.endDate, cached.size());
            return cached;
        }
        log.info("BacktestService loadOhlc query, symbol:{}, text:{}, range:{}~{}",
                symbol, req.text, req.beginDate, req.endDate);
        List<TTbookOhlc> loaded = queryService.queryOhlc(symbol, req.text, req.beginDate, req.endDate);
        List<TTbookOhlc> safe = loaded == null ? Collections.<TTbookOhlc>emptyList() : loaded;
        if (ohlcCache != null) {
            ohlcCache.put(cacheKey, safe);
        }
        log.info("BacktestService loadOhlc loaded, symbol:{}, text:{}, range:{}~{}, bars:{}",
                symbol, req.text, req.beginDate, req.endDate, safe.size());
        return safe;
    }

    private WindowConfig resolveWindowConfig(BacktestOptimizationService.OptimizationPlan plan,
                                             int fitWindowDays,
                                             int validateWindowDays,
                                             int forwardWindowDays) {
        WindowConfig config = new WindowConfig();
        config.fitWindowDays = fitWindowDays > 0
                ? fitWindowDays
                : (plan == null ? 120 : plan.fitWindowDays);
        config.validateWindowDays = validateWindowDays > 0
                ? validateWindowDays
                : (plan == null ? 30 : plan.validateWindowDays);
        config.forwardWindowDays = forwardWindowDays > 0
                ? forwardWindowDays
                : (plan == null ? 14 : plan.forwardWindowDays);
        config.minSliceCount = plan == null ? 3 : plan.minSliceCount;
        return config;
    }

    private String buildOhlcCacheKey(String symbol, String text, String beginDate, String endDate) {
        return safeString(symbol) + "|" + safeString(text) + "|" + safeString(beginDate) + "|" + safeString(endDate);
    }

    private void applyTrialParamOverrides(BacktestParam param, Map<String, Object> trialParams) {
        if (param == null || trialParams == null || trialParams.isEmpty()) {
            return;
        }
        if (param.strategyParams == null) {
            param.strategyParams = new LinkedHashMap<String, Object>();
        }
        for (Map.Entry<String, Object> entry : trialParams.entrySet()) {
            param.strategyParams.put(entry.getKey(), entry.getValue());
        }
    }

    private BacktestParam copyParamForSymbol(BacktestParam source, String symbol) {
        BacktestParam target = new BacktestParam();
        target.strategyName = source.strategyName;
        target.strategyVersion = source.strategyVersion;
        target.baselineVersion = source.baselineVersion;
        target.runtimeType = source.runtimeType;
        target.scene = source.scene;
        target.strategyPayload = source.strategyPayload;
        target.strategyParams = new LinkedHashMap<String, Object>();
        if (source.strategyParams != null && !source.strategyParams.isEmpty()) {
            target.strategyParams.putAll(source.strategyParams);
        }
        target.symbol = symbol;
        target.symbols = symbol;
        target.text = source.text;
        target.beginDate = source.beginDate;
        target.endDate = source.endDate;
        target.initialCapital = source.initialCapital;
        target.feeRatePct = source.feeRatePct;
        target.entryMakerFeeRatePct = source.entryMakerFeeRatePct;
        target.exitTakerFeeRatePct = source.exitTakerFeeRatePct;
        target.fallbackStopLossPct = source.fallbackStopLossPct;
        target.fallbackTakeProfitPct = source.fallbackTakeProfitPct;
        target.maxHoldBars = source.maxHoldBars;
        target.ignoreSentimentGuard = source.ignoreSentimentGuard;
        target.allowMissingStageAnalysis = source.allowMissingStageAnalysis;
        return target;
    }

    public BacktestParam normalizeParam(BacktestParam param) {
        BacktestParam req = param == null ? new BacktestParam() : param;
        if (req.symbol == null || req.symbol.trim().isEmpty()) {
            req.symbol = firstSymbol(req.symbols);
        }
        if (req.symbol == null || req.symbol.trim().isEmpty()) {
            req.symbol = "ETHUSDT";
        }
        if (req.symbols == null || req.symbols.trim().isEmpty()) {
            req.symbols = req.symbol;
        }
        if (req.text == null || req.text.trim().isEmpty()) {
            req.text = defaultSupportedText();
        }
        req.text = supportService.normalizeText(req.text);
        supportService.validateBacktestRange(req.text, req.beginDate, req.endDate);
        if (req.initialCapital == null || req.initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            req.initialCapital = BigDecimal.valueOf(10000);
        }
        if (req.feeRatePct == null || req.feeRatePct.compareTo(BigDecimal.ZERO) < 0) {
            req.feeRatePct = BigDecimal.ZERO;
        }
        if (req.entryMakerFeeRatePct == null || req.entryMakerFeeRatePct.compareTo(BigDecimal.ZERO) < 0) {
            req.entryMakerFeeRatePct = req.feeRatePct.compareTo(BigDecimal.ZERO) > 0
                    ? req.feeRatePct
                    : new BigDecimal("0.02");
        }
        if (req.exitTakerFeeRatePct == null || req.exitTakerFeeRatePct.compareTo(BigDecimal.ZERO) < 0) {
            req.exitTakerFeeRatePct = req.feeRatePct.compareTo(BigDecimal.ZERO) > 0
                    ? req.feeRatePct
                    : new BigDecimal("0.05");
        }
        if (req.maxHoldBars == null || req.maxHoldBars < 0) {
            req.maxHoldBars = 0;
        }
        if (req.ignoreSentimentGuard == null) {
            req.ignoreSentimentGuard = true;
        }
        if (req.allowMissingStageAnalysis == null) {
            req.allowMissingStageAnalysis = true;
        }
        if (req.runtimeType == null || req.runtimeType.trim().isEmpty()) {
            req.runtimeType = "JAR";
        }
        return req;
    }

    static String firstSymbol(String symbols) {
        if (symbols == null || symbols.trim().isEmpty()) {
            return "";
        }
        String[] tokens = symbols.trim().split("[,|;\\s]+");
        return tokens.length == 0 ? "" : tokens[0].trim().toUpperCase(Locale.ENGLISH);
    }

    private String defaultSupportedText() {
        if (klineSupportedTextProvider == null) {
            return "15m";
        }
        return klineSupportedTextProvider.getDefaultText();
    }

    public BacktestResult initResult(String strategyName, BacktestParam param) {
        BacktestResult result = new BacktestResult();
        result.strategyName = strategyName;
        result.strategyVersion = param.strategyVersion;
        result.baselineVersion = param.baselineVersion;
        result.runtimeType = param.runtimeType;
        result.scene = param.scene;
        result.strategyPayload = param.strategyPayload;
        result.symbol = param.symbol;
        result.text = param.text;
        result.beginDate = param.beginDate;
        result.endDate = param.endDate;
        result.initialCapital = scale(param.initialCapital.doubleValue());
        result.finalCapital = scale(param.initialCapital.doubleValue());
        result.feeRatePct = scale(param.entryMakerFeeRatePct.doubleValue() + param.exitTakerFeeRatePct.doubleValue());
        result.entryMakerFeeRatePct = scale(param.entryMakerFeeRatePct.doubleValue());
        result.exitTakerFeeRatePct = scale(param.exitTakerFeeRatePct.doubleValue());
        result.maxHoldBars = param.maxHoldBars;
        result.tradeList = new ArrayList<TradeRecord>();
        result.equityCurve = new ArrayList<BacktestModels.EquityPoint>();
        result.rejectReasonCounts = new java.util.LinkedHashMap<String, Integer>();
        return result;
    }

    public Bar toBar(TTbookOhlc ohlc, Duration duration) throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        ZonedDateTime time = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(sdf.parse(ohlc.starttime).getTime()), ZoneId.systemDefault());
        return new BaseBar(duration, time, ohlc.open, ohlc.high, ohlc.low, ohlc.close, ohlc.volume);
    }

    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Integer nzInt(Integer value) {
        return value == null ? 0 : value;
    }

    private int elapsedMs(long startNs) {
        return (int) Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private int sumTrialCount(List<BacktestResult> results) {
        int total = 0;
        if (results == null) {
            return 0;
        }
        for (BacktestResult result : results) {
            total += nzInt(result == null ? null : result.trialCount);
        }
        return total;
    }

    private List<OptimizationTrial> collectOptimizationTrials(List<BacktestResult> results) {
        List<OptimizationTrial> trials = new ArrayList<OptimizationTrial>();
        if (results == null) {
            return trials;
        }
        for (BacktestResult result : results) {
            if (result == null || result.optimizationTrials == null || result.optimizationTrials.isEmpty()) {
                continue;
            }
            trials.addAll(result.optimizationTrials);
            result.trialCount = result.optimizationTrials.size();
        }
        return trials;
    }

    private int aggregateFragileBest(List<BacktestResult> results) {
        if (results == null) {
            return 0;
        }
        for (BacktestResult result : results) {
            if (result != null && Integer.valueOf(1).equals(result.fragileBest)) {
                return 1;
            }
        }
        return 0;
    }

    private int sumTradeCount(List<BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (BacktestResult result : results) {
            if (result != null && result.tradeCount != null) {
                total += result.tradeCount.intValue();
            }
        }
        return total;
    }

    private int countTrialsByPhase(List<OptimizationTrial> trials, String phase) {
        if (trials == null || trials.isEmpty() || phase == null) {
            return 0;
        }
        int count = 0;
        for (OptimizationTrial trial : trials) {
            if (trial != null && phase.equalsIgnoreCase(safeString(trial.phase))) {
                count++;
            }
        }
        return count;
    }

    private int minRank(List<OptimizationTrial> trials) {
        if (trials == null || trials.isEmpty()) {
            return 0;
        }
        int best = Integer.MAX_VALUE;
        for (OptimizationTrial trial : trials) {
            if (trial == null || trial.rank == null || trial.rank.intValue() <= 0) {
                continue;
            }
            best = Math.min(best, trial.rank.intValue());
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    private String aggregateStableParamRange(List<BacktestResult> results) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (results != null) {
            for (BacktestResult result : results) {
                if (result == null) {
                    continue;
                }
                payload.put(safeString(result.symbol), safeString(result.stableParamRangeJson));
            }
        }
        return JsonUtils.Serializer(payload);
    }

    private String aggregateBestParamSets(List<BacktestResult> results, Map<String, Object> trialParams) {
        if (results == null || results.isEmpty()) {
            return JsonUtils.Serializer(trialParams == null
                    ? Collections.<String, Object>emptyMap()
                    : new LinkedHashMap<String, Object>(trialParams));
        }
        if (results.size() == 1) {
            String single = results.get(0) == null ? "" : results.get(0).bestParamSetJson;
            return single == null || single.trim().isEmpty() ? "{}" : single;
        }
        String first = null;
        boolean allSame = true;
        for (BacktestResult result : results) {
            if (result == null) {
                continue;
            }
            String current = safeString(result.bestParamSetJson);
            if (first == null) {
                first = current;
            } else if (!first.equals(current)) {
                allSame = false;
                break;
            }
        }
        if (allSame && first != null && !first.trim().isEmpty()) {
            return first;
        }
        return "{}";
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private static class TrialExecution {
        private OptimizationTrial trial;
        private Map<String, Object> paramSet;
    }

    private static class WindowConfig {
        private int fitWindowDays;
        private int validateWindowDays;
        private int forwardWindowDays;
        private int minSliceCount;
    }

    private static class GateDecision {
        private boolean overfitPass;
        private boolean oosPass;
        private String reason;
    }

    private String heapSummary() {
        Runtime runtime = Runtime.getRuntime();
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        long totalMb = runtime.totalMemory() / (1024L * 1024L);
        long freeMb = runtime.freeMemory() / (1024L * 1024L);
        long usedMb = totalMb - freeMb;
        return "usedMb=" + usedMb + ", freeMb=" + freeMb + ", totalMb=" + totalMb + ", maxMb=" + maxMb;
    }

}
