package com.app.dc.service.simulation.strategy.trend.bull;

import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SOL 4H direction + 1H impulse/pullback + 15m recovery trend capture. */
@Service
public class SolMomentumBullTrendService {
    public static final String STRATEGY="solMomentumBullTrend";
    private static final int TRIGGER_VALIDITY=4,PROFIT_COOLDOWN_BARS=48,STOP_COOLDOWN_BARS=96;
    @Autowired(required=false) private SolMultiTimeframeContextService multiTimeframe;
    private SolTrendRearmService rearmService=new SolTrendRearmService();
    private final Map<String,SolMomentumBullTrendState> states=
            new ConcurrentHashMap<String,SolMomentumBullTrendState>();

    public BullTrendSnapshot update(String symbol,String timeframe,BarSeries series,
                                    StructuralTrendSnapshot ignored,BacktestRegime regime){
        EthMultiTimeframeSnapshot context=EthMultiTimeframeSnapshot.warmup();
        if(multiTimeframe!=null&&series!=null&&series.getBarCount()>0)
            context=multiTimeframe.update(symbol,
                    series.getLastBar().getEndTime().toInstant().toEpochMilli());
        return update(symbol,timeframe,series,regime,context);
    }

    public BullTrendSnapshot update(String symbol,String timeframe,BarSeries series,
                                    BacktestRegime regime,EthMultiTimeframeSnapshot context){
        if(!supports(symbol,timeframe)||series==null||series.getBarCount()<60)
            return BullTrendSnapshot.none(STRATEGY);
        String key=key(symbol,timeframe);SolMomentumBullTrendState s=states.get(key);
        if(s==null){s=new SolMomentumBullTrendState();states.put(key,s);}
        int end=series.getEndIndex();
        if(s.lastIndex>end){s=new SolMomentumBullTrendState();states.put(key,s);}
        if(s.lastIndex==end)return s.snapshot;s.lastIndex=end;
        double atr=BullTrendMath.atr(series,end,14);
        if(!Double.isFinite(atr)||atr<=0)
            return cache(s,end,BullTrendSnapshot.INVALIDATED,"SOL_BULL_INVALID_ATR",0,false,Double.NaN,null);
        SolTrendRearmDecision rearm=rearmService.evaluate(symbol,timeframe,series,regime,context);
        if(rearm.triggered){
            s.softStopPrice=rearm.softStopPrice;s.stopPrice=rearm.stopPrice;
            s.phase=BullTrendSnapshot.TRIGGERED;s.triggerUntil=end+TRIGGER_VALIDITY;
            s.triggerType=SolTrendRearmService.TRIGGER_TYPE;s.consumed=false;
            return cache(s,end,s.phase,rearm.reason,1,true,s.stopPrice,s.triggerType);
        }
        if(end<=s.cooldownUntil)
            return cache(s,end,BullTrendSnapshot.COOLDOWN,"SOL_BULL_TRADE_COOLDOWN",0,false,Double.NaN,null);
        if(BullTrendSnapshot.RUNNING.equals(s.phase))
            return cache(s,end,s.phase,"SOL_BULL_POSITION_RUNNING",.9,false,s.stopPrice,s.triggerType);
        if(BullTrendSnapshot.TRIGGERED.equals(s.phase)){
            if(!s.consumed&&end<=s.triggerUntil)
                return cache(s,end,s.phase,"SOL_BULL_TRIGGER_VALID",1,true,s.stopPrice,s.triggerType);
            s.observing();
        }
        if(context==null||EthMultiTimeframeSnapshot.WARMUP.equals(context.oneHourPhase)){
            s.observing();return cache(s,end,BullTrendSnapshot.WARMUP,
                    "SOL_MTF_WARMUP",0,false,Double.NaN,null);
        }
        if(!context.directionAllowsLong()){
            s.observing();return cache(s,end,BullTrendSnapshot.OBSERVING,
                    "SOL_4H_DIRECTION_BLOCKED",0,false,Double.NaN,null);
        }
        if(!context.armed()){
            s.observing();String phase=EthMultiTimeframeSnapshot.IMPULSE.equals(context.oneHourPhase)
                    ?BullTrendSnapshot.IMPULSE:EthMultiTimeframeSnapshot.PULLBACK.equals(context.oneHourPhase)
                    ?BullTrendSnapshot.PULLBACK:BullTrendSnapshot.OBSERVING;
            return cache(s,end,phase,solReason(context.reason),context.readiness,false,Double.NaN,null);
        }
        if(s.setupId!=context.setupId)s.setup(context.setupId);
        return trigger(s,series,end,atr,regime,context);
    }

    private BullTrendSnapshot trigger(SolMomentumBullTrendState s,BarSeries x,int end,double atr,
                                      BacktestRegime regime,EthMultiTimeframeSnapshot context){
        s.tacticalBars++;double low=BullTrendMath.low(x,end),close=BullTrendMath.close(x,end);
        double ema20=BullTrendMath.ema(x,end,20);
        s.tacticalLow=Double.isFinite(s.tacticalLow)?Math.min(s.tacticalLow,low):low;
        if(close>ema20)s.aboveEmaBars++;else s.aboveEmaBars=0;
        if(end>=x.getBeginIndex()+2){
            double left=BullTrendMath.low(x,end-2),pivot=BullTrendMath.low(x,end-1),right=low;
            if(pivot<left&&pivot<right&&pivot>context.pullbackLow+.05*atr){
                s.higherLowConfirmed=true;s.higherLow=pivot;
            }
        }
        double prior8=BullTrendMath.highest(x,end-1,8),extension=(close-ema20)/atr;
        boolean transition=EthMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP.equals(context.fourHourTrend);
        boolean regimeOk=regime!=null&&(transition?"UP".equals(regime.trend)
                :("UP".equals(regime.trend)||"NONE".equals(regime.trend)));
        double minimumVolume=transition?1.0:.75;
        boolean quality=s.tacticalBars>=2&&s.higherLowConfirmed&&s.aboveEmaBars>=2&&regimeOk
                &&BullTrendMath.bull(x,end)&&close>BullTrendMath.close(x,end-1)&&close>prior8
                &&BullTrendMath.closeLocation(x,end)>=.62
                &&BullTrendMath.volumeRatio(x,end,20)>=minimumVolume&&extension<=2.5;
        if(!quality)return cache(s,end,BullTrendSnapshot.ARMED,
                "SOL_15M_WAITING_STRUCTURAL_RECOVERY",context.readiness,false,Double.NaN,null);
        double structuralStop=s.higherLow-.5*atr,distance=close-structuralStop;
        if(distance>5*atr)return cache(s,end,BullTrendSnapshot.ARMED,
                "SOL_BULL_STOP_TOO_WIDE",context.readiness,false,Double.NaN,null);
        s.softStopPrice=structuralStop;
        double disasterDistance=4*atr;
        if(Double.isFinite(context.oneHourAtr)&&context.oneHourAtr>0)
            disasterDistance=Math.max(disasterDistance,1.5*context.oneHourAtr);
        disasterDistance=Math.min(disasterDistance,.06*close);
        s.stopPrice=Math.min(structuralStop,close-disasterDistance);
        s.phase=BullTrendSnapshot.TRIGGERED;s.triggerUntil=end+TRIGGER_VALIDITY;
        s.triggerType="SOL_4H_1H_15M_PULLBACK_RECOVERY";s.consumed=false;
        return cache(s,end,s.phase,"SOL_BULL_MULTITIMEFRAME_TRIGGERED",1,true,
                s.stopPrice,s.triggerType);
    }

    public BullTrendSnapshot current(String symbol,String timeframe){
        SolMomentumBullTrendState s=states.get(key(symbol,timeframe));
        return s==null?BullTrendSnapshot.none(STRATEGY):s.snapshot;
    }
    public double currentSoftStop(String symbol,String timeframe){
        SolMomentumBullTrendState s=states.get(key(symbol,timeframe));
        return s==null?Double.NaN:s.softStopPrice;
    }
    public void consume(String symbol,String timeframe,int bar){
        SolMomentumBullTrendState s=states.get(key(symbol,timeframe));
        if(s!=null&&s.lastIndex==bar&&BullTrendSnapshot.TRIGGERED.equals(s.phase)){
            s.consumed=true;s.phase=BullTrendSnapshot.RUNNING;
            if(SolTrendRearmService.TRIGGER_TYPE.equals(s.triggerType))rearmService.consume(symbol,timeframe);
            else if(multiTimeframe!=null)multiTimeframe.consume(symbol,s.setupId);
            s.snapshot=new BullTrendSnapshot(STRATEGY,s.phase,"SOL_BULL_SIGNAL_CONSUMED",
                    .9,false,s.stopPrice,s.triggerType,bar);
        }
    }
    public void tradeClosed(String symbol,int exitBar,TradeRecord trade){
        for(Map.Entry<String,SolMomentumBullTrendState> e:states.entrySet())
            if(e.getKey().startsWith(symbol.toUpperCase()+"|")){
                SolMomentumBullTrendState s=e.getValue();s.observing();
                boolean loss=trade!=null&&trade.returnPct!=null&&trade.returnPct.signum()<0;
                boolean profitLock=trade!=null&&trade.exitReason!=null&&trade.exitReason.toLowerCase().contains("sol_1h_profit_lock");
                s.cooldownUntil=exitBar+(profitLock?16:(loss?STOP_COOLDOWN_BARS:PROFIT_COOLDOWN_BARS));
                s.phase=BullTrendSnapshot.COOLDOWN;
                rearmService.onTradeClosed(symbol,e.getKey().substring(e.getKey().indexOf('|')+1),exitBar,trade);
            }
        if(multiTimeframe!=null)multiTimeframe.tradeClosed(symbol);
    }
    public void reset(String symbol){
        String prefix=symbol==null?"":symbol.toUpperCase()+"|";
        states.keySet().removeIf(k->k.startsWith(prefix));
        rearmService.reset(symbol);
        if(multiTimeframe!=null)multiTimeframe.reset(symbol);
    }
    private BullTrendSnapshot cache(SolMomentumBullTrendState s,int end,String phase,
                                    String reason,double ready,boolean action,double stop,String trigger){
        s.phase=phase;s.snapshot=new BullTrendSnapshot(STRATEGY,phase,reason,ready,
                action,stop,trigger,end);return s.snapshot;
    }
    private String solReason(String reason){return reason==null?"SOL_MTF_STATE":reason.replace("ETH_","SOL_");}
    private boolean supports(String symbol,String timeframe){return "SOLUSDT".equalsIgnoreCase(symbol)
            &&("15M".equalsIgnoreCase(timeframe)||"15m".equals(timeframe));}
    private String key(String symbol,String timeframe){return (symbol==null?"":symbol.toUpperCase())
            +"|"+(timeframe==null?"":timeframe.toUpperCase());}

    @Autowired(required=false)
    public void setRearmService(SolTrendRearmService rearmService){if(rearmService!=null)this.rearmService=rearmService;}
}
