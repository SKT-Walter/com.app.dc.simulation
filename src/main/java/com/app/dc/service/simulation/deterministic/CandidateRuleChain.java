package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.dynamic.DynamicStrategyCatalog;
import com.app.dc.service.simulation.dynamic.DynamicStrategyMeta;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Ordered hard-admission rules. Scores can never restore a rejected strategy. */
@Service
public class CandidateRuleChain {
    @Autowired private DynamicStrategyCatalog catalog;
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
        if (!supportsRegime(meta, context.regime.code())) return "UNSUPPORTED_REGIME";
        if ("orderBookImbalanceReversion".equalsIgnoreCase(meta.strategyName))
            return "MISSING_ORDER_BOOK_FEATURE";
        if (meta.requiredFeatures != null) {
            for (String feature : meta.requiredFeatures)
                if (context.regime.features == null || !context.regime.features.containsKey(feature)
                        || context.regime.features.get(feature) == null
                        || !Double.isFinite(context.regime.features.get(feature)))
                    return "MISSING_REQUIRED_FEATURE:" + feature;
        }
        if ("BREAKOUT".equalsIgnoreCase(meta.family) && !context.regime.breakoutExpansion)
            return "BREAKOUT_EXPANSION_REQUIRED";
        if ("TREND".equalsIgnoreCase(meta.family) && "NONE".equals(context.regime.trend))
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

    private boolean finitePositive(double value) { return Double.isFinite(value) && value > 0; }
}
