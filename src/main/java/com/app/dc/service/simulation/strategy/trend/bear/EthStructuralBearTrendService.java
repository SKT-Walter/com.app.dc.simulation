package com.app.dc.service.simulation.strategy.trend.bear;

import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** ETH 4H bear direction + 1H impulse/pullback + 15m continuation trigger. */
@Service
public class EthStructuralBearTrendService {
    public static final String STRATEGY="ethStructuralBearTrend";
    private static final int TRIGGER_VALIDITY=4,COOLDOWN_BARS=96;
    @Autowired(required=false) private EthBearMultiTimeframeContextService multiTimeframe;
    private final Map<String,EthStructuralBearTrendState> states=new ConcurrentHashMap<String,EthStructuralBearTrendState>();

    public BearTrendSnapshot update(String symbol,String timeframe,BarSeries series,
                                    StructuralTrendSnapshot ignored,BacktestRegime regime){
        EthBearMultiTimeframeSnapshot context=EthBearMultiTimeframeSnapshot.warmup();
        if(multiTimeframe!=null&&series!=null&&series.getBarCount()>0)
            context=multiTimeframe.update(symbol,series.getLastBar().getEndTime().toInstant().toEpochMilli());
        return update(symbol,timeframe,series,regime,context);
    }
    public BearTrendSnapshot update(String symbol,String timeframe,BarSeries series,
                                    BacktestRegime regime,EthBearMultiTimeframeSnapshot context){
        if(!supports(symbol,timeframe)||series==null||series.getBarCount()<60)return BearTrendSnapshot.none(STRATEGY);
        String key=key(symbol,timeframe);EthStructuralBearTrendState s=states.get(key);if(s==null){s=new EthStructuralBearTrendState();states.put(key,s);}
        int end=series.getEndIndex();if(s.lastIndex>end){s=new EthStructuralBearTrendState();states.put(key,s);}if(s.lastIndex==end)return s.snapshot;s.lastIndex=end;
        double atr=BearTrendMath.atr(series,end,14);if(!Double.isFinite(atr)||atr<=0)return cache(s,end,BearTrendSnapshot.INVALIDATED,"ETH_BEAR_INVALID_ATR",0,false,Double.NaN,null);
        if(end<=s.cooldownUntil)return cache(s,end,BearTrendSnapshot.COOLDOWN,"ETH_BEAR_TRADE_COOLDOWN",0,false,Double.NaN,null);
        if(BearTrendSnapshot.RUNNING.equals(s.phase))return cache(s,end,s.phase,"ETH_BEAR_POSITION_RUNNING",.9,false,s.stopPrice,s.triggerType);
        if(BearTrendSnapshot.TRIGGERED.equals(s.phase)){if(!s.consumed&&end<=s.triggerUntil)return cache(s,end,s.phase,"ETH_BEAR_TRIGGER_VALID",1,true,s.stopPrice,s.triggerType);s.observing();}
        if(context==null||EthBearMultiTimeframeSnapshot.WARMUP.equals(context.oneHourPhase)){s.observing();return cache(s,end,BearTrendSnapshot.WARMUP,"ETH_BEAR_MTF_WARMUP",0,false,Double.NaN,null);}
        if(!context.directionAllowsShort()&&!context.fastBearBreakdownActive){s.observing();return cache(s,end,BearTrendSnapshot.OBSERVING,"ETH_BEAR_4H_DIRECTION_BLOCKED",0,false,Double.NaN,null);}
        if(context.fastBearBreakdownActive){
            long fastSetup=-(context.fourHourBarIndex+1L);if(s.setupId!=fastSetup)s.setup(fastSetup);
            return fastTrigger(s,series,end,atr,regime,context);
        }
        if(!context.armed()){s.observing();String p=EthBearMultiTimeframeSnapshot.IMPULSE.equals(context.oneHourPhase)?BearTrendSnapshot.IMPULSE:EthBearMultiTimeframeSnapshot.PULLBACK.equals(context.oneHourPhase)?BearTrendSnapshot.PULLBACK:BearTrendSnapshot.OBSERVING;return cache(s,end,p,context.reason,context.readiness,false,Double.NaN,null);}
        if(s.setupId!=context.setupId)s.setup(context.setupId);return trigger(s,series,end,atr,regime,context);
    }
    private BearTrendSnapshot fastTrigger(EthStructuralBearTrendState s,BarSeries x,int end,double atr,
                                          BacktestRegime regime,EthBearMultiTimeframeSnapshot context){
        double close=BearTrendMath.close(x,end),ema20=BearTrendMath.ema(x,end,20);
        double prior8=BearTrendMath.lowest(x,end-1,8),prior20=BearTrendMath.lowest(x,end-1,20);
        double previousAtr=BearTrendMath.atr(x,end-1,14);
        double body=Math.abs(close-BearTrendMath.open(x,end))/atr;
        double volume=BearTrendMath.volumeRatio(x,end,20),location=BearTrendMath.closeLocation(x,end);
        if(context.campaignDrawdownPct>.22)return cache(s,end,BearTrendSnapshot.ARMED,
                "ETH_BEAR_CAMPAIGN_EXHAUSTION_REJECTED",.70,false,Double.NaN,null);
        boolean establishedCampaign=EthDailyBearContextSnapshot.BEAR.equals(context.dailyState)
                ||EthDailyBearContextSnapshot.BEAR_RISK.equals(context.dailyState);
        boolean regimeOk=regime!=null&&("DOWN".equals(regime.trend)
                ||(establishedCampaign&&"NONE".equals(regime.trend)));
        boolean executionBar=BearTrendMath.bear(x,end)&&close<BearTrendMath.close(x,end-1)
                &&close<(establishedCampaign?prior8:prior20)
                &&body>=(establishedCampaign?.25:.60)&&body<=1.8
                &&volume>=(establishedCampaign?.75:1.20)
                &&location<=(establishedCampaign?.40:.25);
        double stableAtr=Double.isFinite(previousAtr)&&previousAtr>0?previousAtr:atr;
        double extension=(ema20-close)/stableAtr,recentDecline=(BearTrendMath.highest(x,end-1,12)-close)/stableAtr;
        double oneHourExtension=Double.isFinite(context.oneHourAtr)&&context.oneHourAtr>0
                ?(context.oneHourEma20-context.oneHourClose)/context.oneHourAtr:0;
        double fourHourExtension=Double.isFinite(context.fourHourAtr)&&context.fourHourAtr>0
                ?(context.fourHourEma20-context.fourHourClose)/context.fourHourAtr:0;
        boolean failedHighCampaign=EthDailyBearContextSnapshot.BEAR_RISK.equals(context.dailyState)
                &&context.dailyConfidence>=.75;
        double maxRecentDecline=failedHighCampaign?8.0:5.0;
        double maxOneHourExtension=failedHighCampaign?3.0:1.75;
        double maxFourHourExtension=failedHighCampaign?4.0:2.25;
        if(recentDecline>maxRecentDecline||oneHourExtension>maxOneHourExtension
                ||fourHourExtension>maxFourHourExtension)return cache(s,end,BearTrendSnapshot.ARMED,
                "ETH_FAST_BEAR_EXHAUSTION_REJECTED",.85,false,Double.NaN,null);
        if(!regimeOk||!executionBar||extension>3.0)return cache(s,end,BearTrendSnapshot.ARMED,
                "ETH_FAST_BEAR_WAITING_MOMENTUM",.85,false,Double.NaN,null);
        double structuralStop=Math.max(BearTrendMath.highest(x,end,3),ema20)+.5*atr;
        double distance=structuralStop-close;if(distance>4*atr)return cache(s,end,BearTrendSnapshot.INVALIDATED,
                "ETH_FAST_BEAR_STOP_TOO_WIDE",0,false,Double.NaN,null);
        boolean confirmedDailyBear=EthDailyBearContextSnapshot.BEAR.equals(context.dailyState);
        boolean failedHighEntry=EthDailyBearContextSnapshot.BEAR_RISK.equals(context.dailyState)
                &&context.dailyConfidence>=.75;
        double oneHourProtection=Double.isFinite(context.oneHourEma20)&&Double.isFinite(context.oneHourAtr)
                ?context.oneHourEma20+context.oneHourAtr:Double.NaN;
        double fourHourProtection=(confirmedDailyBear||failedHighEntry)&&Double.isFinite(context.fourHourEma20)
                &&Double.isFinite(context.fourHourAtr)?context.fourHourEma20+context.fourHourAtr:Double.NaN;
        double riskLimit=(confirmedDailyBear||failedHighEntry)?.11:.06;
        s.softStopPrice=Math.max(structuralStop,close+2*atr);
        if(Double.isFinite(oneHourProtection))s.softStopPrice=Math.max(s.softStopPrice,oneHourProtection);
        if(Double.isFinite(fourHourProtection))s.softStopPrice=Math.max(s.softStopPrice,fourHourProtection);
        if(s.softStopPrice>close*(1+riskLimit))return cache(s,end,BearTrendSnapshot.INVALIDATED,
                "ETH_FAST_BEAR_PRICE_RISK_TOO_WIDE",0,false,Double.NaN,null);
        s.stopPrice=Math.min(Math.max(s.softStopPrice,close+3*atr),close*(1+riskLimit));
        double entryRisk=s.stopPrice-close;
        // Nearby historical pivots are not reliable take-profit ceilings once a
        // daily/4H campaign has already broken structure.  Keep this defensive
        // check only while the daily chart is still merely weakening.
        if(EthDailyBearContextSnapshot.BULL_WEAKENING.equals(context.dailyState)
                &&Double.isFinite(context.fourHourSupportBelow)){
            double minimumRewardRisk=EthDailyBearContextSnapshot.BULL_WEAKENING.equals(context.dailyState)?3.0:2.5;
            if(close-context.fourHourSupportBelow<minimumRewardRisk*entryRisk)
                return cache(s,end,BearTrendSnapshot.ARMED,"ETH_FAST_BEAR_INSUFFICIENT_DOWNSIDE_ROOM",.85,false,Double.NaN,null);
        }
        s.phase=BearTrendSnapshot.TRIGGERED;s.triggerUntil=end+TRIGGER_VALIDITY;
        s.triggerType=failedHighEntry?"FAILED_HIGH_BEAR_BREAKDOWN":"FAST_BEAR_BREAKDOWN";s.consumed=false;
        return cache(s,end,s.phase,"ETH_FAST_BEAR_BREAKDOWN_TRIGGERED",1,true,s.stopPrice,s.triggerType);
    }
    private BearTrendSnapshot trigger(EthStructuralBearTrendState s,BarSeries x,int end,double atr,BacktestRegime regime,EthBearMultiTimeframeSnapshot context){
        s.tacticalBars++;double high=BearTrendMath.high(x,end),ema20=BearTrendMath.ema(x,end,20),close=BearTrendMath.close(x,end);
        if(close<ema20)s.belowEmaBars++;else s.belowEmaBars=0;
        if(end>=x.getBeginIndex()+2){double left=BearTrendMath.high(x,end-2),pivot=BearTrendMath.high(x,end-1),right=high;if(pivot>left&&pivot>right&&pivot<context.pullbackHigh-.10*atr){s.lowerHighConfirmed=true;s.lowerHigh=pivot;}}
        double prior8=BearTrendMath.lowest(x,end-1,8),extension=(ema20-close)/atr;
        boolean defensive=EthDailyBearContextSnapshot.BULL_WEAKENING.equals(context.dailyState);
        boolean bearRisk=EthDailyBearContextSnapshot.BEAR_RISK.equals(context.dailyState);
        boolean regimeOk=regime!=null&&(defensive?"DOWN".equals(regime.trend):
                ("DOWN".equals(regime.trend)||"NONE".equals(regime.trend)));
        double recentDecline=(BearTrendMath.highest(x,end,8)-close)/atr;
        if(recentDecline>3)return cache(s,end,BearTrendSnapshot.ARMED,"ETH_BEAR_EXHAUSTION_REJECTED",context.readiness,false,Double.NaN,null);
        double minVolume=defensive?1.0:bearRisk?.9:.8;
        double maxExtension=defensive?1.0:bearRisk?1.25:1.75;
        boolean quality=context.oneHourContinuationConfirmed&&s.tacticalBars>=3&&s.lowerHighConfirmed&&s.belowEmaBars>=2&&regimeOk
                &&BearTrendMath.bear(x,end)&&close<BearTrendMath.close(x,end-1)&&close<prior8
                &&BearTrendMath.closeLocation(x,end)<=.35&&BearTrendMath.volumeRatio(x,end,20)>=minVolume&&extension<=maxExtension;
        if(!quality)return cache(s,end,BearTrendSnapshot.ARMED,"ETH_BEAR_15M_WAITING_CONTINUATION",context.readiness,false,Double.NaN,null);
        double structuralStop=s.lowerHigh+.5*atr,distance=structuralStop-close;
        if(distance>4*atr)return cache(s,end,BearTrendSnapshot.INVALIDATED,"ETH_BEAR_STOP_TOO_WIDE",0,false,Double.NaN,null);
        if(context.campaignDrawdownPct>.22)return cache(s,end,BearTrendSnapshot.ARMED,
                "ETH_BEAR_CAMPAIGN_EXHAUSTION_REJECTED",.70,false,Double.NaN,null);
        s.softStopPrice=Math.max(structuralStop,close+2*atr);double disaster=3*atr;
        if(Double.isFinite(context.oneHourAtr)&&context.oneHourAtr>0)disaster=Math.max(disaster,2*context.oneHourAtr);
        disaster=Math.min(disaster,4*atr);
        s.stopPrice=Math.min(Math.max(s.softStopPrice,close+disaster),close+4*atr);
        double entryRisk=s.stopPrice-close;
        if(Double.isFinite(context.fourHourSupportBelow)){
            double downsideRoom=close-context.fourHourSupportBelow;
            double minimumRewardRisk=defensive?3.0:bearRisk?2.75:2.5;
            if(downsideRoom<minimumRewardRisk*entryRisk)
                return cache(s,end,BearTrendSnapshot.ARMED,"ETH_BEAR_INSUFFICIENT_DOWNSIDE_ROOM",context.readiness,false,Double.NaN,null);
        }
        s.phase=BearTrendSnapshot.TRIGGERED;s.triggerUntil=end+TRIGGER_VALIDITY;
        s.triggerType="ETH_4H_1H_15M_BEAR_CONTINUATION";s.consumed=false;
        return cache(s,end,s.phase,"ETH_BEAR_MULTITIMEFRAME_TRIGGERED",1,true,s.stopPrice,s.triggerType);
    }
    public BearTrendSnapshot current(String symbol,String timeframe){EthStructuralBearTrendState s=states.get(key(symbol,timeframe));return s==null?BearTrendSnapshot.none(STRATEGY):s.snapshot;}
    public double currentSoftStop(String symbol,String timeframe){EthStructuralBearTrendState s=states.get(key(symbol,timeframe));return s==null?Double.NaN:s.softStopPrice;}
    public void consume(String symbol,String timeframe,int bar){EthStructuralBearTrendState s=states.get(key(symbol,timeframe));if(s!=null&&s.lastIndex==bar&&BearTrendSnapshot.TRIGGERED.equals(s.phase)){s.consumed=true;s.phase=BearTrendSnapshot.RUNNING;if(multiTimeframe!=null)multiTimeframe.consume(symbol,s.setupId);s.snapshot=new BearTrendSnapshot(STRATEGY,s.phase,"ETH_BEAR_SIGNAL_CONSUMED",.9,false,s.stopPrice,s.triggerType,bar);}}
    public void tradeClosed(String symbol,int exit,TradeRecord trade){boolean reentry=profitProtected(trade);for(Map.Entry<String,EthStructuralBearTrendState>e:states.entrySet())if(e.getKey().startsWith(symbol.toUpperCase()+"|")){EthStructuralBearTrendState s=e.getValue();s.observing();s.cooldownUntil=exit+(reentry?48:COOLDOWN_BARS);}if(multiTimeframe!=null)multiTimeframe.tradeClosed(symbol,reentry);}
    public void reset(String symbol){String p=symbol==null?"":symbol.toUpperCase()+"|";states.keySet().removeIf(k->k.startsWith(p));if(multiTimeframe!=null)multiTimeframe.reset(symbol);}
    private BearTrendSnapshot cache(EthStructuralBearTrendState s,int end,String phase,String reason,double ready,boolean action,double stop,String trigger){s.phase=phase;s.snapshot=new BearTrendSnapshot(STRATEGY,phase,reason,ready,action,stop,trigger,end);return s.snapshot;}
    private boolean profitProtected(TradeRecord trade){if(trade==null)return false;if(trade.pnl!=null&&trade.pnl.signum()>0)return true;
        if(trade.exitReason==null)return false;String r=trade.exitReason.toLowerCase();return r.contains("mfe_capture")||r.contains("staged_profit_lock")||r.contains("chandelier");}
    private boolean supports(String s,String t){return "ETHUSDT".equalsIgnoreCase(s)&&"15M".equalsIgnoreCase(t);}private String key(String s,String t){return (s==null?"":s.toUpperCase())+"|"+(t==null?"":t.toUpperCase());}
}
