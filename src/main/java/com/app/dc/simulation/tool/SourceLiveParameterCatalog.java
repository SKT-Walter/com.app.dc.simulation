package com.app.dc.simulation.tool;

import com.app.dc.signal.StrategyParametersSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SourceLiveParameterCatalog {

    private static final String DEFAULT_TEXT = "15M";

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
        if ("binanceRange".equalsIgnoreCase(name)) {
            return build(
                    mapOf(),
                    parameters());
        }
        if ("binanceTrend".equalsIgnoreCase(name)) {
            return build(
                    mapOf("fastPeriod", 9, "midPeriod", 21, "slowPeriod", 55,
                            "atrBufferMultiplier", 0.35D, "minMaSpreadPct", 0.002D,
                            "targetRiskRewardRatio", 1.4D, "minTargetAtr", 0.6D),
                    parameters(
                            intParam("fastPeriod", 9, 7, 9, 11),
                            intParam("midPeriod", 21, 18, 21, 24),
                            intParam("slowPeriod", 55, 45, 55, 65),
                            doubleParam("atrBufferMultiplier", 0.35D, 0.25D, 0.35D, 0.45D),
                            doubleParam("minMaSpreadPct", 0.002D, 0.001D, 0.002D, 0.003D),
                            doubleParam("targetRiskRewardRatio", 1.4D, 1.1D, 1.4D, 1.7D),
                            doubleParam("minTargetAtr", 0.6D, 0.4D, 0.6D, 0.8D)));
        }
        if ("breakoutRetestContinuation".equalsIgnoreCase(name)
                || "breakoutRetestContinuationTrend".equalsIgnoreCase(name)) {
            return build(
                    mapOf("breakLookback", 16, "retestAtrMax", 1.2D, "stopAtrBuffer", 0.25D,
                            "takeProfitAtrMultiplier", 1.0D, "minTargetAtr", 0.6D,
                            "fastEmaPeriod", 10, "trendEmaPeriod", 20, "slowEmaPeriod", 60,
                            "minTrendAtrSpread", 0.45D, "breakConfirmAtr", 0.15D,
                            "reclaimCloseBufferAtr", 0.05D, "minCloseLocation", 0.6D,
                            "minRangeAtr", 1.2D, "minRiskRewardRatio", 1.2D),
                    parameters(
                            intParam("fastEmaPeriod", 10, 8, 10, 12),
                            intParam("trendEmaPeriod", 20, 16, 20, 24),
                            intParam("slowEmaPeriod", 60, 50, 60, 70),
                            intParam("breakLookback", 16, 12, 16, 20),
                            doubleParam("retestAtrMax", 1.2D, 0.8D, 1.2D, 1.6D),
                            doubleParam("stopAtrBuffer", 0.25D, 0.15D, 0.25D, 0.35D),
                            doubleParam("takeProfitAtrMultiplier", 1.0D, 0.8D, 1.0D, 1.2D),
                            doubleParam("minTargetAtr", 0.6D, 0.4D, 0.6D, 0.8D),
                            doubleParam("minTrendAtrSpread", 0.45D, 0.30D, 0.45D, 0.60D),
                            doubleParam("breakConfirmAtr", 0.15D, 0.05D, 0.15D, 0.25D),
                            doubleParam("reclaimCloseBufferAtr", 0.05D, 0.00D, 0.05D, 0.10D),
                            doubleParam("minCloseLocation", 0.6D, 0.55D, 0.60D, 0.65D),
                            doubleParam("minRangeAtr", 1.2D, 1.0D, 1.2D, 1.4D),
                            doubleParam("minRiskRewardRatio", 1.2D, 1.0D, 1.2D, 1.4D)));
        }
        if ("emaPullbackBuy".equalsIgnoreCase(name)) {
            return build(
                    mapOf("fastEmaPeriod", 10, "midEmaPeriod", 20, "slowEmaPeriod", 60,
                            "atrPeriod", 14, "rsiPeriod", 14,
                            "pullbackAtrMax", 0.8D, "stopAtrBuffer", 0.25D,
                            "riskRewardRatio", 1.2D, "minTargetAtr", 0.8D,
                            "minPullbackAtr", 0.20D, "minTrendAtrSpread", 0.35D,
                            "minRecoveryRsi", 50.0D, "minRecoveryCloseLocation", 0.6D),
                    parameters(
                            intParam("fastEmaPeriod", 10, 8, 10, 12),
                            intParam("midEmaPeriod", 20, 16, 20, 24),
                            intParam("slowEmaPeriod", 60, 45, 60, 75),
                            intParam("atrPeriod", 14, 10, 14, 18),
                            intParam("rsiPeriod", 14, 10, 14, 18),
                            doubleParam("pullbackAtrMax", 0.8D, 0.6D, 0.8D, 1.0D),
                            doubleParam("stopAtrBuffer", 0.25D, 0.15D, 0.25D, 0.35D),
                            doubleParam("riskRewardRatio", 1.2D, 1.0D, 1.2D, 1.5D),
                            doubleParam("minTargetAtr", 0.8D, 0.6D, 0.8D, 1.0D),
                            doubleParam("minPullbackAtr", 0.20D, 0.15D, 0.20D, 0.25D),
                            doubleParam("minTrendAtrSpread", 0.35D, 0.25D, 0.35D, 0.45D),
                            doubleParam("minRecoveryRsi", 50.0D, 48.0D, 50.0D, 52.0D),
                            doubleParam("minRecoveryCloseLocation", 0.6D, 0.55D, 0.60D, 0.65D)));
        }
        if ("compressionBreak".equalsIgnoreCase(name)) {
            return build(
                    mapOf("atrPeriod", 14, "compressLookback", 6, "baseLookback", 20,
                            "stopAtrBuffer", 0.35D, "minTargetAtr", 1.0D),
                    parameters(
                            intParam("atrPeriod", 14, 10, 14, 18),
                            intParam("compressLookback", 6, 4, 6, 8),
                            intParam("baseLookback", 20, 16, 20, 24),
                            doubleParam("stopAtrBuffer", 0.35D, 0.20D, 0.35D, 0.50D),
                            doubleParam("minTargetAtr", 1.0D, 0.8D, 1.0D, 1.2D)));
        }
        if ("failedBreakReversal".equalsIgnoreCase(name)) {
            return build(
                    mapOf("fastEmaPeriod", 10, "slowEmaPeriod", 30, "rsiPeriod", 14,
                            "atrPeriod", 14, "breakLookback", 20, "stopAtrBuffer", 0.3D,
                            "minTargetAtr", 0.8D, "maxTrendAtrSpread", 0.6D,
                            "minRejectWickAtr", 0.1D, "minBreakDistanceAtr", 0.05D,
                            "reclaimCloseBufferAtr", 0.05D, "minConfirmCloseLocation", 0.6D,
                            "shortConfirmRsiMax", 52.0D, "longConfirmRsiMin", 48.0D,
                            "riskRewardRatio", 1.3D),
                    parameters(
                            intParam("fastEmaPeriod", 10, 8, 10, 12),
                            intParam("slowEmaPeriod", 30, 24, 30, 36),
                            intParam("rsiPeriod", 14, 10, 14, 18),
                            intParam("atrPeriod", 14, 10, 14, 18),
                            intParam("breakLookback", 20, 14, 18, 22),
                            doubleParam("stopAtrBuffer", 0.3D, 0.15D, 0.25D, 0.35D),
                            doubleParam("minTargetAtr", 0.8D, 0.6D, 0.8D, 1.0D),
                            doubleParam("maxTrendAtrSpread", 0.6D, 0.4D, 0.6D, 0.8D),
                            doubleParam("minRejectWickAtr", 0.1D, 0.05D, 0.1D, 0.2D),
                            doubleParam("minBreakDistanceAtr", 0.05D, 0.03D, 0.05D, 0.08D),
                            doubleParam("reclaimCloseBufferAtr", 0.05D, 0.00D, 0.03D, 0.05D),
                            doubleParam("minConfirmCloseLocation", 0.6D, 0.55D, 0.60D, 0.65D),
                            doubleParam("shortConfirmRsiMax", 52.0D, 50.0D, 52.0D, 54.0D),
                            doubleParam("longConfirmRsiMin", 48.0D, 46.0D, 48.0D, 50.0D),
                            doubleParam("riskRewardRatio", 1.3D, 1.1D, 1.3D, 1.5D)));
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
                    mapOf("lookback", 16, "impulseAtrMin", 0.8D, "stopAtrBuffer", 0.35D,
                            "riskRewardRatio", 1.2D, "minTargetAtr", 0.8D,
                            "fastEmaPeriod", 10, "midEmaPeriod", 20, "slowEmaPeriod", 60),
                    parameters(
                            intParam("fastEmaPeriod", 10, 8, 10, 12),
                            intParam("midEmaPeriod", 20, 16, 20, 24),
                            intParam("slowEmaPeriod", 60, 45, 60, 75),
                            intParam("lookback", 16, 12, 16, 20),
                            doubleParam("impulseAtrMin", 0.8D, 0.6D, 0.8D, 1.0D),
                            doubleParam("stopAtrBuffer", 0.35D, 0.2D, 0.35D, 0.5D),
                            doubleParam("riskRewardRatio", 1.2D, 1.0D, 1.2D, 1.5D),
                            doubleParam("minTargetAtr", 0.8D, 0.6D, 0.8D, 1.0D)));
        }
        if ("smallRangeBreakout".equalsIgnoreCase(name)) {
            return build(
                    mapOf("atrPeriod", 14, "compressLookback", 6, "baseLookback", 20,
                            "stopAtrBuffer", 0.35D, "minTargetAtr", 1.0D),
                    parameters(
                            intParam("atrPeriod", 14, 10, 14, 18),
                            intParam("compressLookback", 6, 4, 6, 8),
                            intParam("baseLookback", 20, 16, 20, 24),
                            doubleParam("stopAtrBuffer", 0.35D, 0.20D, 0.35D, 0.50D),
                            doubleParam("minTargetAtr", 1.0D, 0.8D, 1.0D, 1.2D)));
        }
        if ("trendPullbackRecovery".equalsIgnoreCase(name)
                || "trendRestart".equalsIgnoreCase(name)) {
            return build(
                    mapOf("fastEmaPeriod", 10, "trendEmaPeriod", 20, "slowEmaPeriod", 60,
                            "atrPeriod", 14, "rsiPeriod", 14,
                            "pullbackLookback", 12, "swingLookback", 24,
                            "minPullbackAtr", 0.8D, "maxPullbackAtr", 2.0D,
                            "stopAtrBuffer", 0.30D, "minTargetAtr", 0.8D,
                            "minTrendAtrSpread", 0.30D, "structureBufferAtr", 0.4D,
                            "recoveryBreakBufferAtr", 0.05D, "minRecoveryCloseLocation", 0.6D,
                            "longRecoveryRsiMin", 52.0D, "shortRecoveryRsiMax", 48.0D,
                            "riskRewardRatio", 1.2D),
                    parameters(
                            intParam("fastEmaPeriod", 10, 8, 10, 12),
                            intParam("trendEmaPeriod", 20, 16, 20, 24),
                            intParam("slowEmaPeriod", 60, 45, 60, 75),
                            intParam("atrPeriod", 14, 10, 14, 18),
                            intParam("rsiPeriod", 14, 10, 14, 18),
                            intParam("pullbackLookback", 12, 8, 12, 16),
                            intParam("swingLookback", 24, 18, 24, 30),
                            doubleParam("minPullbackAtr", 0.8D, 0.6D, 0.8D, 1.0D),
                            doubleParam("maxPullbackAtr", 2.0D, 1.6D, 2.0D, 2.4D),
                            doubleParam("stopAtrBuffer", 0.30D, 0.20D, 0.30D, 0.40D),
                            doubleParam("minTargetAtr", 0.8D, 0.6D, 0.8D, 1.0D),
                            doubleParam("minTrendAtrSpread", 0.30D, 0.20D, 0.30D, 0.40D),
                            doubleParam("structureBufferAtr", 0.4D, 0.2D, 0.4D, 0.6D),
                            doubleParam("recoveryBreakBufferAtr", 0.05D, 0.00D, 0.05D, 0.10D),
                            doubleParam("minRecoveryCloseLocation", 0.6D, 0.60D, 0.65D, 0.70D),
                            doubleParam("longRecoveryRsiMin", 52.0D, 50.0D, 52.0D, 54.0D),
                            doubleParam("shortRecoveryRsiMax", 48.0D, 46.0D, 48.0D, 50.0D),
                            doubleParam("riskRewardRatio", 1.2D, 1.0D, 1.2D, 1.4D)));
        }
        return null;
    }

    static String defaultText(String strategyName) {
        if ("smallRangeBreakout".equalsIgnoreCase(strategyName)) {
            return "1H";
        }
        return supported(strategyName) ? DEFAULT_TEXT : "";
    }

    static String defaultSymbols(String strategyName) {
        if ("binanceChannel".equalsIgnoreCase(strategyName)) {
            return "SOLUSDT";
        }
        if ("binanceRange".equalsIgnoreCase(strategyName)) {
            return "ETHUSDT";
        }
        if ("binanceTrend".equalsIgnoreCase(strategyName)) {
            return "BTCUSDT";
        }
        if ("breakoutRetestContinuation".equalsIgnoreCase(strategyName)) {
            return "ETHUSDT";
        }
        if ("breakoutRetestContinuationTrend".equalsIgnoreCase(strategyName)) {
            return "BTCUSDT";
        }
        if ("compressionBreak".equalsIgnoreCase(strategyName)) {
            return "ETHUSDT";
        }
        if ("emaPullbackBuy".equalsIgnoreCase(strategyName)) {
            return "BTCUSDT";
        }
        if ("failedBreakReversal".equalsIgnoreCase(strategyName)) {
            return "ETHUSDT";
        }
        if ("impulseReclaim".equalsIgnoreCase(strategyName)) {
            return "ETHUSDT";
        }
        if ("smallRangeBreakout".equalsIgnoreCase(strategyName)) {
            return "BTCUSDT";
        }
        if ("strongMomentumContinuation".equalsIgnoreCase(strategyName)) {
            return "BTCUSDT";
        }
        if ("trendPullbackRecovery".equalsIgnoreCase(strategyName)) {
            return "ETHUSDT";
        }
        if ("trendRestart".equalsIgnoreCase(strategyName)) {
            return "BTCUSDT";
        }
        return "";
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
