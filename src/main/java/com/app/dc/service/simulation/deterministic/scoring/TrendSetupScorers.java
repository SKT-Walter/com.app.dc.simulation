package com.app.dc.service.simulation.deterministic.scoring;

import com.app.dc.service.simulation.deterministic.StrategyEvaluationContext;
import com.app.dc.service.simulation.deterministic.StrategySetupScore;
import com.app.dc.service.simulation.deterministic.TechnicalSnapshot;
import org.springframework.stereotype.Service;

abstract class TrendScorer extends AbstractSetupScorer {
    @Override public String family(){return "TREND";}
}

@Service class BinanceChannelSetupScorer extends TrendScorer {
    public String strategyName(){return "binanceChannel";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.55*trendAlignment(x)+.45*breakout(x),"趋势通道突破准备");}
}
@Service class BinanceTrendSetupScorer extends TrendScorer {
    public String strategyName(){return "binanceTrend";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.6*trendAlignment(x)+.4*pullback(x.technical),"均线趋势与回踩");}
}
@Service class EmaPullbackBuySetupScorer extends TrendScorer {
    public String strategyName(){return "emaPullbackBuy";}
    public StrategySetupScore score(StrategyEvaluationContext x){double direction="UP".equals(x.regime.trend)?1:0;return result(.45*trendAlignment(x)+.4*pullback(x.technical)+.15*direction,"EMA多头回踩");}
}
@Service class TrendRestartSetupScorer extends TrendScorer {
    public String strategyName(){return "trendRestart";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.45*trendAlignment(x)+.35*pullback(x.technical)+.2*recovery(x.technical),"趋势回踩后重启");}
    private double recovery(TechnicalSnapshot t){return c(.5+t.bodyAtr/2);}
}
@Service class StrongMomentumContinuationSetupScorer extends TrendScorer {
    public String strategyName(){return "strongMomentumContinuation";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.4*trendAlignment(x)+.35*n(x.technical.bodyAtr,.6,1.8)+.25*breakout(x),"强动量延续");}
}
@Service class TrendPullbackRecoverySetupScorer extends TrendScorer {
    public String strategyName(){return "trendPullbackRecovery";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.4*trendAlignment(x)+.35*pullback(x.technical)+.25*reversal(x.technical),"趋势回调恢复");}
}
@Service class BollingerPullbackBiasSetupScorer extends TrendScorer {
    public String strategyName(){return "bollingerPullbackBias";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.5*trendAlignment(x)+.3*pullback(x.technical)+.2*n(Math.abs(x.technical.zScore20),.5,2),"趋势中的布林回踩");}
}
@Service class AtrChannelBiasReversionSetupScorer extends TrendScorer {
    public String strategyName(){return "atrChannelBiasReversion";}
    public StrategySetupScore score(StrategyEvaluationContext x){TechnicalSnapshot t=x.technical;return result(.5*trendAlignment(x)+.3*n(Math.abs(t.close-t.ema20)/Math.max(t.atr,1e-9),.4,1.8)+.2*reversal(t),"趋势偏置ATR通道回归");}
}

public final class TrendSetupScorers { private TrendSetupScorers(){} }
