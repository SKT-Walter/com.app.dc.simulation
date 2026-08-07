package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.Signal;
import com.app.dc.service.simulation.BacktestStrategyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import com.app.dc.po.Side;

/** Sole signal publication gate: only the router's active strategy is evaluated. */
@Service
public class ActiveStrategySignalService {
    @Autowired private BacktestStrategyService strategyService;
    @Autowired private TrendCompressionSignalService trendCompressionSignals;
    @Autowired private SymbolStrategyProfileService strategyProfiles;

    public Signal evaluate(StrategyRoutingState state, StrategyRoutingDecision decision,
                           StrategyEvaluationContext context) {
        if (decision == null || decision.strategyName == null
                || state.activeStrategy() == null
                || !decision.strategyName.equalsIgnoreCase(state.activeStrategy())) return null;
        if ("ACTIVATED".equals(decision.reason) || "SWITCHED".equals(decision.reason)
                || "HARD_INVALID_SWITCHED".equals(decision.reason)
                || "LOW_SCORE_SWITCHED".equals(decision.reason))
            strategyService.resetRuntime(decision.strategyName, context.symbol);
        if ("compressionBreak".equalsIgnoreCase(decision.strategyName)
                && context.trendCompression != null
                && context.trendCompression.triggered) {
            Signal signal = trendCompressionSignals.evaluate(context);
            if (signal != null) return signal;
        }
        Signal signal=strategyService.evaluateSignal(decision.strategyName, context.symbol, context.timeframe,
                context.series, context.currentOhlc);
        if("binanceTrend".equalsIgnoreCase(decision.strategyName)&&signal!=null
                &&signal.side==Side.BUY&&!strategyProfiles.binanceTrendBuyEnabled(
                        context.symbol,context.timeframe))signal.side=Side.NONE;
        if(signal!=null&&signal.side!=null&&signal.side!=Side.NONE
                &&!strategyProfiles.isSideEnabled(context.symbol,context.timeframe,
                decision.strategyName,signal.side.name()))signal.side=Side.NONE;
        return signal;
    }
}
