package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.Signal;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.dynamic.BacktestRegimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.List;

/** One deterministic, side-effect-bounded pass for each closed bar. */
@Service
public class DeterministicBacktestPipeline {
    @Autowired private BacktestRegimeService regimeService;
    @Autowired private MarketContextFactory contextFactory;
    @Autowired private CandidateRuleChain candidateRules;
    @Autowired private DeterministicScoringService scoringService;
    @Autowired private DeterministicStrategyRouter router;
    @Autowired private ActiveStrategySignalService signalService;
    @Autowired private StructuralTrendService structuralTrendService;

    public DeterministicPipelineState newState() {
        return new DeterministicPipelineState(router.newState(), structuralTrendService.newState());
    }

    public DeterministicPipelineResult onClosedBar(DeterministicPipelineState state, String symbol,
                                                   String timeframe, BarSeries series,
                                                   TTbookOhlc ohlc) {
        BacktestRegime regime = regimeService.identify(series);
        StructuralTrendSnapshot structural = structuralTrendService.update(
                state.structuralTrendState, series);
        StrategyEvaluationContext context = contextFactory.create(
                symbol, timeframe, series, ohlc, regime, structural);
        CandidateSelectionResult candidates = candidateRules.select(context);
        List<DeterministicScoreCard> scores = scoringService.score(context, candidates);
        StrategyRoutingDecision decision = router.route(state.routerState, context, candidates, scores);
        DeterministicScoreCard selected = selectedScore(decision, scores);
        Signal baselineSignal = signalService.evaluate(state.routerState, decision, context);
        boolean algorithmActionable = baselineSignal != null && baselineSignal.side != null
                && baselineSignal.side != com.app.dc.po.Side.NONE;

        DeterministicPipelineResult result = new DeterministicPipelineResult();
        result.context = context;
        result.candidates = candidates;
        result.routingDecision = decision;
        result.structuralTrend = structural;
        result.signal = baselineSignal;
        result.signalSource = algorithmActionable ? "BASELINE" : "NONE";
        result.executionStrategyName = decision.strategyName;
        decision.structuralTrend = structural.direction;
        decision.structuralPhase = structural.phase;
        decision.structuralConfidence = structural.confidence;
        decision.executionStrategyName = result.executionStrategyName;
        decision.signalSource = result.signalSource;
        if (selected != null) {
            decision.structuralScoreAdjustment = selected.structuralAdjustment;
            decision.structuralScoreReason = selected.structuralAdjustmentReason;
        } else {
            decision.structuralScoreReason = "NO_SELECTED_STRATEGY";
        }
        return result;
    }

    private DeterministicScoreCard selectedScore(StrategyRoutingDecision decision,
                                                 List<DeterministicScoreCard> scores) {
        if (decision == null || scores == null) return null;
        String name = decision.strategyName != null
                ? decision.strategyName : decision.challengerStrategyName;
        if (name == null) return null;
        for (DeterministicScoreCard score : scores)
            if (name.equalsIgnoreCase(score.strategyName)) return score;
        return null;
    }
}
