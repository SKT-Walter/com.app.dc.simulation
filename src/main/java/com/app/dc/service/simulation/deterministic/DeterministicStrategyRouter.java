package com.app.dc.service.simulation.deterministic;

import com.app.dc.strategy.core.routing.DeterministicRoutingEngine;
import com.app.dc.strategy.core.routing.RoutingCandidate;
import com.app.dc.strategy.core.routing.RoutingConfig;
import com.app.dc.strategy.core.routing.RoutingContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Spring adapter around the framework-free routing state machine shared with realtime. */
@Service
public class DeterministicStrategyRouter {
    @Value("${backtest.routing.confirmationBars:3}") private int confirmationBars;
    @Value("${backtest.routing.minimumHoldBars:4}") private int minimumHoldBars;
    @Value("${backtest.routing.switchMargin:5}") private double switchMargin;
    @Value("${backtest.routing.retentionScoreDelta:10}") private double retentionScoreDelta;
    @Value("${backtest.routing.lowScoreConfirmationBars:3}") private int lowScoreConfirmationBars;
    @Value("${backtest.routing.breakoutConfirmationBars:1}") private int breakoutConfirmationBars=1;

    public StrategyRoutingState newState(){return new StrategyRoutingState();}
    public StrategyRoutingDecision route(StrategyRoutingState state,StrategyEvaluationContext context,
            CandidateSelectionResult selection,List<DeterministicScoreCard> scores){return route(state,context,selection,scores,null);}
    public StrategyRoutingDecision route(StrategyRoutingState state,StrategyEvaluationContext context,
            CandidateSelectionResult selection,List<DeterministicScoreCard> scores,String priorityStrategy){
        RoutingConfig cfg=new RoutingConfig();cfg.confirmationBars=confirmationBars;cfg.minimumHoldBars=minimumHoldBars;
        cfg.switchMargin=switchMargin;cfg.retentionScoreDelta=retentionScoreDelta;
        cfg.lowScoreConfirmationBars=lowScoreConfirmationBars;cfg.breakoutConfirmationBars=breakoutConfirmationBars;
        RoutingContext c=new RoutingContext();c.barIndex=context.barIndex;c.barTime=context.regime.barTime;
        c.regime=context.regime.code();c.regimeConfidence=context.regime.confidence;c.priorityStrategy=priorityStrategy;
        if(selection!=null)c.candidateRejections.putAll(selection.rejectedStrategies);
        com.app.dc.strategy.core.routing.RoutingDecision core=new DeterministicRoutingEngine(cfg).route(state.core,c,toCore(scores));
        StrategyRoutingDecision d=new StrategyRoutingDecision();d.barTime=core.barTime;d.regime=core.regime;
        d.regimeConfidence=core.regimeConfidence;d.previousStrategyName=core.previousStrategyName;d.strategyName=core.strategyName;
        d.challengerStrategyName=core.challengerStrategyName;d.activeScore=core.activeScore;d.challengerScore=core.challengerScore;
        d.scoreGap=core.scoreGap;d.reason=core.reason;d.pendingCount=core.pendingCount;
        if(scores!=null)d.scoreCards.addAll(sortedLikeCore(scores,core.scores));d.candidateRejections.putAll(core.candidateRejections);return d;
    }
    private List<RoutingCandidate> toCore(List<DeterministicScoreCard> scores){List<RoutingCandidate> out=new ArrayList<RoutingCandidate>();
        if(scores!=null)for(DeterministicScoreCard s:scores)out.add(new RoutingCandidate(s.strategyName,s.family,s.score,s.minimumScore,s.setupReadiness,s.regimeScore));return out;}
    private List<DeterministicScoreCard> sortedLikeCore(List<DeterministicScoreCard> source,List<RoutingCandidate> order){List<DeterministicScoreCard> out=new ArrayList<DeterministicScoreCard>();
        for(RoutingCandidate c:order)for(DeterministicScoreCard s:source)if(c.strategyName.equalsIgnoreCase(s.strategyName)){out.add(s);break;}return out;}
}
