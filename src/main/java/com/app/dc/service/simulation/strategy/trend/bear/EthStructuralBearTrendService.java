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
        if(!context.directionAllowsShort()){s.observing();return cache(s,end,BearTrendSnapshot.OBSERVING,"ETH_BEAR_4H_DIRECTION_BLOCKED",0,false,Double.NaN,null);}
        if(!context.armed()){s.observing();String p=EthBearMultiTimeframeSnapshot.IMPULSE.equals(context.oneHourPhase)?BearTrendSnapshot.IMPULSE:EthBearMultiTimeframeSnapshot.PULLBACK.equals(context.oneHourPhase)?BearTrendSnapshot.PULLBACK:BearTrendSnapshot.OBSERVING;return cache(s,end,p,context.reason,context.readiness,false,Double.NaN,null);}
        if(s.setupId!=context.setupId)s.setup(context.setupId);return trigger(s,series,end,atr,regime,context);
    }
    private BearTrendSnapshot trigger(EthStructuralBearTrendState s,BarSeries x,int end,double atr,BacktestRegime regime,EthBearMultiTimeframeSnapshot context){
        s.tacticalBars++;double high=BearTrendMath.high(x,end),ema20=BearTrendMath.ema(x,end,20),close=BearTrendMath.close(x,end);
        if(close<ema20)s.belowEmaBars++;else s.belowEmaBars=0;
        if(end>=x.getBeginIndex()+2){double left=BearTrendMath.high(x,end-2),pivot=BearTrendMath.high(x,end-1),right=high;if(pivot>left&&pivot>right&&pivot<context.pullbackHigh-.10*atr){s.lowerHighConfirmed=true;s.lowerHigh=pivot;}}
        double prior8=BearTrendMath.lowest(x,end-1,8),extension=(ema20-close)/atr;
        boolean regimeOk=regime!=null&&("DOWN".equals(regime.trend)||"NONE".equals(regime.trend));
        double recentDecline=(BearTrendMath.highest(x,end,8)-close)/atr;
        if(recentDecline>3)return cache(s,end,BearTrendSnapshot.ARMED,"ETH_BEAR_EXHAUSTION_REJECTED",context.readiness,false,Double.NaN,null);
        double minVolume=.8,maxExtension=1.75;
        boolean quality=context.oneHourContinuationConfirmed&&s.tacticalBars>=3&&s.lowerHighConfirmed&&s.belowEmaBars>=2&&regimeOk
                &&BearTrendMath.bear(x,end)&&close<BearTrendMath.close(x,end-1)&&close<prior8
                &&BearTrendMath.closeLocation(x,end)<=.35&&BearTrendMath.volumeRatio(x,end,20)>=minVolume&&extension<=maxExtension;
        if(!quality)return cache(s,end,BearTrendSnapshot.ARMED,"ETH_BEAR_15M_WAITING_CONTINUATION",context.readiness,false,Double.NaN,null);
        double structuralStop=s.lowerHigh+.5*atr,distance=structuralStop-close;
        if(distance>4*atr)return cache(s,end,BearTrendSnapshot.INVALIDATED,"ETH_BEAR_STOP_TOO_WIDE",0,false,Double.NaN,null);
        s.softStopPrice=Math.max(structuralStop,close+2*atr);double disaster=3*atr;
        if(Double.isFinite(context.oneHourAtr)&&context.oneHourAtr>0)disaster=Math.max(disaster,2*context.oneHourAtr);
        // The higher timeframe may justify patience, but it must never inflate a
        // 15m entry beyond the declared 4 ATR disaster-risk budget.
        disaster=Math.min(disaster,4*atr);
        s.stopPrice=Math.min(Math.max(s.softStopPrice,close+disaster),close+4*atr);
        double entryRisk=s.stopPrice-close;
        if(Double.isFinite(context.fourHourSupportBelow)){
            double downsideRoom=close-context.fourHourSupportBelow;
            if(downsideRoom<2.5*entryRisk)
                return cache(s,end,BearTrendSnapshot.ARMED,"ETH_BEAR_INSUFFICIENT_DOWNSIDE_ROOM",context.readiness,false,Double.NaN,null);
        }
        s.phase=BearTrendSnapshot.TRIGGERED;s.triggerUntil=end+TRIGGER_VALIDITY;
        s.triggerType="ETH_4H_1H_15M_BEAR_CONTINUATION";s.consumed=false;
        return cache(s,end,s.phase,"ETH_BEAR_MULTITIMEFRAME_TRIGGERED",1,true,s.stopPrice,s.triggerType);
    }
    public BearTrendSnapshot current(String symbol,String timeframe){EthStructuralBearTrendState s=states.get(key(symbol,timeframe));return s==null?BearTrendSnapshot.none(STRATEGY):s.snapshot;}
    public double currentSoftStop(String symbol,String timeframe){EthStructuralBearTrendState s=states.get(key(symbol,timeframe));return s==null?Double.NaN:s.softStopPrice;}
    public void consume(String symbol,String timeframe,int bar){EthStructuralBearTrendState s=states.get(key(symbol,timeframe));if(s!=null&&s.lastIndex==bar&&BearTrendSnapshot.TRIGGERED.equals(s.phase)){s.consumed=true;s.phase=BearTrendSnapshot.RUNNING;if(multiTimeframe!=null)multiTimeframe.consume(symbol,s.setupId);s.snapshot=new BearTrendSnapshot(STRATEGY,s.phase,"ETH_BEAR_SIGNAL_CONSUMED",.9,false,s.stopPrice,s.triggerType,bar);}}
    public void tradeClosed(String symbol,int exit,TradeRecord trade){for(Map.Entry<String,EthStructuralBearTrendState>e:states.entrySet())if(e.getKey().startsWith(symbol.toUpperCase()+"|")){EthStructuralBearTrendState s=e.getValue();s.observing();s.cooldownUntil=exit+COOLDOWN_BARS;}if(multiTimeframe!=null)multiTimeframe.tradeClosed(symbol);}
    public void reset(String symbol){String p=symbol==null?"":symbol.toUpperCase()+"|";states.keySet().removeIf(k->k.startsWith(p));if(multiTimeframe!=null)multiTimeframe.reset(symbol);}
    private BearTrendSnapshot cache(EthStructuralBearTrendState s,int end,String phase,String reason,double ready,boolean action,double stop,String trigger){s.phase=phase;s.snapshot=new BearTrendSnapshot(STRATEGY,phase,reason,ready,action,stop,trigger,end);return s.snapshot;}
    private boolean supports(String s,String t){return "ETHUSDT".equalsIgnoreCase(s)&&"15M".equalsIgnoreCase(t);}private String key(String s,String t){return (s==null?"":s.toUpperCase())+"|"+(t==null?"":t.toUpperCase());}
}
