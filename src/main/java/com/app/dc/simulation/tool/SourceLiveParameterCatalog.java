package com.app.dc.simulation.tool;

import com.app.dc.signal.StrategyParametersSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SourceLiveParameterCatalog {

    private SourceLiveParameterCatalog() {
    }

    static boolean supported(String strategyName) {
        return parametersJson(strategyName) != null;
    }

    static String parametersJson(String strategyName) {
        if (isBlank(strategyName)) {
            return null;
        }
        String name = strategyName.trim();
        if ("binanceChannel".equalsIgnoreCase(name)) {
            return build(
                    mapOf("channelLookback", 20, "atrPeriod", 14, "atrBufferMultiplier", 0.5D, "takeProfitWidthMultiplier", 1.0D),
                    parameters(
                            intParam("channelLookback", 20, 16, 20, 24),
                            intParam("atrPeriod", 14, 10, 14, 18),
                            doubleParam("atrBufferMultiplier", 0.5D, 0.35D, 0.5D, 0.65D),
                            doubleParam("takeProfitWidthMultiplier", 1.0D, 0.8D, 1.0D, 1.2D)));
        }
        if ("binanceTrend".equalsIgnoreCase(name)) {
            return build(
                    mapOf("fastPeriod", 9, "midPeriod", 21, "slowPeriod", 55,
                            "atrBufferMultiplier", 0.5D, "minMaSpreadPct", 0.008D, "targetRiskRewardRatio", 1.8D),
                    parameters(
                            intParam("fastPeriod", 9, 7, 9, 11),
                            intParam("midPeriod", 21, 18, 21, 24),
                            intParam("slowPeriod", 55, 45, 55, 65),
                            doubleParam("atrBufferMultiplier", 0.5D, 0.35D, 0.5D, 0.65D),
                            doubleParam("minMaSpreadPct", 0.008D, 0.005D, 0.008D, 0.011D),
                            doubleParam("targetRiskRewardRatio", 1.8D, 1.4D, 1.8D, 2.2D)));
        }
        if ("breakoutRetestContinuation".equalsIgnoreCase(name)
                || "breakoutRetestContinuationTrend".equalsIgnoreCase(name)) {
            return build(
                    mapOf("breakLookback", 20, "retestAtrMax", 0.8D, "stopAtrBuffer", 0.35D,
                            "takeProfitAtrMultiplier", 1.2D, "minTargetAtr", 1.0D),
                    parameters(
                            intParam("breakLookback", 20, 16, 20, 24),
                            doubleParam("retestAtrMax", 0.8D, 0.6D, 0.8D, 1.0D),
                            doubleParam("stopAtrBuffer", 0.35D, 0.25D, 0.35D, 0.45D),
                            doubleParam("takeProfitAtrMultiplier", 1.2D, 1.0D, 1.2D, 1.4D),
                            doubleParam("minTargetAtr", 1.0D, 0.8D, 1.0D, 1.2D)));
        }
        if ("emaPullbackBuy".equalsIgnoreCase(name)) {
            return build(
                    mapOf("fastEmaPeriod", 10, "midEmaPeriod", 20, "slowEmaPeriod", 60,
                            "pullbackAtrMax", 0.6D, "stopAtrBuffer", 0.35D, "riskRewardRatio", 1.5D),
                    parameters(
                            intParam("fastEmaPeriod", 10, 8, 10, 12),
                            intParam("midEmaPeriod", 20, 18, 20, 22),
                            intParam("slowEmaPeriod", 60, 50, 60, 70),
                            doubleParam("pullbackAtrMax", 0.6D, 0.4D, 0.6D, 0.8D),
                            doubleParam("stopAtrBuffer", 0.35D, 0.25D, 0.35D, 0.45D),
                            doubleParam("riskRewardRatio", 1.5D, 1.2D, 1.5D, 1.8D)));
        }
        if ("failedBreakReversal".equalsIgnoreCase(name)) {
            return build(
                    mapOf("atrPeriod", 14, "breakLookback", 20, "stopAtrBuffer", 0.3D, "minTargetAtr", 1.0D),
                    parameters(
                            intParam("atrPeriod", 14, 10, 14, 18),
                            intParam("breakLookback", 20, 16, 20, 24),
                            doubleParam("stopAtrBuffer", 0.3D, 0.2D, 0.3D, 0.4D),
                            doubleParam("minTargetAtr", 1.0D, 0.8D, 1.0D, 1.2D)));
        }
        if ("impulseReclaim".equalsIgnoreCase(name)) {
            return build(
                    mapOf("impulseBodyAtr", 1.2D, "reclaimRatio", 0.55D, "stopAtrBuffer", 0.3D, "takeProfitAtrMultiplier", 1.2D),
                    parameters(
                            doubleParam("impulseBodyAtr", 1.2D, 1.0D, 1.2D, 1.4D),
                            doubleParam("reclaimRatio", 0.55D, 0.45D, 0.55D, 0.65D),
                            doubleParam("stopAtrBuffer", 0.3D, 0.2D, 0.3D, 0.4D),
                            doubleParam("takeProfitAtrMultiplier", 1.2D, 1.0D, 1.2D, 1.4D)));
        }
        if ("strongMomentumContinuation".equalsIgnoreCase(name)) {
            return build(
                    mapOf("lookback", 20, "impulseAtrMin", 1.2D, "stopAtrBuffer", 0.5D,
                            "riskRewardRatio", 1.5D, "minTargetAtr", 1.2D),
                    parameters(
                            intParam("lookback", 20, 16, 20, 24),
                            doubleParam("impulseAtrMin", 1.2D, 1.0D, 1.2D, 1.4D),
                            doubleParam("stopAtrBuffer", 0.5D, 0.35D, 0.5D, 0.65D),
                            doubleParam("riskRewardRatio", 1.5D, 1.2D, 1.5D, 1.8D),
                            doubleParam("minTargetAtr", 1.2D, 1.0D, 1.2D, 1.4D)));
        }
        if ("trendPullbackRecovery".equalsIgnoreCase(name)
                || "trendRestart".equalsIgnoreCase(name)) {
            return build(
                    mapOf("pullbackLookback", 12, "swingLookback", 24,
                            "minPullbackAtr", 0.8D, "maxPullbackAtr", 2.6D,
                            "stopAtrBuffer", 0.35D, "minTargetAtr", 1.0D),
                    parameters(
                            intParam("pullbackLookback", 12, 8, 12, 16),
                            intParam("swingLookback", 24, 18, 24, 30),
                            doubleParam("minPullbackAtr", 0.8D, 0.6D, 0.8D, 1.0D),
                            doubleParam("maxPullbackAtr", 2.6D, 2.0D, 2.6D, 3.2D),
                            doubleParam("stopAtrBuffer", 0.35D, 0.25D, 0.35D, 0.45D),
                            doubleParam("minTargetAtr", 1.0D, 0.8D, 1.0D, 1.2D)));
        }
        return null;
    }

    private static String build(Map<String, Object> defaultParams, List<Map<String, Object>> parameters) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("parameters", parameters);
        Map<String, Object> profile = new LinkedHashMap<String, Object>();
        profile.put("mode", "LAYERED_GRID");
        profile.put("topN", Integer.valueOf(6));
        profile.put("maxFullGrid", Integer.valueOf(128));
        return StrategyParametersSupport.buildCandidateParametersJson(schema, defaultParams, profile);
    }

    private static List<Map<String, Object>> parameters(Map<String, Object>... items) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (items == null) {
            return rows;
        }
        for (Map<String, Object> item : items) {
            if (item != null && !item.isEmpty()) {
                rows.add(item);
            }
        }
        return rows;
    }

    private static Map<String, Object> intParam(String name, int defaultValue, int... candidates) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("type", "int");
        row.put("default", Integer.valueOf(defaultValue));
        List<Object> values = new ArrayList<Object>();
        for (int candidate : candidates) {
            values.add(Integer.valueOf(candidate));
        }
        row.put("candidates", values);
        return row;
    }

    private static Map<String, Object> doubleParam(String name, double defaultValue, double... candidates) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("type", "double");
        row.put("default", Double.valueOf(defaultValue));
        List<Object> values = new ArrayList<Object>();
        for (double candidate : candidates) {
            values.add(Double.valueOf(candidate));
        }
        row.put("candidates", values);
        return row;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        if (kv == null) {
            return row;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            row.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return row;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
