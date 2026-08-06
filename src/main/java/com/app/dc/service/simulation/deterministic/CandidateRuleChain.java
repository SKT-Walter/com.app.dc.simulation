package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.dynamic.DynamicStrategyCatalog;
import com.app.dc.service.simulation.dynamic.DynamicStrategyMeta;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Ordered hard-admission rules. Scores can never restore a rejected strategy. */
@Service
public class CandidateRuleChain {
    @Autowired private DynamicStrategyCatalog catalog;
    @Autowired private SymbolStrategyProfileService strategyProfiles;
    @Value("${backtest.regime.minimumBars:60}") private int minimumBars;

    public CandidateSelectionResult select(StrategyEvaluationContext context) {
        CandidateSelectionResult result = new CandidateSelectionResult();
        for (DynamicStrategyMeta strategy : catalog.all()) {
            String rejection = rejection(context, strategy);
            if (rejection == null) result.candidates.add(strategy);
            else result.rejectedStrategies.put(strategy.strategyName, rejection);
        }
        return result;
    }

    private String rejection(StrategyEvaluationContext context, DynamicStrategyMeta meta) {
        if (context == null || context.series == null || context.technical == null) return "INVALID_CONTEXT";
        if (context.series.getBarCount() < minimumBars) return "DATA_WARMUP";
        TechnicalSnapshot t = context.technical;
        if (!finitePositive(t.open) || !finitePositive(t.high) || !finitePositive(t.low)
                || !finitePositive(t.close) || t.high < t.low) return "INVALID_OHLC";
        if (context.regime == null || !context.regime.tradeable) return "NO_TRADE_REGIME";
        if (!meta.enabled) return "STRATEGY_DISABLED";
        if ("ethStructuralBullTrend".equalsIgnoreCase(meta.strategyName)
                &&!("ETHUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if ("ethStructuralBearTrend".equalsIgnoreCase(meta.strategyName)
                &&!("ETHUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if ("solMomentumBullTrend".equalsIgnoreCase(meta.strategyName)
                &&!("SOLUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if (!strategyProfiles.isStrategyEnabled(context.symbol, context.timeframe,
                meta.strategyName)) return "SYMBOL_STRATEGY_DISABLED";
        if("binanceTrend".equalsIgnoreCase(meta.strategyName)
                &&"UP".equals(context.regime.trend)
                &&!strategyProfiles.binanceTrendBuyEnabled(context.symbol,context.timeframe))
            return "SYMBOL_SIDE_BLOCKED";
        boolean lifecycleTrend=lifecycleTrend(context,meta)||bullTrend(context,meta)||bearTrend(context,meta);
        if (!supportsRegime(meta, context.regime.code())&&!lifecycleTrend) return "UNSUPPORTED_REGIME";
        if ("orderBookImbalanceReversion".equalsIgnoreCase(meta.strategyName))
            return "MISSING_ORDER_BOOK_FEATURE";
        if (meta.requiredFeatures != null) {
            for (String feature : meta.requiredFeatures)
                if (context.regime.features == null || !context.regime.features.containsKey(feature)
                        || context.regime.features.get(feature) == null
                        || !Double.isFinite(context.regime.features.get(feature)))
                    return "MISSING_REQUIRED_FEATURE:" + feature;
        }
        boolean compressionTriggered = context.trendCompression != null
                && context.trendCompression.triggered
                && "compressionBreak".equalsIgnoreCase(meta.strategyName);
        if ("BREAKOUT".equalsIgnoreCase(meta.family)
                && !context.regime.breakoutExpansion && !compressionTriggered)
            return "BREAKOUT_EXPANSION_REQUIRED";
        if ("TREND".equalsIgnoreCase(meta.family) && "NONE".equals(context.regime.trend)
                &&!lifecycleTrend)
            return "DIRECTION_INCOMPATIBLE";
        if ("MEAN_REVERSION".equalsIgnoreCase(meta.family) && !"NONE".equals(context.regime.trend))
            return "DIRECTION_INCOMPATIBLE";
        if (t.volumeRatio < .05) return "ABNORMAL_LIQUIDITY";
        return null;
    }

    private boolean supportsRegime(DynamicStrategyMeta meta, String regime) {
        if (meta.supportedRegimes == null) return false;
        for (String value : meta.supportedRegimes)
            if ("*".equals(value) || regime.equalsIgnoreCase(value)) return true;
        return false;
    }

    private boolean lifecycleTrend(StrategyEvaluationContext context,DynamicStrategyMeta meta){
        if(!"binanceTrend".equalsIgnoreCase(meta.strategyName)
                ||!strategyProfiles.binanceTrendSettings(context.symbol,context.timeframe).lifecycleEnabled
                ||context.trendLifecycle==null)return false;
        String phase=context.trendLifecycle.phase;
        return !com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot.WARMUP.equals(phase)
                &&!com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot.NEUTRAL.equals(phase)
                &&!com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot.INVALIDATED.equals(phase);
    }

    private boolean bullTrend(StrategyEvaluationContext context,DynamicStrategyMeta meta){
        com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot snapshot;
        if("ethStructuralBullTrend".equalsIgnoreCase(meta.strategyName))snapshot=context.ethBullTrend;
        else if("solMomentumBullTrend".equalsIgnoreCase(meta.strategyName))snapshot=context.solBullTrend;
        else return false;
        return snapshot!=null&&snapshot.activeCandidate()
                &&context.structuralTrend!=null&&context.structuralTrend.ready
                &&context.structuralTrend.isBull();
    }

    private boolean bearTrend(StrategyEvaluationContext context,DynamicStrategyMeta meta){
        if(!"ethStructuralBearTrend".equalsIgnoreCase(meta.strategyName))return false;
        com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot snapshot=context.ethBearTrend;
        return snapshot!=null&&snapshot.activeCandidate();
    }

    private boolean finitePositive(double value) { return Double.isFinite(value) && value > 0; }
}
