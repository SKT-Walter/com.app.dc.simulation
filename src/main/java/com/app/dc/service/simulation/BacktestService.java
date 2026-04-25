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
import com.app.dc.service.simulation.runtime.WalkForwardBacktestRunner;
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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

@Service
@Slf4j
public class BacktestService {

    @Autowired
    private BacktestQueryService queryService;

    @Autowired
    private BacktestSupportService supportService;

    @Autowired
    private StrategyBacktestTaskDao strategyBacktestTaskDao;

    @Autowired
    private WalkForwardBacktestRunner walkForwardBacktestRunner;

    @Autowired
    private BacktestOptimizationService backtestOptimizationService;

    @Autowired(required = false)
    @Qualifier("strategyBacktestSymbolExecutor")
    private ThreadPoolTaskExecutor strategyBacktestSymbolExecutor;

    @Value("${strategy.backtest.maxTrialsPerTask:160}")
    private int maxTrialsPerTask;

    public BacktestResponse run(BacktestParam param) throws Exception {
        return run(param, 120, 30, 14);
    }

    public BacktestResponse run(BacktestParam param,
                                int fitWindowDays,
                                int validateWindowDays,
                                int forwardWindowDays) throws Exception {
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
        List<TrialExecution> executions = new ArrayList<TrialExecution>();
        int trialNo = 1;
        Map<String, List<TTbookOhlc>> ohlcCache = new ConcurrentHashMap<String, List<TTbookOhlc>>();
        int trialBudget = Math.max(1, maxTrialsPerTask);

        List<Map<String, Object>> coarseParamSets = plan.optimizationSupported
                ? backtestOptimizationService.buildCoarseParamSets(plan)
                : backtestOptimizationService.buildDefaultOnly(plan);
        int coarseCandidateCount = coarseParamSets == null ? 0 : coarseParamSets.size();
        coarseParamSets = limitTrialSets(coarseParamSets, trialBudget, "COARSE", candidate);
        executions.addAll(executeTrials("COARSE", trialNo, candidate, req, symbols, coarseParamSets,
                windowConfig, plan, ohlcCache));
        trialNo += coarseParamSets.size();

        List<OptimizationTrial> rankedTrials = collectTrials(executions);
        backtestOptimizationService.rankTrials(plan, rankedTrials);
        int fineCandidateCount = 0;
        if (plan.optimizationSupported) {
            List<Map<String, Object>> fineParamSets = backtestOptimizationService.buildFineParamSets(plan, rankedTrials);
            fineCandidateCount = fineParamSets == null ? 0 : fineParamSets.size();
            fineParamSets = limitTrialSets(fineParamSets, Math.max(0, trialBudget - executions.size()), "FINE", candidate);
            executions.addAll(executeTrials("FINE", trialNo, candidate, req, symbols, fineParamSets,
                    windowConfig, plan, ohlcCache));
            rankedTrials = collectTrials(executions);
            backtestOptimizationService.rankTrials(plan, rankedTrials);
        }

        TrialExecution best = bestTrial(executions);
        if (best == null) {
            throw new IllegalStateException("no backtest trial result produced");
        }
        log.info("BacktestService rerun best trial for full payload retention, strategy:{}@{}, bestRank:{}, trialNo:{}, phase:{}, heap:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                best.trial == null ? 0 : best.trial.rank,
                best.trial == null ? 0 : best.trial.trialNo,
                best.trial == null ? "" : best.trial.phase,
                heapSummary());
        BacktestResponse response = runSingle(candidate, req, symbols, best.paramSet,
                windowConfig, plan, ohlcCache);
        response.optimizationMode = plan.optimizationMode;
        response.optimizationObjective = plan.objective;
        response.minForwardContribution = plan.minForwardContribution;
        response.trialCount = executions.size();
        response.trialBudget = trialBudget;
        response.trialBudgetUsed = executions.size();
        response.trialBudgetHit = executions.size() >= trialBudget ? 1 : 0;
        response.coarseCandidateCount = coarseCandidateCount;
        response.fineCandidateCount = fineCandidateCount;
        response.bestRank = best.trial.rank == null ? 0 : best.trial.rank;
        response.bestParamSetJson = best.trial.paramSetJson == null ? "{}" : best.trial.paramSetJson;
        response.elapsedMs = nzInt(response.elapsedMs);
        response.symbolCount = nzInt(response.symbolCount);
        response.fitWindowDays = windowConfig.fitWindowDays;
        response.validateWindowDays = windowConfig.validateWindowDays;
        response.forwardWindowDays = windowConfig.forwardWindowDays;
        response.minSliceCount = windowConfig.minSliceCount;
        response.fragileBest = best.trial.fragileBest;
        response.stableParamRangeJson = best.trial.stableParamRangeJson == null ? "{}" : best.trial.stableParamRangeJson;
        response.neighborAvgPnl = nz(best.trial.neighborAvgPnl);
        response.neighborWorstPnl = nz(best.trial.neighborWorstPnl);
        response.trials = rankedTrials;
        if (response.results != null) {
            for (BacktestResult result : response.results) {
                if (result == null) {
                    continue;
                }
                result.optimizationMode = response.optimizationMode;
                result.optimizationObjective = response.optimizationObjective;
                result.minForwardContribution = response.minForwardContribution;
                result.trialCount = response.trialCount;
                result.trialBudget = response.trialBudget;
                result.trialBudgetUsed = response.trialBudgetUsed;
                result.trialBudgetHit = response.trialBudgetHit;
                result.coarseCandidateCount = response.coarseCandidateCount;
                result.fineCandidateCount = response.fineCandidateCount;
                result.bestRank = response.bestRank;
                result.bestParamSetJson = response.bestParamSetJson;
                result.fitWindowDays = response.fitWindowDays;
                result.validateWindowDays = response.validateWindowDays;
                result.forwardWindowDays = response.forwardWindowDays;
                result.minSliceCount = response.minSliceCount;
                result.fragileBest = response.fragileBest;
                result.stableParamRangeJson = response.stableParamRangeJson;
                result.neighborAvgPnl = response.neighborAvgPnl;
                result.neighborWorstPnl = response.neighborWorstPnl;
            }
        }
        log.info("BacktestService run end, strategy:{}@{}, bestRank:{}, trialCount:{}, totalPnl:{}, validatePnl:{}, forwardPnl:{}, sliceCount:{}, elapsedMs:{}, fragileBest:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                response.bestRank,
                response.trialCount,
                response.totalPnl,
                response.validatePnl,
                response.forwardPnl,
                response.sliceCount,
                response.elapsedMs,
                response.fragileBest);
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
                                               Map<String, List<TTbookOhlc>> ohlcCache) throws Exception {
        if (paramSets == null || paramSets.isEmpty()) {
            return Collections.emptyList();
        }
        log.info("BacktestService phase start, strategy:{}@{}, phase:{}, startTrialNo:{}, paramSetCount:{}, symbols:{}",
                candidate.strategyName, candidate.strategyVersion, phase, startTrialNo, paramSets.size(), symbols);
        List<TrialExecution> executions = new ArrayList<TrialExecution>();
        int trialNo = startTrialNo;
        for (Map<String, Object> paramSet : paramSets) {
            long trialStartNs = System.nanoTime();
            log.info("BacktestService trial start, strategy:{}@{}, phase:{}, trialNo:{}, params:{}",
                    candidate.strategyName, candidate.strategyVersion, phase, trialNo, JsonUtils.Serializer(paramSet));
            BacktestResponse response = runSingle(candidate, baseParam, symbols, paramSet,
                    windowConfig, plan, ohlcCache);
            OptimizationTrial trial = backtestOptimizationService.buildTrial(trialNo, phase,
                    candidate.strategyName, candidate.strategyVersion,
                    response.symbol, response.text, paramSet, response);
            TrialExecution execution = new TrialExecution();
            execution.trial = trial;
            execution.paramSet = paramSet == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(paramSet);
            executions.add(execution);
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
                                       Map<String, List<TTbookOhlc>> ohlcCache) throws Exception {
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
        response.windowMode = "WALK_FORWARD";
        response.fitWindowDays = windowConfig.fitWindowDays;
        response.validateWindowDays = windowConfig.validateWindowDays;
        response.forwardWindowDays = windowConfig.forwardWindowDays;
        response.minSliceCount = windowConfig.minSliceCount;
        response.optimizationMode = plan == null ? "" : plan.optimizationMode;
        response.optimizationObjective = plan == null ? "" : plan.objective;
        response.minForwardContribution = plan == null ? BigDecimal.ZERO : plan.minForwardContribution;
        log.info("BacktestService runSingle start, strategy:{}@{}, symbols:{}, trialParams:{}",
                candidate.strategyName, candidate.strategyVersion, symbols, JsonUtils.Serializer(trialParams));

        List<BacktestResult> results = new ArrayList<BacktestResult>();
        BigDecimal fitPnl = BigDecimal.ZERO;
        BigDecimal validatePnl = BigDecimal.ZERO;
        BigDecimal forwardPnl = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        int sliceCount = Integer.MAX_VALUE;
        boolean overfitPass = true;
        String overfitReason = "";
        List<BacktestResult> symbolResults = executeSymbols(candidate, req, symbols, trialParams, windowConfig, ohlcCache);
        symbolResults.sort(new Comparator<BacktestResult>() {
            @Override
            public int compare(BacktestResult left, BacktestResult right) {
                return safeString(left == null ? null : left.symbol).compareTo(safeString(right == null ? null : right.symbol));
            }
        });
        for (BacktestResult result : symbolResults) {
            if (result == null) {
                continue;
            }
            results.add(result);
            fitPnl = fitPnl.add(nz(result.fitPnl));
            validatePnl = validatePnl.add(nz(result.validatePnl));
            forwardPnl = forwardPnl.add(nz(result.forwardPnl));
            totalPnl = totalPnl.add(nz(result.totalPnl));
            sliceCount = Math.min(sliceCount, result.sliceCount == null ? 0 : result.sliceCount.intValue());
            if (!Integer.valueOf(1).equals(result.overfitPass)) {
                overfitPass = false;
                if (overfitReason.isEmpty() && result.overfitReason != null) {
                    overfitReason = result.overfitReason;
                }
            }
        }
        response.results = results.isEmpty() ? Collections.<BacktestResult>emptyList() : results;
        response.fitPnl = scale(fitPnl.doubleValue());
        response.validatePnl = scale(validatePnl.doubleValue());
        response.forwardPnl = scale(forwardPnl.doubleValue());
        response.totalPnl = scale(totalPnl.doubleValue());
        response.sliceCount = results.isEmpty() ? 0 : sliceCount;
        response.overfitPass = results.isEmpty() ? 0 : (overfitPass ? 1 : 0);
        response.overfitReason = overfitReason;
        response.elapsedMs = elapsedMs(startNs);
        response.bestParamSetJson = JsonUtils.Serializer(trialParams == null
                ? Collections.<String, Object>emptyMap()
                : new LinkedHashMap<String, Object>(trialParams));
        log.info("BacktestService runSingle end, strategy:{}@{}, totalPnl:{}, validatePnl:{}, forwardPnl:{}, sliceCount:{}, elapsedMs:{}",
                candidate.strategyName, candidate.strategyVersion,
                response.totalPnl, response.validatePnl, response.forwardPnl, response.sliceCount, response.elapsedMs);
        return response;
    }

    private List<BacktestResult> executeSymbols(final StrategyCandidateRow candidate,
                                                final BacktestParam req,
                                                List<String> symbols,
                                                final Map<String, Object> trialParams,
                                                final WindowConfig windowConfig,
                                                final Map<String, List<TTbookOhlc>> ohlcCache) throws Exception {
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
            return executeSymbolsSerial(candidate, req, symbols, trialParams, windowConfig, ohlcCache);
        }
        log.info("BacktestService executeSymbols parallel, strategy:{}@{}, symbolCount:{}, executorActive:{}, executorPool:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                symbols.size(),
                strategyBacktestSymbolExecutor.getActiveCount(),
                strategyBacktestSymbolExecutor.getPoolSize());
        List<Future<BacktestResult>> futures = new ArrayList<Future<BacktestResult>>();
        for (final String symbol : symbols) {
            futures.add(strategyBacktestSymbolExecutor.submit(new Callable<BacktestResult>() {
                @Override
                public BacktestResult call() throws Exception {
                    return runSingleSymbol(candidate, req, symbol, trialParams, windowConfig, ohlcCache);
                }
            }));
        }
        List<BacktestResult> results = new ArrayList<BacktestResult>();
        for (Future<BacktestResult> future : futures) {
            results.add(future.get());
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
                                                      Map<String, List<TTbookOhlc>> ohlcCache) throws Exception {
        List<BacktestResult> results = new ArrayList<BacktestResult>();
        for (String symbol : symbols) {
            results.add(runSingleSymbol(candidate, req, symbol, trialParams, windowConfig, ohlcCache));
        }
        return results;
    }

    private BacktestResult runSingleSymbol(StrategyCandidateRow candidate,
                                           BacktestParam req,
                                           String symbol,
                                           Map<String, Object> trialParams,
                                           WindowConfig windowConfig,
                                           Map<String, List<TTbookOhlc>> ohlcCache) throws Exception {
        List<TTbookOhlc> ohlcList = loadOhlc(req, symbol, ohlcCache);
        BacktestParam symbolParam = copyParamForSymbol(req, symbol);
        applyTrialParamOverrides(symbolParam, trialParams);
        BacktestResult result = walkForwardBacktestRunner.run(candidate, symbolParam, ohlcList,
                windowConfig.fitWindowDays, windowConfig.validateWindowDays,
                windowConfig.forwardWindowDays, windowConfig.minSliceCount);
        result.symbolCount = 1;
        result.fitWindowDays = windowConfig.fitWindowDays;
        result.validateWindowDays = windowConfig.validateWindowDays;
        result.forwardWindowDays = windowConfig.forwardWindowDays;
        result.minSliceCount = windowConfig.minSliceCount;
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
        target.maxHoldBars = source.maxHoldBars;
        target.ignoreSentimentGuard = source.ignoreSentimentGuard;
        target.allowMissingStageAnalysis = source.allowMissingStageAnalysis;
        return target;
    }

    public BacktestParam normalizeParam(BacktestParam param) {
        BacktestParam req = param == null ? new BacktestParam() : param;
        if (req.symbol == null || req.symbol.trim().isEmpty()) {
            req.symbol = "ETHUSDT";
        }
        if (req.symbols == null || req.symbols.trim().isEmpty()) {
            req.symbols = req.symbol;
        }
        if (req.text == null || req.text.trim().isEmpty()) {
            req.text = "15m";
        }
        req.text = supportService.normalizeText(req.text);
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

    private String heapSummary() {
        Runtime runtime = Runtime.getRuntime();
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        long totalMb = runtime.totalMemory() / (1024L * 1024L);
        long freeMb = runtime.freeMemory() / (1024L * 1024L);
        long usedMb = totalMb - freeMb;
        return "usedMb=" + usedMb + ", freeMb=" + freeMb + ", totalMb=" + totalMb + ", maxMb=" + maxMb;
    }

}
