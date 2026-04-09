package com.app.dc.service.simulation;

import com.app.dc.service.simulation.BacktestModels.OptimizationTrial;
import com.app.dc.signal.StrategyParametersJson;
import com.app.dc.signal.StrategyParametersSupport;
import com.gateway.connector.utils.JsonUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class BacktestOptimizationService {

    private static final int DEFAULT_TOP_N = 10;
    private static final int DEFAULT_MAX_FULL_GRID = 256;

    public OptimizationPlan buildPlan(String parametersJson) {
        StrategyParametersJson parsed = StrategyParametersSupport.parse(parametersJson);
        OptimizationPlan plan = new OptimizationPlan();
        plan.optimizationSupported = StrategyParametersSupport.isOptimizationSupported(parametersJson);
        plan.optimizationMode = string(parsed.optimizationProfile.get("mode"), "LAYERED_GRID");
        plan.topN = Math.max(1, intValue(parsed.optimizationProfile.get("topN"), DEFAULT_TOP_N));
        plan.maxFullGrid = Math.max(1, intValue(parsed.optimizationProfile.get("maxFullGrid"), DEFAULT_MAX_FULL_GRID));
        plan.defaultParams.putAll(StrategyParametersSupport.extractDefaultParams(parametersJson));
        plan.dimensions.addAll(parseDimensions(parsed.parameterSchema, plan.defaultParams));
        if (plan.dimensions.isEmpty()) {
            plan.optimizationSupported = false;
        }
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
        if (plan == null || !plan.optimizationSupported || plan.dimensions.isEmpty()) {
            return buildDefaultOnly(plan);
        }
        if (estimateGridSize(plan.dimensions, false) <= plan.maxFullGrid) {
            return uniqueParamSets(cartesian(plan.dimensions, false), plan.defaultParams);
        }
        return uniqueParamSets(cartesian(plan.dimensions, true), plan.defaultParams);
    }

    public List<Map<String, Object>> buildFineParamSets(OptimizationPlan plan, List<OptimizationTrial> rankedTrials) {
        if (plan == null || !plan.optimizationSupported || plan.dimensions.isEmpty()) {
            return Collections.emptyList();
        }
        List<OptimizationTrial> topTrials = topRanked(rankedTrials, plan.topN);
        if (topTrials.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<String>();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (OptimizationTrial trial : topTrials) {
            Map<String, Object> paramSet = parseParamSet(trial.paramSetJson);
            List<Map<String, Object>> candidates = cartesian(buildNeighborDimensions(plan.dimensions, paramSet), false);
            for (Map<String, Object> candidate : uniqueParamSets(candidates, plan.defaultParams)) {
                String key = JsonUtils.Serializer(candidate);
                if (seen.add(key)) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    public void rankTrials(List<OptimizationTrial> trials) {
        if (trials == null || trials.isEmpty()) {
            return;
        }
        Collections.sort(trials, new Comparator<OptimizationTrial>() {
            @Override
            public int compare(OptimizationTrial left, OptimizationTrial right) {
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
        return trial;
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
                                                     Map<String, Object> defaultParams) {
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
            if (isBlank(name)) {
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
            result.add(dimension);
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

    private List<Map<String, Object>> cartesian(List<ParameterDimension> dimensions, boolean coarse) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (dimensions == null || dimensions.isEmpty()) {
            result.add(new LinkedHashMap<String, Object>());
            return result;
        }
        cartesianRecursive(dimensions, 0, new LinkedHashMap<String, Object>(), result, coarse);
        return result;
    }

    private void cartesianRecursive(List<ParameterDimension> dimensions,
                                    int index,
                                    Map<String, Object> current,
                                    List<Map<String, Object>> result,
                                    boolean coarse) {
        if (index >= dimensions.size()) {
            result.add(copy(current));
            return;
        }
        ParameterDimension dimension = dimensions.get(index);
        List<Object> candidates = coarse ? sampleCandidates(dimension) : dimension.candidates;
        for (Object candidate : candidates) {
            current.put(dimension.name, candidate);
            cartesianRecursive(dimensions, index + 1, current, result, coarse);
        }
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
        public String optimizationMode = "LAYERED_GRID";
        public int topN = DEFAULT_TOP_N;
        public int maxFullGrid = DEFAULT_MAX_FULL_GRID;
        public Map<String, Object> defaultParams = new LinkedHashMap<String, Object>();
        public List<ParameterDimension> dimensions = new ArrayList<ParameterDimension>();
    }

    public static class ParameterDimension {
        public String name;
        public String type;
        public Object defaultValue;
        public List<Object> candidates = new ArrayList<Object>();
    }
}
