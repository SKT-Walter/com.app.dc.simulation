package com.app.dc.service.simulation;

import com.app.dc.service.simulation.BacktestModels.OptimizationTrial;
import com.app.dc.signal.StrategyParametersJson;
import com.app.dc.signal.StrategyParametersSupport;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Service
@Slf4j
public class BacktestOptimizationService {

    private static final int DEFAULT_TOP_N = 10;
    private static final int DEFAULT_MAX_FULL_GRID = 256;
    private static final int DEFAULT_MAX_COARSE_CANDIDATES = 256;
    private static final int DEFAULT_MAX_FINE_CANDIDATES = 384;
    private static final int DEFAULT_FIT_WINDOW_DAYS = 120;
    private static final int DEFAULT_VALIDATE_WINDOW_DAYS = 30;
    private static final int DEFAULT_FORWARD_WINDOW_DAYS = 14;
    private static final int DEFAULT_MIN_SLICE_COUNT = 3;
    private static final long DEFAULT_RANDOM_SEED = 20260418L;
    private static final BigDecimal DEFAULT_MIN_FORWARD_CONTRIBUTION = new BigDecimal("0.20");
    private static final String MODE_LAYERED_GRID = "LAYERED_GRID";
    private static final String MODE_RANDOM_LOCAL = "RANDOM_LOCAL";
    private static final String OBJECTIVE_PROFIT_FIRST = "PROFIT_FIRST";

    public OptimizationPlan buildPlan(String parametersJson) {
        StrategyParametersJson parsed = StrategyParametersSupport.parse(parametersJson);
        OptimizationPlan plan = new OptimizationPlan();
        plan.optimizationSupported = StrategyParametersSupport.isOptimizationSupported(parametersJson);
        plan.optimizationMode = normalizeMode(string(parsed.optimizationProfile.get("mode"), MODE_LAYERED_GRID));
        plan.objective = normalizeObjective(string(parsed.optimizationProfile.get("objective"), OBJECTIVE_PROFIT_FIRST));
        plan.topN = Math.max(1, intValue(parsed.optimizationProfile.get("topN"), DEFAULT_TOP_N));
        plan.maxFullGrid = Math.max(1, intValue(parsed.optimizationProfile.get("maxFullGrid"), DEFAULT_MAX_FULL_GRID));
        plan.maxCoarseCandidates = Math.max(1,
                intValue(parsed.optimizationProfile.get("maxCoarseCandidates"), DEFAULT_MAX_COARSE_CANDIDATES));
        plan.maxFineCandidates = Math.max(1,
                intValue(parsed.optimizationProfile.get("maxFineCandidates"), DEFAULT_MAX_FINE_CANDIDATES));
        plan.fitWindowDays = Math.max(1,
                intValue(parsed.optimizationProfile.get("fitWindowDays"), DEFAULT_FIT_WINDOW_DAYS));
        plan.validateWindowDays = Math.max(1,
                intValue(parsed.optimizationProfile.get("validateWindowDays"), DEFAULT_VALIDATE_WINDOW_DAYS));
        plan.forwardWindowDays = Math.max(1,
                intValue(parsed.optimizationProfile.get("forwardWindowDays"), DEFAULT_FORWARD_WINDOW_DAYS));
        plan.minSliceCount = Math.max(1,
                intValue(parsed.optimizationProfile.get("minSliceCount"), DEFAULT_MIN_SLICE_COUNT));
        plan.randomSeed = longValue(parsed.optimizationProfile.get("randomSeed"), DEFAULT_RANDOM_SEED);
        plan.minForwardContribution = decimalValue(
                parsed.optimizationProfile.get("minForwardContribution"),
                DEFAULT_MIN_FORWARD_CONTRIBUTION);
        plan.lockedParams.addAll(stringSet(parsed.optimizationProfile.get("lockedParams")));
        plan.priorityParams.addAll(stringSet(parsed.optimizationProfile.get("priorityParams")));
        plan.coarseOnlyParams.addAll(stringSet(parsed.optimizationProfile.get("coarseOnlyParams")));
        plan.fineOnlyParams.addAll(stringSet(parsed.optimizationProfile.get("fineOnlyParams")));
        plan.defaultParams.putAll(StrategyParametersSupport.extractDefaultParams(parametersJson));
        plan.dimensions.addAll(parseDimensions(parsed.parameterSchema, plan.defaultParams, plan));
        if (plan.dimensions.isEmpty()) {
            plan.optimizationSupported = false;
        }
        log.info("BacktestOptimizationService buildPlan, optimizationSupported:{}, mode:{}, objective:{}, dimensions:{}, topN:{}, maxCoarseCandidates:{}, maxFineCandidates:{}, window:{}/{}/{}, minSliceCount:{}, minForwardContribution:{}",
                plan.optimizationSupported,
                plan.optimizationMode,
                plan.objective,
                plan.dimensions.size(),
                plan.topN,
                plan.maxCoarseCandidates,
                plan.maxFineCandidates,
                plan.fitWindowDays,
                plan.validateWindowDays,
                plan.forwardWindowDays,
                plan.minSliceCount,
                plan.minForwardContribution);
        return plan;
    }

    public List<Map<String, Object>> buildDefaultOnly(OptimizationPlan plan) {
        if (plan == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(copy(plan.defaultParams));
        return result;
    }

    public List<Map<String, Object>> buildCoarseParamSets(OptimizationPlan plan) {
        if (plan == null || !plan.optimizationSupported || activeDimensions(plan, true).isEmpty()) {
            return buildDefaultOnly(plan);
        }
        List<ParameterDimension> coarseDimensions = activeDimensions(plan, true);
        if (MODE_RANDOM_LOCAL.equalsIgnoreCase(plan.optimizationMode)) {
            List<Map<String, Object>> result = uniqueParamSets(randomSample(coarseDimensions, plan.defaultParams, plan.maxCoarseCandidates, plan.randomSeed),
                    plan.defaultParams);
            log.info("BacktestOptimizationService buildCoarseParamSets, mode:{}, dimensions:{}, paramSetCount:{}, randomSeed:{}",
                    plan.optimizationMode, coarseDimensions.size(), result.size(), plan.randomSeed);
            return result;
        }
        if (estimateGridSize(coarseDimensions, false) <= plan.maxFullGrid) {
            List<Map<String, Object>> result = uniqueParamSets(cartesian(coarseDimensions, false, plan.maxFullGrid), plan.defaultParams);
            log.info("BacktestOptimizationService buildCoarseParamSets, mode:{}, dimensions:{}, paramSetCount:{}, fullGrid:{}",
                    plan.optimizationMode, coarseDimensions.size(), result.size(), true);
            return result;
        }
        List<Map<String, Object>> result = uniqueParamSets(cartesian(coarseDimensions, true, plan.maxCoarseCandidates), plan.defaultParams);
        log.info("BacktestOptimizationService buildCoarseParamSets, mode:{}, dimensions:{}, paramSetCount:{}, sampledGrid:{}",
                plan.optimizationMode, coarseDimensions.size(), result.size(), true);
        return result;
    }

    public List<Map<String, Object>> buildFineParamSets(OptimizationPlan plan, List<OptimizationTrial> rankedTrials) {
        if (plan == null || !plan.optimizationSupported || activeDimensions(plan, false).isEmpty()) {
            return Collections.emptyList();
        }
        List<OptimizationTrial> topTrials = topRanked(rankedTrials, plan.topN);
        if (topTrials.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<String>();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        List<ParameterDimension> fineDimensions = activeDimensions(plan, false);
        for (OptimizationTrial trial : topTrials) {
            Map<String, Object> paramSet = parseParamSet(trial.paramSetJson);
            List<Map<String, Object>> candidates = cartesian(
                    buildNeighborDimensions(fineDimensions, paramSet),
                    false,
                    plan.maxFineCandidates);
            for (Map<String, Object> candidate : uniqueParamSets(candidates, plan.defaultParams)) {
                String key = JsonUtils.Serializer(candidate);
                if (seen.add(key)) {
                    result.add(candidate);
                    if (result.size() >= plan.maxFineCandidates) {
                        return result;
                    }
                }
            }
        }
        log.info("BacktestOptimizationService buildFineParamSets, topTrials:{}, dimensions:{}, paramSetCount:{}",
                topTrials.size(), fineDimensions.size(), result.size());
        return result;
    }

    public void rankTrials(OptimizationPlan plan, List<OptimizationTrial> trials) {
        if (trials == null || trials.isEmpty()) {
            return;
        }
        final OptimizationPlan effectivePlan = plan == null ? new OptimizationPlan() : plan;
        Collections.sort(trials, new Comparator<OptimizationTrial>() {
            @Override
            public int compare(OptimizationTrial left, OptimizationTrial right) {
                int gate = Integer.compare(passProfitGate(right, effectivePlan), passProfitGate(left, effectivePlan));
                if (gate != 0) {
                    return gate;
                }
                int overfit = Integer.compare(intValue(right.overfitPass), intValue(left.overfitPass));
                if (overfit != 0) {
                    return overfit;
                }
                int validate = compareGatePass(right.validatePnl, left.validatePnl);
                if (validate != 0) {
                    return validate;
                }
                int forward = compareGatePass(right.forwardPnl, left.forwardPnl);
                if (forward != 0) {
                    return forward;
                }
                int totalGate = compareGatePass(right.totalPnl, left.totalPnl);
                if (totalGate != 0) {
                    return totalGate;
                }
                int total = nz(right.totalPnl).compareTo(nz(left.totalPnl));
                if (total != 0) {
                    return total;
                }
                int forwardPnl = nz(right.forwardPnl).compareTo(nz(left.forwardPnl));
                if (forwardPnl != 0) {
                    return forwardPnl;
                }
                int score = nz(right.forwardScore).compareTo(nz(left.forwardScore));
                if (score != 0) {
                    return score;
                }
                return nz(left.maxDrawdownPct).compareTo(nz(right.maxDrawdownPct));
            }
        });
        for (int i = 0; i < trials.size(); i++) {
            trials.get(i).rank = Integer.valueOf(i + 1);
        }
        applyStabilityAnalysis(effectivePlan, trials);
        if (!trials.isEmpty()) {
            OptimizationTrial best = trials.get(0);
            log.info("BacktestOptimizationService rankTrials, trialCount:{}, bestRank:{}, bestTotalPnl:{}, bestValidatePnl:{}, bestForwardPnl:{}, bestForwardScore:{}, fragileBest:{}",
                    trials.size(),
                    best.rank,
                    best.totalPnl,
                    best.validatePnl,
                    best.forwardPnl,
                    best.forwardScore,
                    best.fragileBest);
        }
    }

    public OptimizationTrial buildTrial(int trialNo,
                                        String phase,
                                        String strategyName,
                                        String strategyVersion,
                                        String symbolScope,
                                        String textScope,
                                        Map<String, Object> paramSet,
                                        BacktestModels.BacktestResponse response) {
        OptimizationTrial trial = new OptimizationTrial();
        trial.trialNo = Integer.valueOf(trialNo);
        trial.phase = phase == null ? "" : phase;
        trial.strategyName = strategyName;
        trial.strategyVersion = strategyVersion;
        trial.symbolScope = symbolScope;
        trial.textScope = textScope;
        trial.paramSetJson = JsonUtils.Serializer(copy(paramSet));
        trial.fitPnl = nz(response == null ? null : response.fitPnl);
        trial.validatePnl = nz(response == null ? null : response.validatePnl);
        trial.forwardPnl = nz(response == null ? null : response.forwardPnl);
        trial.totalPnl = nz(response == null ? null : response.totalPnl);
        trial.forwardScore = nz(response == null ? null : response.results == null || response.results.isEmpty()
                ? BigDecimal.ZERO
                : averageForwardScore(response.results));
        trial.maxDrawdownPct = maxDrawdown(response == null ? null : response.results);
        trial.overfitPass = response == null ? 0 : intValue(response.overfitPass);
        trial.overfitReason = response == null ? "" : string(response.overfitReason, "");
        trial.elapsedMs = response == null ? 0 : intValue(response.elapsedMs);
        trial.symbolCount = response == null ? 0 : intValue(response.symbolCount);
        trial.sliceCount = response == null ? 0 : intValue(response.sliceCount);
        trial.fitWindowDays = response == null ? 0 : intValue(response.fitWindowDays);
        trial.validateWindowDays = response == null ? 0 : intValue(response.validateWindowDays);
        trial.forwardWindowDays = response == null ? 0 : intValue(response.forwardWindowDays);
        trial.minSliceCount = response == null ? 0 : intValue(response.minSliceCount);
        trial.optimizationObjective = response == null ? "" : string(response.optimizationObjective, "");
        trial.minForwardContribution = response == null ? BigDecimal.ZERO : nz(response.minForwardContribution);
        trial.fragileBest = 0;
        trial.stableParamRangeJson = "{}";
        trial.neighborAvgPnl = BigDecimal.ZERO;
        trial.neighborWorstPnl = BigDecimal.ZERO;
        return trial;
    }

    private void applyStabilityAnalysis(OptimizationPlan plan, List<OptimizationTrial> rankedTrials) {
        if (plan == null || rankedTrials == null || rankedTrials.isEmpty()) {
            return;
        }
        OptimizationTrial best = rankedTrials.get(0);
        Map<String, Object> bestParamSet = parseParamSet(best.paramSetJson);
        List<OptimizationTrial> neighbors = collectNeighbors(best, bestParamSet, plan, rankedTrials);
        if (neighbors.isEmpty()) {
            best.fragileBest = 1;
            best.stableParamRangeJson = "{}";
            best.neighborAvgPnl = BigDecimal.ZERO;
            best.neighborWorstPnl = BigDecimal.ZERO;
            return;
        }
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal worst = null;
        for (OptimizationTrial neighbor : neighbors) {
            BigDecimal pnl = nz(neighbor.totalPnl);
            sum = sum.add(pnl);
            worst = worst == null ? pnl : worst.min(pnl);
        }
        BigDecimal avg = neighbors.isEmpty()
                ? BigDecimal.ZERO
                : sum.divide(BigDecimal.valueOf(neighbors.size()), 6, RoundingMode.HALF_UP);
        best.neighborAvgPnl = avg;
        best.neighborWorstPnl = worst == null ? BigDecimal.ZERO : worst;
        best.stableParamRangeJson = buildStableParamRangeJson(neighbors, plan);
        best.fragileBest = isFragile(best, neighbors, avg, worst) ? 1 : 0;
    }

    private List<OptimizationTrial> collectNeighbors(OptimizationTrial best,
                                                     Map<String, Object> bestParamSet,
                                                     OptimizationPlan plan,
                                                     List<OptimizationTrial> rankedTrials) {
        List<OptimizationTrial> result = new ArrayList<OptimizationTrial>();
        if (best == null || rankedTrials == null || rankedTrials.isEmpty()) {
            return result;
        }
        List<ParameterDimension> fineDimensions = activeDimensions(plan, false);
        for (OptimizationTrial trial : rankedTrials) {
            if (trial == null || trial.rank == null || trial.rank.intValue() > plan.topN) {
                continue;
            }
            if (passProfitGate(trial, plan) <= 0) {
                continue;
            }
            if (isNeighborTrial(trial, bestParamSet, fineDimensions)) {
                result.add(trial);
            }
        }
        return result;
    }

    private boolean isNeighborTrial(OptimizationTrial trial,
                                    Map<String, Object> bestParamSet,
                                    List<ParameterDimension> dimensions) {
        Map<String, Object> current = parseParamSet(trial == null ? null : trial.paramSetJson);
        if (dimensions == null || dimensions.isEmpty()) {
            return true;
        }
        for (ParameterDimension dimension : dimensions) {
            int bestIndex = indexOf(dimension.candidates, bestParamSet.get(dimension.name));
            int currentIndex = indexOf(dimension.candidates, current.get(dimension.name));
            if (bestIndex < 0 || currentIndex < 0) {
                continue;
            }
            if (Math.abs(bestIndex - currentIndex) > 1) {
                return false;
            }
        }
        return true;
    }

    private String buildStableParamRangeJson(List<OptimizationTrial> neighbors, OptimizationPlan plan) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (neighbors == null || neighbors.isEmpty()) {
            return "{}";
        }
        List<ParameterDimension> dimensions = activeDimensions(plan, false);
        for (ParameterDimension dimension : dimensions) {
            List<Object> values = new ArrayList<Object>();
            for (OptimizationTrial neighbor : neighbors) {
                Map<String, Object> paramSet = parseParamSet(neighbor.paramSetJson);
                if (paramSet.containsKey(dimension.name)) {
                    Object value = normalizeValue(dimension.type, paramSet.get(dimension.name));
                    if (!containsValue(values, value)) {
                        values.add(value);
                    }
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            if (isNumericType(dimension.type)) {
                BigDecimal min = null;
                BigDecimal max = null;
                for (Object value : values) {
                    BigDecimal current = decimalValue(value, BigDecimal.ZERO);
                    min = min == null ? current : min.min(current);
                    max = max == null ? current : max.max(current);
                }
                Map<String, Object> range = new LinkedHashMap<String, Object>();
                range.put("min", min == null ? 0 : min);
                range.put("max", max == null ? 0 : max);
                range.put("values", values);
                result.put(dimension.name, range);
            } else {
                result.put(dimension.name, values);
            }
        }
        return JsonUtils.Serializer(result);
    }

    private boolean isFragile(OptimizationTrial best,
                              List<OptimizationTrial> neighbors,
                              BigDecimal avg,
                              BigDecimal worst) {
        if (best == null) {
            return true;
        }
        if (neighbors == null || neighbors.size() <= 1) {
            return true;
        }
        BigDecimal bestPnl = nz(best.totalPnl);
        if (bestPnl.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        if (nz(worst).compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        BigDecimal threshold = bestPnl.multiply(new BigDecimal("0.50"));
        return nz(avg).compareTo(threshold) < 0;
    }

    private BigDecimal averageForwardScore(List<BacktestModels.BacktestResult> results) {
        if (results == null || results.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (BacktestModels.BacktestResult result : results) {
            if (result == null) {
                continue;
            }
            sum = sum.add(nz(result.forwardScore));
            count++;
        }
        return count <= 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(count), 6, BigDecimal.ROUND_HALF_UP);
    }

    private BigDecimal maxDrawdown(List<BacktestModels.BacktestResult> results) {
        BigDecimal max = BigDecimal.ZERO;
        if (results == null) {
            return max;
        }
        for (BacktestModels.BacktestResult result : results) {
            max = max.max(nz(result == null ? null : result.maxDrawdownPct));
        }
        return max;
    }

    private int compareGatePass(BigDecimal left, BigDecimal right) {
        boolean leftPositive = nz(left).compareTo(BigDecimal.ZERO) > 0;
        boolean rightPositive = nz(right).compareTo(BigDecimal.ZERO) > 0;
        if (leftPositive != rightPositive) {
            return leftPositive ? 1 : -1;
        }
        return 0;
    }

    private int passProfitGate(OptimizationTrial trial, OptimizationPlan plan) {
        if (trial == null) {
            return 0;
        }
        if (intValue(trial.overfitPass) <= 0) {
            return 0;
        }
        if (nz(trial.validatePnl).compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        if (nz(trial.forwardPnl).compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        if (nz(trial.totalPnl).compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        BigDecimal contribution = forwardContribution(trial.forwardPnl, trial.totalPnl);
        return contribution.compareTo(nz(plan == null ? null : plan.minForwardContribution)) >= 0 ? 1 : 0;
    }

    public BigDecimal forwardContribution(BigDecimal forwardPnl, BigDecimal totalPnl) {
        if (totalPnl == null || totalPnl.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return nz(forwardPnl).divide(totalPnl, 6, RoundingMode.HALF_UP);
    }

    private List<OptimizationTrial> topRanked(List<OptimizationTrial> trials, int topN) {
        if (trials == null || trials.isEmpty()) {
            return Collections.emptyList();
        }
        List<OptimizationTrial> sorted = new ArrayList<OptimizationTrial>(trials);
        Collections.sort(sorted, new Comparator<OptimizationTrial>() {
            @Override
            public int compare(OptimizationTrial left, OptimizationTrial right) {
                return Integer.compare(intValue(left.rank), intValue(right.rank));
            }
        });
        return sorted.subList(0, Math.min(Math.max(1, topN), sorted.size()));
    }

    private List<ParameterDimension> parseDimensions(Map<String, Object> parameterSchema,
                                                     Map<String, Object> defaultParams,
                                                     OptimizationPlan plan) {
        List<ParameterDimension> result = new ArrayList<ParameterDimension>();
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            return result;
        }
        Object parameters = parameterSchema.get("parameters");
        if (!(parameters instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) parameters) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item;
            String name = string(row.get("name"), "");
            if (isBlank(name) || plan.lockedParams.contains(name)) {
                continue;
            }
            ParameterDimension dimension = new ParameterDimension();
            dimension.name = name;
            dimension.type = string(row.get("type"), "string").toLowerCase(Locale.ENGLISH);
            dimension.defaultValue = row.containsKey("default")
                    ? row.get("default")
                    : row.get("defaultValue");
            if (dimension.defaultValue == null) {
                dimension.defaultValue = defaultParams.get(name);
            }
            dimension.candidates.addAll(resolveCandidates(dimension.type, row, dimension.defaultValue));
            if (dimension.candidates.isEmpty()) {
                continue;
            }
            dimension.coarseActive = isCoarseActive(plan, name);
            dimension.fineActive = isFineActive(plan, name);
            if (!dimension.coarseActive && !dimension.fineActive) {
                continue;
            }
            result.add(dimension);
        }
        return result;
    }

    private boolean isCoarseActive(OptimizationPlan plan, String name) {
        if (plan == null || isBlank(name) || plan.lockedParams.contains(name)) {
            return false;
        }
        if (plan.priorityParams.isEmpty() && plan.coarseOnlyParams.isEmpty() && plan.fineOnlyParams.isEmpty()) {
            return true;
        }
        if (plan.fineOnlyParams.contains(name)) {
            return false;
        }
        return plan.priorityParams.contains(name) || plan.coarseOnlyParams.contains(name);
    }

    private boolean isFineActive(OptimizationPlan plan, String name) {
        if (plan == null || isBlank(name) || plan.lockedParams.contains(name)) {
            return false;
        }
        if (plan.priorityParams.isEmpty() && plan.coarseOnlyParams.isEmpty() && plan.fineOnlyParams.isEmpty()) {
            return true;
        }
        if (plan.coarseOnlyParams.contains(name)) {
            return false;
        }
        return plan.priorityParams.contains(name) || plan.fineOnlyParams.contains(name);
    }

    private List<ParameterDimension> activeDimensions(OptimizationPlan plan, boolean coarse) {
        List<ParameterDimension> result = new ArrayList<ParameterDimension>();
        if (plan == null || plan.dimensions == null) {
            return result;
        }
        for (ParameterDimension dimension : plan.dimensions) {
            if (dimension == null) {
                continue;
            }
            if (coarse && dimension.coarseActive) {
                result.add(dimension);
            } else if (!coarse && dimension.fineActive) {
                result.add(dimension);
            }
        }
        return result;
    }

    private List<Object> resolveCandidates(String type, Map<String, Object> row, Object defaultValue) {
        List<Object> result = new ArrayList<Object>();
        Object candidates = row.get("candidates");
        if (candidates instanceof List) {
            for (Object item : (List<?>) candidates) {
                result.add(normalizeValue(type, item));
            }
        } else if (row.containsKey("min") && row.containsKey("max") && row.containsKey("step")) {
            double min = doubleValue(row.get("min"), 0D);
            double max = doubleValue(row.get("max"), 0D);
            double step = doubleValue(row.get("step"), 0D);
            if (step > 0D && max >= min) {
                for (double current = min; current <= max + 0.0000001D; current += step) {
                    result.add(normalizeValue(type, current));
                }
            }
        }
        if (defaultValue != null) {
            Object normalizedDefault = normalizeValue(type, defaultValue);
            if (!containsValue(result, normalizedDefault)) {
                result.add(normalizedDefault);
            }
        }
        return result;
    }

    private List<ParameterDimension> buildNeighborDimensions(List<ParameterDimension> base,
                                                             Map<String, Object> selectedParamSet) {
        List<ParameterDimension> result = new ArrayList<ParameterDimension>();
        for (ParameterDimension source : base) {
            ParameterDimension target = new ParameterDimension();
            target.name = source.name;
            target.type = source.type;
            target.defaultValue = source.defaultValue;
            target.coarseActive = source.coarseActive;
            target.fineActive = source.fineActive;
            Object selected = selectedParamSet.get(source.name);
            int index = indexOf(source.candidates, selected);
            if (index < 0 || source.candidates.size() <= 3) {
                target.candidates.addAll(source.candidates);
            } else {
                int begin = Math.max(0, index - 1);
                int end = Math.min(source.candidates.size() - 1, index + 1);
                for (int i = begin; i <= end; i++) {
                    target.candidates.add(source.candidates.get(i));
                }
                Object normalizedDefault = normalizeValue(source.type, source.defaultValue);
                if (!containsValue(target.candidates, normalizedDefault)) {
                    target.candidates.add(normalizedDefault);
                }
            }
            result.add(target);
        }
        return result;
    }

    private List<Map<String, Object>> cartesian(List<ParameterDimension> dimensions, boolean coarse, int limit) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (dimensions == null || dimensions.isEmpty()) {
            result.add(new LinkedHashMap<String, Object>());
            return result;
        }
        cartesianRecursive(dimensions, 0, new LinkedHashMap<String, Object>(), result, coarse, Math.max(1, limit));
        return result;
    }

    private void cartesianRecursive(List<ParameterDimension> dimensions,
                                    int index,
                                    Map<String, Object> current,
                                    List<Map<String, Object>> result,
                                    boolean coarse,
                                    int limit) {
        if (result.size() >= limit) {
            return;
        }
        if (index >= dimensions.size()) {
            result.add(copy(current));
            return;
        }
        ParameterDimension dimension = dimensions.get(index);
        List<Object> candidates = coarse ? sampleCandidates(dimension) : dimension.candidates;
        for (Object candidate : candidates) {
            if (result.size() >= limit) {
                return;
            }
            current.put(dimension.name, candidate);
            cartesianRecursive(dimensions, index + 1, current, result, coarse, limit);
        }
    }

    private List<Map<String, Object>> randomSample(List<ParameterDimension> dimensions,
                                                   Map<String, Object> defaultParams,
                                                   int limit,
                                                   long seed) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (dimensions == null || dimensions.isEmpty()) {
            result.add(copy(defaultParams));
            return result;
        }
        Random random = new Random(seed);
        Set<String> seen = new LinkedHashSet<String>();
        Map<String, Object> baseline = copy(defaultParams);
        if (seen.add(JsonUtils.Serializer(baseline))) {
            result.add(baseline);
        }
        int maxAttempts = Math.max(limit * 20, 50);
        int attempt = 0;
        while (result.size() < limit && attempt++ < maxAttempts) {
            Map<String, Object> candidate = copy(defaultParams);
            for (ParameterDimension dimension : dimensions) {
                if (dimension.candidates.isEmpty()) {
                    continue;
                }
                Object sampled = dimension.candidates.get(random.nextInt(dimension.candidates.size()));
                candidate.put(dimension.name, sampled);
            }
            String key = JsonUtils.Serializer(candidate);
            if (seen.add(key)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private List<Object> sampleCandidates(ParameterDimension dimension) {
        List<Object> source = dimension.candidates;
        if (source.size() <= 3) {
            return source;
        }
        List<Object> result = new ArrayList<Object>();
        result.add(source.get(0));
        result.add(source.get(source.size() / 2));
        result.add(source.get(source.size() - 1));
        Object normalizedDefault = normalizeValue(dimension.type, dimension.defaultValue);
        if (!containsValue(result, normalizedDefault)) {
            result.add(normalizedDefault);
        }
        return result;
    }

    private List<Map<String, Object>> uniqueParamSets(List<Map<String, Object>> candidates,
                                                      Map<String, Object> defaultParams) {
        Set<String> seen = new LinkedHashSet<String>();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Object> baseline = copy(defaultParams);
        if (seen.add(JsonUtils.Serializer(baseline))) {
            result.add(baseline);
        }
        for (Map<String, Object> candidate : candidates) {
            Map<String, Object> merged = copy(defaultParams);
            merged.putAll(candidate);
            String key = JsonUtils.Serializer(merged);
            if (seen.add(key)) {
                result.add(merged);
            }
        }
        return result;
    }

    private long estimateGridSize(List<ParameterDimension> dimensions, boolean coarse) {
        long result = 1L;
        for (ParameterDimension dimension : dimensions) {
            List<Object> values = coarse ? sampleCandidates(dimension) : dimension.candidates;
            result *= Math.max(1, values.size());
            if (result > DEFAULT_MAX_FULL_GRID * 16L) {
                return result;
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParamSet(String json) {
        if (isBlank(json)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object obj = JsonUtils.Deserialize(json, Map.class);
            if (obj instanceof Map) {
                return copy((Map<String, Object>) obj);
            }
        } catch (Exception ignore) {
        }
        return new LinkedHashMap<String, Object>();
    }

    private Set<String> stringSet(Object value) {
        Set<String> result = new LinkedHashSet<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                String text = string(item, "");
                if (!isBlank(text)) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = string(value, "");
        if (isBlank(text)) {
            return result;
        }
        result.addAll(Arrays.asList(text.split(",")));
        Set<String> normalized = new LinkedHashSet<String>();
        for (String item : result) {
            if (!isBlank(item)) {
                normalized.add(item.trim());
            }
        }
        return normalized;
    }

    private boolean containsValue(List<Object> values, Object target) {
        return indexOf(values, target) >= 0;
    }

    private int indexOf(List<Object> values, Object target) {
        if (values == null || values.isEmpty()) {
            return -1;
        }
        String key = valueKey(target);
        for (int i = 0; i < values.size(); i++) {
            if (valueKey(values.get(i)).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private String valueKey(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Object normalizeValue(String type, Object value) {
        if (value == null) {
            return null;
        }
        if ("int".equalsIgnoreCase(type) || "integer".equalsIgnoreCase(type)) {
            return Integer.valueOf(intValue(value, 0));
        }
        if ("double".equalsIgnoreCase(type) || "float".equalsIgnoreCase(type) || "decimal".equalsIgnoreCase(type)) {
            return Double.valueOf(doubleValue(value, 0D));
        }
        if ("bool".equalsIgnoreCase(type) || "boolean".equalsIgnoreCase(type)) {
            if (value instanceof Boolean) {
                return value;
            }
            return Boolean.valueOf(String.valueOf(value));
        }
        return String.valueOf(value);
    }

    private boolean isNumericType(String type) {
        return "int".equalsIgnoreCase(type)
                || "integer".equalsIgnoreCase(type)
                || "double".equalsIgnoreCase(type)
                || "float".equalsIgnoreCase(type)
                || "decimal".equalsIgnoreCase(type);
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String normalizeMode(String value) {
        String mode = string(value, MODE_LAYERED_GRID).toUpperCase(Locale.ENGLISH);
        if (MODE_RANDOM_LOCAL.equals(mode)) {
            return MODE_RANDOM_LOCAL;
        }
        return MODE_LAYERED_GRID;
    }

    private String normalizeObjective(String value) {
        String objective = string(value, OBJECTIVE_PROFIT_FIRST).toUpperCase(Locale.ENGLISH);
        if (OBJECTIVE_PROFIT_FIRST.equals(objective)) {
            return OBJECTIVE_PROFIT_FIRST;
        }
        return OBJECTIVE_PROFIT_FIRST;
    }

    private BigDecimal decimalValue(Object value, BigDecimal fallback) {
        try {
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            if (value instanceof Number) {
                return BigDecimal.valueOf(((Number) value).doubleValue()).setScale(6, RoundingMode.HALF_UP);
            }
            if (value == null) {
                return fallback;
            }
            return new BigDecimal(String.valueOf(value)).setScale(6, RoundingMode.HALF_UP);
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int intValue(Object value) {
        return intValue(value, 0);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private String string(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class OptimizationPlan {
        public boolean optimizationSupported;
        public String optimizationMode = MODE_LAYERED_GRID;
        public String objective = OBJECTIVE_PROFIT_FIRST;
        public int topN = DEFAULT_TOP_N;
        public int maxFullGrid = DEFAULT_MAX_FULL_GRID;
        public int maxCoarseCandidates = DEFAULT_MAX_COARSE_CANDIDATES;
        public int maxFineCandidates = DEFAULT_MAX_FINE_CANDIDATES;
        public int fitWindowDays = DEFAULT_FIT_WINDOW_DAYS;
        public int validateWindowDays = DEFAULT_VALIDATE_WINDOW_DAYS;
        public int forwardWindowDays = DEFAULT_FORWARD_WINDOW_DAYS;
        public int minSliceCount = DEFAULT_MIN_SLICE_COUNT;
        public long randomSeed = DEFAULT_RANDOM_SEED;
        public BigDecimal minForwardContribution = DEFAULT_MIN_FORWARD_CONTRIBUTION;
        public Set<String> lockedParams = new LinkedHashSet<String>();
        public Set<String> priorityParams = new LinkedHashSet<String>();
        public Set<String> coarseOnlyParams = new LinkedHashSet<String>();
        public Set<String> fineOnlyParams = new LinkedHashSet<String>();
        public Map<String, Object> defaultParams = new LinkedHashMap<String, Object>();
        public List<ParameterDimension> dimensions = new ArrayList<ParameterDimension>();
    }

    public static class ParameterDimension {
        public String name;
        public String type;
        public Object defaultValue;
        public List<Object> candidates = new ArrayList<Object>();
        public boolean coarseActive = true;
        public boolean fineActive = true;
    }
}
