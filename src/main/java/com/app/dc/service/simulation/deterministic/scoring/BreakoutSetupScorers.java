package com.app.dc.service.simulation.deterministic.scoring;

import com.app.dc.service.simulation.deterministic.StrategyEvaluationContext;
import com.app.dc.service.simulation.deterministic.StrategySetupScore;
import org.springframework.stereotype.Service;

abstract class BreakoutScorer extends AbstractSetupScorer {
    @Override public String family(){return "BREAKOUT";}
}

@Service class BreakoutRetestContinuationSetupScorer extends BreakoutScorer {
    public String strategyName(){return "breakoutRetestContinuation";}
    public StrategySetupScore score(StrategyEvaluationContext x){
        return result(.65*breakout(x)+.35*pullback(x.technical),
                "BREAKOUT_RETEST_CONTINUATION");
    }
}
@Service class CompressionBreakSetupScorer extends BreakoutScorer {
    public String strategyName(){return "compressionBreak";}
    public StrategySetupScore score(StrategyEvaluationContext x){
        if(x.trendCompression!=null&&x.trendCompression.triggered)
            return result(1,"TREND_COMPRESSION_EXPANSION");
        return result(.55*compression(x.technical)+.45*breakout(x),
                "COMPRESSION_BREAKOUT");
    }
}
@Service class BreakoutRetestContinuationTrendSetupScorer extends BreakoutScorer {
    public String strategyName(){return "breakoutRetestContinuationTrend";}
    public StrategySetupScore score(StrategyEvaluationContext x){
        return result(.45*breakout(x)+.3*pullback(x.technical)+.25*trendAlignment(x),
                "TREND_ALIGNED_BREAKOUT_RETEST");
    }
}
@Service class SmallRangeBreakoutSetupScorer extends BreakoutScorer {
    public String strategyName(){return "smallRangeBreakout";}
    public StrategySetupScore score(StrategyEvaluationContext x){
        return result(.65*compression(x.technical)+.35*breakout(x),
                "SMALL_RANGE_BREAKOUT");
    }
}

public final class BreakoutSetupScorers { private BreakoutSetupScorers(){} }
