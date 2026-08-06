package com.app.dc.service.simulation.deterministic.scoring;

import com.app.dc.service.simulation.deterministic.StrategyEvaluationContext;
import com.app.dc.service.simulation.deterministic.StrategySetupScore;
import com.app.dc.service.simulation.deterministic.TechnicalSnapshot;
import com.app.dc.service.simulation.strategy.range.BinanceRangeStateMachine;
import com.app.dc.service.simulation.strategy.range.BinanceRangeStateSnapshot;
import com.app.dc.service.simulation.strategy.range.BinanceRangeSetupAnalyzer;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

abstract class MeanReversionScorer extends AbstractSetupScorer {
    @Override public String family() { return "MEAN_REVERSION"; }
}

@Service class BinanceRangeSetupScorer extends MeanReversionScorer {
    @Autowired private BinanceRangeStateMachine stateMachine;
    @Autowired private SymbolStrategyProfileService strategyProfiles;
    public String strategyName(){return "binanceRange";}
    public StrategySetupScore score(StrategyEvaluationContext x){
        BinanceRangeStateSnapshot setup=stateMachine.evaluate(
                x.symbol,x.timeframe,x.series);
        BinanceRangeSetupAnalyzer.Snapshot preparation=
                BinanceRangeSetupAnalyzer.analyze(x.series,
                        strategyProfiles.minimumTriggerRangeAtr(
                                x.symbol,x.timeframe,strategyName()),
                        strategyProfiles.minimumBuyRecoveryBodyAtr(
                                x.symbol,x.timeframe,strategyName()),
                        strategyProfiles.minimumBuyCloseLocation(
                                x.symbol,x.timeframe,strategyName()));
        double readiness=Math.max(preparation.readiness,setup.readiness);
        return result(readiness,"LEGACY_PREPARATION|"+setup.reason);
    }
}
@Service class BinanceRangeMacdSetupScorer extends MeanReversionScorer {
    public String strategyName(){return "binanceRangeMacd";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.6*rangeEdge(x.technical)+.4*x.technical.macdImprovement,"区间边缘与动量衰减");}
}
@Service class BollingerMeanReversionSetupScorer extends MeanReversionScorer {
    public String strategyName(){return "bollingerMeanReversion";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(n(Math.abs(x.technical.zScore20),1,2.5),"布林带偏离");}
}
@Service class DonchianReversionSetupScorer extends MeanReversionScorer {
    public String strategyName(){return "donchianReversion";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(.7*rangeEdge(x.technical)+.3*reversal(x.technical),"唐奇安边缘反转");}
}
@Service class VwapReversionSetupScorer extends MeanReversionScorer {
    private static final double DEVIATION_THRESHOLD = .0038;
    public String strategyName(){return "vwapReversion";}
    public StrategySetupScore score(StrategyEvaluationContext x){
        TechnicalSnapshot t=x.technical;
        double currentDeviation=(t.close-t.vwap20)/Math.max(t.vwap20,1e-9);
        double previousDeviation=(t.previousClose-t.previousVwap20)/Math.max(t.previousVwap20,1e-9);
        double deviation=n(Math.abs(currentDeviation),DEVIATION_THRESHOLD,DEVIATION_THRESHOLD*2.5);
        boolean recovering=currentDeviation<=-DEVIATION_THRESHOLD
                ? currentDeviation>previousDeviation && t.close>t.previousClose
                    && t.close>t.open && t.low>=t.previousBarLow
                : currentDeviation>=DEVIATION_THRESHOLD
                    && currentDeviation<previousDeviation && t.close<t.previousClose
                    && t.close<t.open && t.high<=t.previousBarHigh;
        return result(.55*deviation+.45*(recovering?1:0),"VWAP偏离后出现方向性回归");
    }
}
@Service class ZScoreReversionSetupScorer extends MeanReversionScorer {
    public String strategyName(){return "zscoreReversion";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(n(Math.abs(x.technical.zScore20),1,2.5),"ZScore偏离");}
}
@Service class AtrChannelReversionSetupScorer extends MeanReversionScorer {
    public String strategyName(){return "atrChannelReversion";}
    public StrategySetupScore score(StrategyEvaluationContext x){TechnicalSnapshot t=x.technical;return result(n(Math.abs(t.close-t.ema20)/Math.max(t.atr,1e-9),.5,2),"ATR通道偏离");}
}
@Service class RsiKdjReversionSetupScorer extends MeanReversionScorer {
    public String strategyName(){return "rsiKdjReversion";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(n(Math.abs(x.technical.rsi14-50),15,30),"RSI极值");}
}
@Service class OrderBookImbalanceReversionSetupScorer extends MeanReversionScorer {
    public String strategyName(){return "orderBookImbalanceReversion";}
    public StrategySetupScore score(StrategyEvaluationContext x){return result(0,"缺少真实盘口数据");}
}
@Service class FailedBreakReversalSetupScorer extends MeanReversionScorer {
    public String strategyName(){return "failedBreakReversal";}
    public StrategySetupScore score(StrategyEvaluationContext x){TechnicalSnapshot t=x.technical;double wick=1-c(t.bodyAtr);return result(.55*rangeEdge(t)+.45*wick,"假突破回归区间");}
}
@Service class ImpulseReclaimSetupScorer extends MeanReversionScorer {
    public String strategyName(){return "impulseReclaim";}
    public StrategySetupScore score(StrategyEvaluationContext x){TechnicalSnapshot t=x.technical;return result(.55*n(t.bodyAtr,.8,2)+.45*reversal(t),"冲击后价格收回");}
}

/** Marker file; concrete package-private scorer classes above are independent Spring components. */
public final class MeanReversionSetupScorers { private MeanReversionSetupScorers(){} }
