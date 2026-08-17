package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.dynamic.DynamicStrategyCatalog;
import com.app.dc.service.simulation.dynamic.DynamicStrategyMeta;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.app.dc.service.simulation.strategy.SymbolStrategyNames;

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
        if (meta.supportedSymbol == null
                && ("ETHUSDT".equalsIgnoreCase(context.symbol) || "SOLUSDT".equalsIgnoreCase(context.symbol)
                || "BTCUSDT".equalsIgnoreCase(context.symbol)))
            return "SYMBOL_STRATEGY_POOL_REPLACED";
        if (meta.supportedSymbol != null && !meta.supportedSymbol.equalsIgnoreCase(context.symbol))
            return "SYMBOL_NOT_SUPPORTED";
        String strategyName=SymbolStrategyNames.baseName(meta.strategyName);
        if ("ethStructuralBullTrend".equalsIgnoreCase(strategyName)
                &&!("ETHUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if ("ethStructuralBearTrend".equalsIgnoreCase(strategyName)
                &&!("ETHUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if ("solStructuralBearTrend".equalsIgnoreCase(strategyName)
                &&!("SOLUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if ("solMomentumBullTrend".equalsIgnoreCase(strategyName)
                &&!("SOLUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if ("solBullLaunchTrend".equalsIgnoreCase(strategyName)
                &&!("SOLUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if ("btcStructuralBullTrend".equalsIgnoreCase(strategyName)
                &&!("BTCUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if (("btcBullLaunchTrend".equalsIgnoreCase(strategyName)||"btcStructuralBearTrend".equalsIgnoreCase(strategyName))
                &&!("BTCUSDT".equalsIgnoreCase(context.symbol)&&"15M".equalsIgnoreCase(context.timeframe)))
            return "SYMBOL_NOT_SUPPORTED";
        if (!strategyProfiles.isStrategyEnabled(context.symbol, context.timeframe,
                meta.strategyName)) return "SYMBOL_STRATEGY_DISABLED";
        if ("emaPullbackBuy".equalsIgnoreCase(strategyName)
                && "ETHUSDT".equalsIgnoreCase(context.symbol)
                && context.structuralTrend != null && context.structuralTrend.ready
                && context.structuralTrend.isBear())
            return "STRUCTURAL_DIRECTION_CONFLICT";
        if ("binanceChannel".equalsIgnoreCase(strategyName)
                && "ETHUSDT".equalsIgnoreCase(context.symbol)) {
            if (!"15M".equalsIgnoreCase(context.timeframe)) return "SYMBOL_NOT_SUPPORTED";
            if (!"UP".equals(context.regime.trend)
                    || !"HIGH".equals(context.regime.volatility))
                return "SYMBOL_REGIME_BLOCKED";
            if (context.structuralTrend != null && context.structuralTrend.ready
                    && context.structuralTrend.isBear())
                return "STRUCTURAL_DIRECTION_CONFLICT";
            if (!(t.ema20 > t.ema60) || !(t.emaSlowSlope > 0) || t.adx < 25)
                return "CHANNEL_TREND_QUALITY_REJECTED";
        }
        if("binanceTrend".equalsIgnoreCase(strategyName)
                &&"UP".equals(context.regime.trend)
                &&!strategyProfiles.binanceTrendBuyEnabled(context.symbol,context.timeframe,meta.strategyName))
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
                && "compressionBreak".equalsIgnoreCase(strategyName);
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
        if(!"binanceTrend".equalsIgnoreCase(SymbolStrategyNames.baseName(meta.strategyName))
                ||!strategyProfiles.binanceTrendSettings(context.symbol,context.timeframe,meta.strategyName).lifecycleEnabled
                ||context.trendLifecycle==null)return false;
        String phase=context.trendLifecycle.phase;
        return !com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot.WARMUP.equals(phase)
                &&!com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot.NEUTRAL.equals(phase)
                &&!com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot.INVALIDATED.equals(phase);
    }

    private boolean bullTrend(StrategyEvaluationContext context,DynamicStrategyMeta meta){
        com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot snapshot;
        String name=SymbolStrategyNames.baseName(meta.strategyName);
        if("ethStructuralBullTrend".equalsIgnoreCase(name))snapshot=context.ethBullTrend;
        else if("btcStructuralBullTrend".equalsIgnoreCase(name))snapshot=context.btcBullTrend;
        else if("solMomentumBullTrend".equalsIgnoreCase(name))snapshot=context.solBullTrend;
        else if("solBullLaunchTrend".equalsIgnoreCase(name))snapshot=context.solBullLaunchTrend;
        else if("btcBullLaunchTrend".equalsIgnoreCase(name))snapshot=context.btcBullLaunchTrend;
        else return false;
        if(snapshot==null||!snapshot.activeCandidate())return false;
        // Launch owns the early 4H transition window; requiring the slower 15m
        // structural state to be BULL here would remove exactly that lead time.
        if("solBullLaunchTrend".equalsIgnoreCase(name)||"btcBullLaunchTrend".equalsIgnoreCase(name))return true;
        if("solMomentumBullTrend".equalsIgnoreCase(name)&&snapshot.actionable
                &&com.app.dc.service.simulation.strategy.trend.bull.SolTrendRearmService.TRIGGER_TYPE.equals(snapshot.triggerType))return true;
        return context.structuralTrend!=null&&context.structuralTrend.ready
                &&context.structuralTrend.isBull();
    }

    private boolean bearTrend(StrategyEvaluationContext context,DynamicStrategyMeta meta){
        String name=SymbolStrategyNames.baseName(meta.strategyName);
        com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot snapshot;
        if("ethStructuralBearTrend".equalsIgnoreCase(name))snapshot=context.ethBearTrend;
        else if("solStructuralBearTrend".equalsIgnoreCase(name))snapshot=context.solBearTrend;
        else if("btcStructuralBearTrend".equalsIgnoreCase(name))snapshot=context.btcBearTrend;
        else return false;
        return snapshot!=null&&snapshot.activeCandidate();
    }

    private boolean finitePositive(double value) { return Double.isFinite(value) && value > 0; }
}
