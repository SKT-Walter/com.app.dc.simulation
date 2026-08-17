package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.strategy.trend.bull.EthMultiTimeframeSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.SolMultiTimeframeContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** SOL-only higher-timeframe admission for actual EMA pullback BUY signals. */
@Service
public class SolEmaPullbackAdmissionService {
    @Value("${backtest.sol-ema-pullback.mtf-admission-enabled:true}")
    private boolean enabled;
    @Value("${backtest.sol-ema-pullback.require-positive-one-hour-slope:false}")
    private boolean requirePositiveOneHourSlope;
    @Value("${backtest.sol-ema-pullback.require-bull-structure:false}")
    private boolean requireBullStructure;
    @Autowired(required = false) private SolMultiTimeframeContextService multiTimeframe;

    public String rejection(StrategyEvaluationContext context) {
        if (!enabled || context == null || !"SOLUSDT".equalsIgnoreCase(context.symbol))
            return null;
        EthMultiTimeframeSnapshot mtf = multiTimeframe == null
                ? EthMultiTimeframeSnapshot.warmup()
                : multiTimeframe.current(context.symbol);
        if (EthMultiTimeframeSnapshot.WARMUP.equals(mtf.oneHourPhase))
            return "SOL_EMA_PULLBACK_MTF_WARMUP";
        if (EthMultiTimeframeSnapshot.FOUR_HOUR_BEAR.equals(mtf.fourHourTrend))
            return "SOL_EMA_PULLBACK_4H_BEAR";
        if (requirePositiveOneHourSlope
                && (!Double.isFinite(mtf.oneHourEma20Slope) || mtf.oneHourEma20Slope <= 0))
            return "SOL_EMA_PULLBACK_1H_SLOPE_NOT_POSITIVE";
        StructuralTrendSnapshot structural = context.structuralTrend;
        if (requireBullStructure
                && (structural == null || !structural.ready || !structural.isBull()))
            return "SOL_EMA_PULLBACK_STRUCTURE_NOT_BULL";
        return null;
    }
}
