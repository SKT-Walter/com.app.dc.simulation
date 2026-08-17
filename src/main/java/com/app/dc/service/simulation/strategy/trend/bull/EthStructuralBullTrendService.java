package com.app.dc.service.simulation.strategy.trend.bull;

import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** ETH 4H direction + 1H lifecycle + 15m execution trigger. */
@Service
public class EthStructuralBullTrendService {
    public static final String STRATEGY="ethStructuralBullTrend";
    private static final int TRIGGER_VALIDITY=4,COOLDOWN_BARS=96;
    @Autowired(required=false) @Qualifier("ethMultiTimeframeContextService")
    private EthMultiTimeframeContextService multiTimeframe;
    private final Map<String,EthStructuralBullTrendState> states=new ConcurrentHashMap<String,EthStructuralBullTrendState>();

    public BullTrendSnapshot update(String symbol,String timeframe,BarSeries series,
                                    StructuralTrendSnapshot ignored,BacktestRegime regime){
        EthMultiTimeframeSnapshot context=EthMultiTimeframeSnapshot.warmup();
        if(multiTimeframe!=null&&series!=null&&series.getBarCount()>0)
            context=multiTimeframe.update(symbol,series.getLastBar().getEndTime().toInstant().toEpochMilli());
        return update(symbol,timeframe,series,regime,context);
    }

    /** Explicit-context overload used by deterministic unit tests. */
    public BullTrendSnapshot update(String symbol,String timeframe,BarSeries series,
                                    BacktestRegime regime,EthMultiTimeframeSnapshot context){
        if(!supports(symbol,timeframe)||series==null||series.getBarCount()<60)return BullTrendSnapshot.none(STRATEGY);
        String key=key(symbol,timeframe);EthStructuralBullTrendState s=states.get(key);
        if(s==null){s=new EthStructuralBullTrendState();states.put(key,s);}
        int end=series.getEndIndex();if(s.lastIndex>end){s=new EthStructuralBullTrendState();states.put(key,s);}
        if(s.lastIndex==end)return s.snapshot;s.lastIndex=end;
        double atr=BullTrendMath.atr(series,end,14);
        if(!Double.isFinite(atr)||atr<=0)return cache(s,end,BullTrendSnapshot.INVALIDATED,"ETH_BULL_INVALID_ATR",0,false,Double.NaN,null);
        if(end<=s.cooldownUntil)return cache(s,end,BullTrendSnapshot.COOLDOWN,"ETH_BULL_TRADE_COOLDOWN",0,false,Double.NaN,null);
        if(BullTrendSnapshot.RUNNING.equals(s.phase))return cache(s,end,s.phase,"ETH_BULL_POSITION_RUNNING",.9,false,s.stopPrice,s.triggerType);
        if(BullTrendSnapshot.TRIGGERED.equals(s.phase)){
            if(!s.consumed&&end<=s.triggerUntil)return cache(s,end,s.phase,"ETH_BULL_TRIGGER_VALID",1,true,s.stopPrice,s.triggerType);
            s.observing();
        }
        if(context==null||EthMultiTimeframeSnapshot.WARMUP.equals(context.oneHourPhase)){
            s.observing();return cache(s,end,BullTrendSnapshot.WARMUP,"ETH_MTF_WARMUP",0,false,Double.NaN,null);
        }
        if(!context.directionAllowsLong()){
            s.observing();return cache(s,end,BullTrendSnapshot.OBSERVING,"ETH_4H_DIRECTION_BLOCKED",0,false,Double.NaN,null);
        }
        if(!context.armed()){
            s.observing();String phase=EthMultiTimeframeSnapshot.IMPULSE.equals(context.oneHourPhase)?BullTrendSnapshot.IMPULSE:
                    EthMultiTimeframeSnapshot.PULLBACK.equals(context.oneHourPhase)?BullTrendSnapshot.PULLBACK:BullTrendSnapshot.OBSERVING;
            return cache(s,end,phase,context.reason,context.readiness,false,Double.NaN,null);
        }
        if(s.setupId!=context.setupId)s.setup(context.setupId);
        return trigger(s,symbol,series,end,atr,regime,context);
    }

    private BullTrendSnapshot trigger(EthStructuralBullTrendState s,String symbol,BarSeries x,int end,double atr,
                                      BacktestRegime regime,EthMultiTimeframeSnapshot context){
        s.tacticalBars++;double low=BullTrendMath.low(x,end),ema20=BullTrendMath.ema(x,end,20),close=BullTrendMath.close(x,end);
        s.tacticalLow=Double.isFinite(s.tacticalLow)?Math.min(s.tacticalLow,low):low;
        if(close>ema20)s.aboveEmaBars++;else s.aboveEmaBars=0;
        if(end>=x.getBeginIndex()+2){
            double left=BullTrendMath.low(x,end-2),pivot=BullTrendMath.low(x,end-1),right=low;
            if(pivot<left&&pivot<right&&pivot>context.pullbackLow+.10*atr){s.higherLowConfirmed=true;s.higherLow=pivot;}
        }
        double prior8=BullTrendMath.highest(x,end-1,8),extension=(close-ema20)/atr;
        boolean transition=EthMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP.equals(context.fourHourTrend);
        boolean regimeOk=regime!=null&&(transition?"UP".equals(regime.trend):("UP".equals(regime.trend)||"NONE".equals(regime.trend)));
        boolean eth="ETHUSDT".equalsIgnoreCase(symbol);
        boolean cautious=eth&&close>=3500,extreme=eth&&close>=4500;
        double fourHourExtension=Double.isFinite(context.fourHourAtr)&&context.fourHourAtr>0
                &&Double.isFinite(context.fourHourClose)&&Double.isFinite(context.fourHourEma20)
                ?(context.fourHourClose-context.fourHourEma20)/context.fourHourAtr:Double.POSITIVE_INFINITY;
        double pullbackDepthAtr=Double.isFinite(context.oneHourAtr)&&context.oneHourAtr>0
                &&Double.isFinite(context.impulseHigh)&&Double.isFinite(context.pullbackLow)
                ?(context.impulseHigh-context.pullbackLow)/context.oneHourAtr:0;
        // 3500 is a caution zone, not a price ceiling: keep mature bull legs but reject
        // transitional or already over-extended entries. 4500+ requires materially
        // stronger context because late-cycle false breakouts become much more costly.
        if(cautious&&(transition||context.fourHourConfidence<.65||fourHourExtension>2.5
                ||pullbackDepthAtr<.5))
            return cache(s,end,BullTrendSnapshot.ARMED,"ETH_HIGH_PRICE_GUARD_REJECTED",context.readiness,false,Double.NaN,null);
        if(extreme&&(context.fourHourConfidence<.75||fourHourExtension>2.0||pullbackDepthAtr<1.0))
            return cache(s,end,BullTrendSnapshot.ARMED,"ETH_EXTREME_PRICE_GUARD_REJECTED",context.readiness,false,Double.NaN,null);
        double minimumVolume=extreme?1.0:(cautious?.9:(transition?1.0:.8));
        double maximumExtension=extreme?1.5:(cautious?1.75:2.0);
        boolean quality=s.tacticalBars>=3&&s.higherLowConfirmed&&s.aboveEmaBars>=2&&regimeOk
                &&BullTrendMath.bull(x,end)&&close>BullTrendMath.close(x,end-1)&&close>prior8
                &&BullTrendMath.closeLocation(x,end)>=.65&&BullTrendMath.volumeRatio(x,end,20)>=minimumVolume
                &&extension<=maximumExtension;
        if(!quality)return cache(s,end,BullTrendSnapshot.ARMED,"ETH_15M_WAITING_STRUCTURAL_RECOVERY",context.readiness,false,Double.NaN,null);
        double structuralStop=s.higherLow-.5*atr,distance=close-structuralStop;
        if(distance>4*atr)return cache(s,end,BullTrendSnapshot.INVALIDATED,"ETH_BULL_STOP_TOO_WIDE",0,false,Double.NaN,null);
        s.softStopPrice=Math.min(structuralStop,close-2*atr);
        // 15m noise frequently spans several ATR during a healthy one-to-two day ETH
        // pullback. The hard stop is therefore the wider of 3 ATR15 and 2 ATR1H;
        // the original structure line is retained as a soft, two-close 1H invalidation.
        double disasterDistance=3*atr;
        if(Double.isFinite(context.oneHourAtr)&&context.oneHourAtr>0)
            disasterDistance=Math.max(disasterDistance,2*context.oneHourAtr);
        s.stopPrice=Math.min(s.softStopPrice,close-disasterDistance);
        if(extreme&&(close-s.stopPrice)/close>.02)
            return cache(s,end,BullTrendSnapshot.ARMED,"ETH_EXTREME_PRICE_RISK_REJECTED",context.readiness,false,Double.NaN,null);
        s.riskAtr=atr;s.phase=BullTrendSnapshot.TRIGGERED;
        s.triggerUntil=end+TRIGGER_VALIDITY;s.triggerType="ETH_4H_1H_15M_PULLBACK_RECOVERY";s.consumed=false;
        return cache(s,end,s.phase,"ETH_BULL_MULTITIMEFRAME_TRIGGERED",1,true,s.stopPrice,s.triggerType);
    }

    public BullTrendSnapshot current(String symbol,String timeframe){EthStructuralBullTrendState s=states.get(key(symbol,timeframe));return s==null?BullTrendSnapshot.none(STRATEGY):s.snapshot;}
    public double currentSoftStop(String symbol,String timeframe){EthStructuralBullTrendState s=states.get(key(symbol,timeframe));return s==null?Double.NaN:s.softStopPrice;}
    public void consume(String symbol,String timeframe,int bar){EthStructuralBullTrendState s=states.get(key(symbol,timeframe));if(s!=null&&s.lastIndex==bar&&BullTrendSnapshot.TRIGGERED.equals(s.phase)){s.consumed=true;s.phase=BullTrendSnapshot.RUNNING;if(multiTimeframe!=null)multiTimeframe.consume(symbol,s.setupId);s.snapshot=new BullTrendSnapshot(STRATEGY,s.phase,"ETH_BULL_SIGNAL_CONSUMED",.9,false,s.stopPrice,s.triggerType,bar);}}
    public void tradeClosed(String symbol,int exitBar,TradeRecord trade){for(Map.Entry<String,EthStructuralBullTrendState> e:states.entrySet())if(e.getKey().startsWith(symbol.toUpperCase()+"|")){EthStructuralBullTrendState s=e.getValue();s.observing();s.cooldownUntil=exitBar+COOLDOWN_BARS;}if(multiTimeframe!=null)multiTimeframe.tradeClosed(symbol);}
    public void reset(String symbol){String prefix=symbol==null?"":symbol.toUpperCase()+"|";states.keySet().removeIf(k->k.startsWith(prefix));if(multiTimeframe!=null)multiTimeframe.reset(symbol);}
    private BullTrendSnapshot cache(EthStructuralBullTrendState s,int end,String phase,String reason,double ready,boolean action,double stop,String trigger){s.phase=phase;s.snapshot=new BullTrendSnapshot(STRATEGY,phase,reason,ready,action,stop,trigger,end);return s.snapshot;}
    private boolean supports(String symbol,String timeframe){return "ETHUSDT".equalsIgnoreCase(symbol)&&("15M".equalsIgnoreCase(timeframe)||"15m".equals(timeframe));}
    private String key(String symbol,String timeframe){return (symbol==null?"":symbol.toUpperCase())+"|"+(timeframe==null?"":timeframe.toUpperCase());}
}
