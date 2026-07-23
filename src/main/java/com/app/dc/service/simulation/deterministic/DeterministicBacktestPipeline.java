package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.TTbookOhlc;
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

    public StrategyRoutingState newState() { return router.newState(); }

    public DeterministicPipelineResult onClosedBar(StrategyRoutingState state, String symbol,
                                                   String timeframe, BarSeries series,
                                                   TTbookOhlc ohlc) {
        BacktestRegime regime = regimeService.identify(series);
        StrategyEvaluationContext context = contextFactory.create(symbol, timeframe, series, ohlc, regime);
        CandidateSelectionResult candidates = candidateRules.select(context);
        List<DeterministicScoreCard> scores = scoringService.score(context, candidates);
        StrategyRoutingDecision decision = router.route(state, context, candidates, scores);
        DeterministicPipelineResult result = new DeterministicPipelineResult();
        result.context = context;
        result.candidates = candidates;
        result.routingDecision = decision;
        result.signal = signalService.evaluate(state, decision, context);
        return result;
    }
}
