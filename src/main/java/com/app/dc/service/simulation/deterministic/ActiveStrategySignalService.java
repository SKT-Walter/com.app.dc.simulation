package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.Signal;
import com.app.dc.service.simulation.BacktestStrategyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Sole signal publication gate: only the router's active strategy is evaluated. */
@Service
public class ActiveStrategySignalService {
    @Autowired private BacktestStrategyService strategyService;

    public Signal evaluate(StrategyRoutingState state, StrategyRoutingDecision decision,
                           StrategyEvaluationContext context) {
        if (decision == null || decision.strategyName == null
                || state.activeStrategy() == null
                || !decision.strategyName.equalsIgnoreCase(state.activeStrategy())) return null;
        if ("ACTIVATED".equals(decision.reason) || "SWITCHED".equals(decision.reason)
                || "HARD_INVALID_SWITCHED".equals(decision.reason)
                || "LOW_SCORE_SWITCHED".equals(decision.reason))
            strategyService.resetRuntime(decision.strategyName, context.symbol);
        return strategyService.evaluateSignal(decision.strategyName, context.symbol, context.timeframe,
                context.series, context.currentOhlc);
    }
}
