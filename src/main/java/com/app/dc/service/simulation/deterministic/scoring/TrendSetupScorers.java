package com.app.dc.service.simulation.deterministic.scoring;

import com.app.dc.service.simulation.deterministic.StrategyEvaluationContext;
import com.app.dc.service.simulation.deterministic.StrategySetupScore;
import com.app.dc.service.simulation.deterministic.TechnicalSnapshot;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import com.app.dc.service.simulation.strategy.trend.BinanceTrendSettings;
import com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

abstract class TrendScorer extends AbstractSetupScorer { public String family(){return "TREND";} }
@Service class BinanceChannelSetupScorer extends TrendScorer {
 public String strategyName(){return "binanceChannel";} public StrategySetupScore score(StrategyEvaluationContext x){
  if(!"ETHUSDT".equalsIgnoreCase(x.symbol))return result(.55*trendAlignment(x)+.45*breakout(x),"趋势通道突破准备");
  TechnicalSnapshot t=x.technical;double atr=Math.max(t.atr,1e-9);
  double breakoutAtr=Math.max(0,(t.close-t.previousHigh)/atr);
  double bodyQuality=breakoutAtr<=0?n(t.bodyAtr,.15,.50):t.bodyAtr<=.70?n(t.bodyAtr,.35,.70):c(1-(t.bodyAtr-.70)/.50);
  double extensionQuality=breakoutAtr<=1?1:c(1-(breakoutAtr-1)/.50);
  double readiness=.25*trendAlignment(x)+.20*n(breakoutAtr,.05,.60)
    +.15*bodyQuality+.15*n(t.volumeRatio,.80,1.50)+.10*n(t.closeLocation,.55,.80)
    +.10*n(t.adx,20,40)+.05*extensionQuality;
  return result(readiness,"ETH高波动通道突破质量");}}
@Service class BinanceTrendSetupScorer extends TrendScorer {
 @Autowired private SymbolStrategyProfileService profiles;
 public String strategyName(){return "binanceTrend";}
 public StrategySetupScore score(StrategyEvaluationContext x){
  BinanceTrendSettings settings=profiles.binanceTrendSettings(x.symbol,x.timeframe);
  if(!settings.lifecycleEnabled)return result(.6*trendAlignment(x)+.4*pullback(x.technical),"均线趋势与回调");
  TrendLifecycleSnapshot s=x.trendLifecycle;double readiness=s==null?0:s.readiness;
  if(s!=null&&s.actionable){boolean matches=("UP".equals(x.regime.trend)&&"BUY".equals(s.signalSide()))
    ||("DOWN".equals(x.regime.trend)&&"SELL".equals(s.signalSide()))||"NONE".equals(x.regime.trend);
   if(!matches)readiness=Math.min(readiness,.35);}
  return result(readiness,"ETH趋势生命周期|"+(s==null?"NONE":s.reason));}}
@Service class EmaPullbackBuySetupScorer extends TrendScorer {
 public String strategyName(){return "emaPullbackBuy";} public StrategySetupScore score(StrategyEvaluationContext x){double d="UP".equals(x.regime.trend)?1:0;return result(.45*trendAlignment(x)+.4*pullback(x.technical)+.15*d,"EMA多头回踩");}}
@Service class TrendRestartSetupScorer extends TrendScorer {
 public String strategyName(){return "trendRestart";} public StrategySetupScore score(StrategyEvaluationContext x){return result(.45*trendAlignment(x)+.35*pullback(x.technical)+.2*recovery(x.technical),"趋势回调后重启");} private double recovery(TechnicalSnapshot t){return c(.5+t.bodyAtr/2);}}
@Service class StrongMomentumContinuationSetupScorer extends TrendScorer {
 public String strategyName(){return "strongMomentumContinuation";} public StrategySetupScore score(StrategyEvaluationContext x){return result(.4*trendAlignment(x)+.35*n(x.technical.bodyAtr,.6,1.8)+.25*breakout(x),"强动量延续");}}
@Service class TrendPullbackRecoverySetupScorer extends TrendScorer {
 public String strategyName(){return "trendPullbackRecovery";} public StrategySetupScore score(StrategyEvaluationContext x){return result(.4*trendAlignment(x)+.35*pullback(x.technical)+.25*reversal(x.technical),"趋势回调恢复");}}
@Service class BollingerPullbackBiasSetupScorer extends TrendScorer {
 public String strategyName(){return "bollingerPullbackBias";} public StrategySetupScore score(StrategyEvaluationContext x){return result(.5*trendAlignment(x)+.3*pullback(x.technical)+.2*n(Math.abs(x.technical.zScore20),.5,2),"趋势中的布林回踩");}}
@Service class AtrChannelBiasReversionSetupScorer extends TrendScorer {
 public String strategyName(){return "atrChannelBiasReversion";} public StrategySetupScore score(StrategyEvaluationContext x){TechnicalSnapshot t=x.technical;return result(.5*trendAlignment(x)+.3*n(Math.abs(t.close-t.ema20)/Math.max(t.atr,1e-9),.4,1.8)+.2*reversal(t),"趋势偏置ATR通道回归");}}
@Service class EthStructuralBullTrendSetupScorer extends TrendScorer {
 public String strategyName(){return "ethStructuralBullTrend";} public StrategySetupScore score(StrategyEvaluationContext x){BullTrendSnapshot s=x.ethBullTrend;return result(s==null?0:s.readiness,"ETH结构多头|"+(s==null?"NONE":s.reason));}}
@Service class SolMomentumBullTrendSetupScorer extends TrendScorer {
 public String strategyName(){return "solMomentumBullTrend";} public StrategySetupScore score(StrategyEvaluationContext x){BullTrendSnapshot s=x.solBullTrend;return result(s==null?0:s.readiness,"SOL压缩多头|"+(s==null?"NONE":s.reason));}}
@Service class EthStructuralBearTrendSetupScorer extends TrendScorer {
 public String strategyName(){return "ethStructuralBearTrend";} public StrategySetupScore score(StrategyEvaluationContext x){BearTrendSnapshot s=x.ethBearTrend;return result(s==null?0:s.readiness,"ETH structural bear|"+(s==null?"NONE":s.reason));}}
public final class TrendSetupScorers {private TrendSetupScorers(){}}
