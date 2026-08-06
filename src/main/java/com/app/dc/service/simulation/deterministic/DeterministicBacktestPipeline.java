package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.Signal;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.dynamic.BacktestRegimeService;
import com.app.dc.service.simulation.strategy.range.BinanceRangeStateMachine;
import com.app.dc.service.simulation.strategy.trend.TrendLifecycleService;
import com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.EthStructuralBullTrendService;
import com.app.dc.service.simulation.strategy.trend.bull.SolMomentumBullTrendService;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.EthStructuralBearTrendService;
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
    @Autowired private TrendCompressionService trendCompressionService;
    @Autowired private BinanceRangeStateMachine binanceRangeStateMachine;
    @Autowired private TrendLifecycleService trendLifecycleService;
    @Autowired private EthStructuralBullTrendService ethBullTrendService;
    @Autowired private SolMomentumBullTrendService solBullTrendService;
    @Autowired private EthStructuralBearTrendService ethBearTrendService;
    @Autowired private LifecycleTrendPriorityPolicy lifecyclePriority;

    public DeterministicPipelineState newState() {
        return new DeterministicPipelineState(router.newState(),
                structuralTrendService.newState(), trendCompressionService.newState());
    }

    public DeterministicPipelineResult onClosedBar(DeterministicPipelineState state, String symbol,
                                                   String timeframe, BarSeries series,
                                                   TTbookOhlc ohlc) {
        return onClosedBar(state,symbol,timeframe,series,ohlc,null);
    }

    public DeterministicPipelineResult onClosedBar(DeterministicPipelineState state, String symbol,
                                                   String timeframe, BarSeries series,
                                                   TTbookOhlc ohlc,String positionOwner) {
        BacktestRegime regime = regimeService.identify(series);
        StructuralTrendSnapshot structural = structuralTrendService.update(
                state.structuralTrendState, series);
        StrategyEvaluationContext baseContext = contextFactory.create(
                symbol, timeframe, series, ohlc, regime, structural);
        TrendCompressionSnapshot trendCompression = trendCompressionService.update(
                state.trendCompressionState, baseContext);
        TrendLifecycleSnapshot trendLifecycle=trendLifecycleService.update(
                symbol,timeframe,series,structural,regime);
        BullTrendSnapshot ethBullTrend=ethBullTrendService.update(symbol,timeframe,series,structural,regime);
        BullTrendSnapshot solBullTrend=solBullTrendService.update(symbol,timeframe,series,structural,regime);
        BearTrendSnapshot ethBearTrend=ethBearTrendService.update(symbol,timeframe,series,structural,regime);
        StrategyEvaluationContext context = new StrategyEvaluationContext(
                baseContext.symbol, baseContext.timeframe, baseContext.barIndex,
                baseContext.series, baseContext.currentOhlc, baseContext.regime,
                baseContext.technical, structural, trendCompression,trendLifecycle,
                ethBullTrend,solBullTrend,ethBearTrend);
        // Range scene memory is market context and must advance on every closed bar.
        // Candidate rules decide execution eligibility; they do not pause scene time.
        binanceRangeStateMachine.evaluate(symbol, timeframe, series);
        CandidateSelectionResult candidates = candidateRules.select(context);
        List<DeterministicScoreCard> scores = scoringService.score(context, candidates);
        String priority=lifecyclePriority.priorityStrategy(context,scores,positionOwner);
        StrategyRoutingDecision decision = router.route(state.routerState, context, candidates, scores,priority);
        DeterministicScoreCard selected = selectedScore(decision, scores);
        Signal baselineSignal = signalService.evaluate(state.routerState, decision, context);
        boolean algorithmActionable = baselineSignal != null && baselineSignal.side != null
                && baselineSignal.side != com.app.dc.po.Side.NONE;

        DeterministicPipelineResult result = new DeterministicPipelineResult();
        result.context = context;
        result.candidates = candidates;
        result.routingDecision = decision;
        result.structuralTrend = structural;
        result.trendLifecycle=trendLifecycle;
        result.ethBullTrend=ethBullTrend;
        result.solBullTrend=solBullTrend;
        result.ethBearTrend=ethBearTrend;
        result.signal = baselineSignal;
        result.signalSource = algorithmActionable ? "BASELINE" : "NONE";
        result.executionStrategyName = decision.strategyName;
        decision.structuralTrend = structural.direction;
        decision.structuralPhase = structural.phase;
        decision.structuralConfidence = structural.confidence;
        decision.executionStrategyName = result.executionStrategyName;
        decision.signalSource = result.signalSource;
        decision.trendCompressionPhase = trendCompression.phase;
        decision.trendCompressionDirection = trendCompression.direction;
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
