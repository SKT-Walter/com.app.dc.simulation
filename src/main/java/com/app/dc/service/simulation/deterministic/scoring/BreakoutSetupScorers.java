package com.app.dc.service.simulation.deterministic.scoring;

import com.app.dc.service.simulation.deterministic.StrategyEvaluationContext;
import com.app.dc.service.simulation.deterministic.StrategySetupScore;
import org.springframework.stereotype.Service;

abstract class BreakoutScorer extends AbstractSetupScorer {
    @Override public String family(){return "BREAKOUT";}
}

@Service class BreakoutRetestContinuationSetupScorer extends BreakoutScorer {
    public String strategyName(){return "breakoutRetestContinuation";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.65*breakout(x)+.35*pullback(x.technical),"突破回踩延续");}
}
@Service class CompressionBreakSetupScorer extends BreakoutScorer {
    public String strategyName(){return "compressionBreak";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.55*compression(x.technical)+.45*breakout(x),"压缩后突破");}
}
@Service class BreakoutRetestContinuationTrendSetupScorer extends BreakoutScorer {
    public String strategyName(){return "breakoutRetestContinuationTrend";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.45*breakout(x)+.3*pullback(x.technical)+.25*trendAlignment(x),"顺势突破回踩");}
}
@Service class SmallRangeBreakoutSetupScorer extends BreakoutScorer {
    public String strategyName(){return "smallRangeBreakout";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.65*compression(x.technical)+.35*breakout(x),"小区间突破");}
}

public final class BreakoutSetupScorers { private BreakoutSetupScorers(){} }
