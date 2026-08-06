package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Stateful, idempotent and look-ahead-free structural trend lifecycle. */
@Service
public class TrendLifecycleService {
    private final Map<String,TrendLifecycleState> states=
            new ConcurrentHashMap<String,TrendLifecycleState>();
    @Autowired private SymbolStrategyProfileService profiles;

    public TrendLifecycleSnapshot update(String symbol, String timeframe,
                                         BarSeries series,
                                         StructuralTrendSnapshot structural) {
        return update(symbol,timeframe,series,structural,null);
    }

    public TrendLifecycleSnapshot update(String symbol,String timeframe,BarSeries series,
                                         StructuralTrendSnapshot structural,BacktestRegime regime) {
        BinanceTrendSettings settings=profiles.binanceTrendSettings(symbol,timeframe);
        if(!settings.lifecycleEnabled) return TrendLifecycleSnapshot.none();
        String key=key(symbol,timeframe);
        TrendLifecycleState state=states.computeIfAbsent(key,k->new TrendLifecycleState());
        synchronized(state){
            TrendLifecycleSnapshot value=update(state,series,structural,settings);
            if(value.actionable&&regime!=null&&!regimeAligned(value,regime)){
                value=new TrendLifecycleSnapshot(value.phase,value.direction,
                        "TREND_TRIGGER_WAITING_REGIME_ALIGNMENT",value.readiness,false,
                        value.stopPrice,value.triggerPrice,value.atr,value.retracement,value.phaseBars);
                state.cached=value;
            }
            return value;
        }
    }

    public TrendLifecycleState newState(){return new TrendLifecycleState();}

    public TrendLifecycleSnapshot update(TrendLifecycleState s, BarSeries series,
                                  StructuralTrendSnapshot structural,
                                  BinanceTrendSettings x) {
        if(series==null||series.getBarCount()<Math.max(60,x.impulseLookbackBars+2)
                ||structural==null||!structural.ready) return snapshot(s,WARMUP_REASON,0,false,
                Double.NaN,Double.NaN,Double.NaN,0,0);
        int end=series.getEndIndex();
        if(s.lastProcessedIndex==end&&s.cached!=null) return s.cached;
        if(s.lastProcessedIndex>=0&&end!=s.lastProcessedIndex+1) reset(s);
        s.lastProcessedIndex=end;
        String structuralDirection=structural.isBull()?TrendLifecycleSnapshot.BULL
                :structural.isBear()?TrendLifecycleSnapshot.BEAR:TrendLifecycleSnapshot.NONE;
        if(TrendLifecycleSnapshot.NONE.equals(structuralDirection)) {
            if(TrendLifecycleSnapshot.WARMUP.equals(s.phase)) s.phase=TrendLifecycleSnapshot.NEUTRAL;
            return cache(s,snapshot(s,"TREND_STRUCTURE_NEUTRAL",0,false,
                    Double.NaN,Double.NaN,atr(series,end),Double.NaN,phaseBars(s,end)));
        }
        if(TrendLifecycleSnapshot.NONE.equals(s.direction)
                ||!structuralDirection.equals(s.direction)) {
            String old=s.direction;
            s.directional(structuralDirection,end);
            if(!TrendLifecycleSnapshot.NONE.equals(old))
                return cache(s,new TrendLifecycleSnapshot(TrendLifecycleSnapshot.INVALIDATED,
                        old,"TREND_SLOW_STRUCTURE_REVERSED",0,false,Double.NaN,
                        Double.NaN,atr(series,end),Double.NaN,0));
        }
        double atr=atr(series,end);
        if(!positive(atr)) return cache(s,invalidate(s,end,"TREND_INVALID_ATR",atr));
        if(phaseBars(s,end)>x.maximumLifecycleBars
                &&!TrendLifecycleSnapshot.DIRECTIONAL.equals(s.phase))
            return cache(s,invalidate(s,end,"TREND_LIFECYCLE_EXPIRED",atr));

        if(TrendLifecycleSnapshot.DIRECTIONAL.equals(s.phase))
            return cache(s,directional(s,series,end,atr,x));
        if(TrendLifecycleSnapshot.IMPULSE.equals(s.phase))
            return cache(s,impulse(s,series,end,atr,x));
        if(TrendLifecycleSnapshot.PULLBACK.equals(s.phase))
            return cache(s,pullback(s,series,end,atr,x));
        if(TrendLifecycleSnapshot.ARMED.equals(s.phase))
            return cache(s,armed(s,series,end,atr,x));
        if(TrendLifecycleSnapshot.TRIGGERED.equals(s.phase))
            return cache(s,triggered(s,series,end,atr,x));
        if(TrendLifecycleSnapshot.RUNNING.equals(s.phase))
            return cache(s,snapshot(s,"TREND_POSITION_RUNNING",1,false,s.stopPrice,
                    s.triggerPrice,atr,deepestRetracement(s),phaseBars(s,end)));
        s.directional(structuralDirection,end);
        return cache(s,snapshot(s,"TREND_DIRECTION_CONFIRMED",.45,false,
                Double.NaN,Double.NaN,atr,Double.NaN,0));
    }

    public TrendLifecycleSnapshot current(String symbol,String timeframe) {
        TrendLifecycleState s=states.get(key(symbol,timeframe));
        return s==null||s.cached==null?TrendLifecycleSnapshot.none():s.cached;
    }

    public void consume(String symbol,String timeframe,int barIndex) {
        TrendLifecycleState s=states.get(key(symbol,timeframe));
        if(s==null)return;
        synchronized(s){if(s.lastProcessedIndex==barIndex&&TrendLifecycleSnapshot.TRIGGERED.equals(s.phase)){
            s.consumed=true;s.phase=TrendLifecycleSnapshot.RUNNING;s.phaseStartIndex=barIndex;
        }}
    }

    public void tradeClosed(String symbol,String timeframe) {
        TrendLifecycleState s=states.get(key(symbol,timeframe));
        if(s==null)return;
        synchronized(s){String direction=s.direction;int index=s.lastProcessedIndex;
            s.directional(direction,index);s.cached=null;}
    }

    public void tradeClosed(String symbol){
        String prefix=(symbol==null?"":symbol.trim().toUpperCase(Locale.ROOT))+"|";
        for(Map.Entry<String,TrendLifecycleState> entry:states.entrySet()){
            if(!entry.getKey().startsWith(prefix))continue;
            TrendLifecycleState s=entry.getValue();synchronized(s){String d=s.direction;int i=s.lastProcessedIndex;
                s.directional(d,i);s.cached=null;}
        }
    }

    public void reset(String symbol) {
        String prefix=(symbol==null?"":symbol.trim().toUpperCase(Locale.ROOT))+"|";
        for(String key:states.keySet()) if(key.startsWith(prefix))states.remove(key);
    }

    private TrendLifecycleSnapshot directional(TrendLifecycleState s,BarSeries series,int end,
                                                double atr,BinanceTrendSettings x){
        Bar bar=series.getBar(end);double close=close(bar),open=open(bar);
        double priorHigh=highest(series,end-1,x.impulseLookbackBars);
        double priorLow=lowest(series,end-1,x.impulseLookbackBars);
        double body=Math.abs(close-open)/atr,location=location(bar);
        double volume=volumeRatio(series,end,20);
        boolean bull=TrendLifecycleSnapshot.BULL.equals(s.direction);
        boolean breakout=bull?close>priorHigh:close<priorLow;
        boolean quality=body>=x.minimumBodyAtr&&volume>=x.minimumVolumeRatio
                &&(bull?location>=x.minimumCloseLocation:location<=1-x.minimumCloseLocation);
        if(!breakout||!quality)return snapshot(s,"TREND_WAITING_FOR_FIRST_IMPULSE",.45,
                false,Double.NaN,Double.NaN,atr,Double.NaN,phaseBars(s,end));
        s.phase=TrendLifecycleSnapshot.IMPULSE;s.phaseStartIndex=end;
        s.impulseOrigin=bull?priorLow:priorHigh;
        s.impulseExtreme=bull?bar.getHighPrice().doubleValue():bar.getLowPrice().doubleValue();
        return snapshot(s,"TREND_FIRST_IMPULSE_CONFIRMED",.65,false,Double.NaN,
                Double.NaN,atr,0,0);
    }

    private TrendLifecycleSnapshot impulse(TrendLifecycleState s,BarSeries series,int end,
                                           double atr,BinanceTrendSettings x){
        Bar bar=series.getBar(end);boolean bull=TrendLifecycleSnapshot.BULL.equals(s.direction);
        if(bull)s.impulseExtreme=Math.max(s.impulseExtreme,bar.getHighPrice().doubleValue());
        else s.impulseExtreme=Math.min(s.impulseExtreme,bar.getLowPrice().doubleValue());
        double retracement=currentRetracement(s,close(bar));
        if(retracement>x.invalidationRetracement||originBroken(s,bar))
            return invalidate(s,end,"TREND_IMPULSE_INVALIDATED",atr);
        if(retracement>=x.minimumRetracement){
            s.phase=TrendLifecycleSnapshot.PULLBACK;s.pullbackBars=1;
            s.pullbackExtreme=bull?bar.getLowPrice().doubleValue():bar.getHighPrice().doubleValue();
            return snapshot(s,"TREND_PULLBACK_STARTED",.75,false,Double.NaN,
                    Double.NaN,atr,retracement,phaseBars(s,end));
        }
        return snapshot(s,"TREND_IMPULSE_RUNNING",.65,false,Double.NaN,
                Double.NaN,atr,retracement,phaseBars(s,end));
    }

    private TrendLifecycleSnapshot pullback(TrendLifecycleState s,BarSeries series,int end,
                                            double atr,BinanceTrendSettings x){
        Bar bar=series.getBar(end);boolean bull=TrendLifecycleSnapshot.BULL.equals(s.direction);
        s.pullbackBars++;
        if(bull)s.pullbackExtreme=Math.min(s.pullbackExtreme,bar.getLowPrice().doubleValue());
        else s.pullbackExtreme=Math.max(s.pullbackExtreme,bar.getHighPrice().doubleValue());
        double retracement=deepestRetracement(s);
        if(retracement>x.invalidationRetracement||originBroken(s,bar))
            return invalidate(s,end,"TREND_PULLBACK_INVALIDATED",atr);
        boolean validDepth=retracement>=x.minimumRetracement&&retracement<=x.maximumRetracement;
        boolean recovery=recovery(bar,series,end,bull,x);
        if(s.pullbackBars>=x.minimumPullbackBars&&validDepth&&recovery){
            s.phase=TrendLifecycleSnapshot.ARMED;
            // Wave-3/C confirmation must take out the first impulse extreme;
            // merely crossing the recovery candle is too noisy on ETH 15m.
            s.recoveryLevel=s.impulseExtreme;
            return snapshot(s,"TREND_CONTINUATION_ARMED",.90,false,Double.NaN,
                    s.recoveryLevel,atr,retracement,phaseBars(s,end));
        }
        return snapshot(s,retracement>x.maximumRetracement
                        ?"TREND_DEEP_PULLBACK_WAITING":"TREND_PULLBACK_ACCUMULATING",
                .75,false,Double.NaN,Double.NaN,atr,retracement,phaseBars(s,end));
    }

    private TrendLifecycleSnapshot armed(TrendLifecycleState s,BarSeries series,int end,
                                         double atr,BinanceTrendSettings x){
        Bar bar=series.getBar(end);boolean bull=TrendLifecycleSnapshot.BULL.equals(s.direction);
        double retracement=deepestRetracement(s);
        if(retracement>x.invalidationRetracement||originBroken(s,bar))
            return invalidate(s,end,"TREND_ARMED_INVALIDATED",atr);
        double close=close(bar);boolean crossed=bull?close>s.recoveryLevel:close<s.recoveryLevel;
        if(!crossed||!triggerQuality(bar,series,end,bull,atr,x))
            return snapshot(s,"TREND_ARMED_WAITING_BREAKOUT",.90,false,Double.NaN,
                    s.recoveryLevel,atr,retracement,phaseBars(s,end));
        double structureStop=bull?s.pullbackExtreme-x.stopPaddingAtr*atr
                :s.pullbackExtreme+x.stopPaddingAtr*atr;
        double minimumStop=bull?close-x.minimumStopAtr*atr:close+x.minimumStopAtr*atr;
        double stop=bull?Math.min(structureStop,minimumStop):Math.max(structureStop,minimumStop);
        double risk=Math.abs(close-stop)/atr;
        if(risk>x.maximumStopAtr)return invalidate(s,end,"TREND_ENTRY_RISK_TOO_WIDE",atr);
        s.phase=TrendLifecycleSnapshot.TRIGGERED;s.triggeredIndex=end;
        s.signalExpiresIndex=end+x.triggerValidityBars;s.triggerPrice=close;s.stopPrice=stop;
        s.consumed=false;
        return snapshot(s,"CONTINUATION_BREAKOUT",1,true,stop,close,atr,retracement,0);
    }

    private TrendLifecycleSnapshot triggered(TrendLifecycleState s,BarSeries series,int end,
                                             double atr,BinanceTrendSettings x){
        if(s.consumed){s.phase=TrendLifecycleSnapshot.RUNNING;s.phaseStartIndex=end;
            return snapshot(s,"TREND_POSITION_RUNNING",1,false,s.stopPrice,s.triggerPrice,
                    atr,deepestRetracement(s),0);}
        double close=close(series.getBar(end));boolean bull=TrendLifecycleSnapshot.BULL.equals(s.direction);
        double extension=(bull?close-s.triggerPrice:s.triggerPrice-close)/atr;
        if(end>s.signalExpiresIndex||extension>x.maximumTriggerExtensionAtr)
            return invalidate(s,end,"TREND_TRIGGER_EXPIRED",atr);
        return snapshot(s,"CONTINUATION_BREAKOUT",1,true,s.stopPrice,s.triggerPrice,
                atr,deepestRetracement(s),phaseBars(s,end));
    }

    private boolean recovery(Bar bar,BarSeries series,int end,boolean bull,BinanceTrendSettings x){
        double close=close(bar),open=open(bar),previous=BinanceStrategyMath.close(series,end-1);
        double loc=location(bar);
        return bull?close>open&&close>previous&&loc>=.55:close<open&&close<previous&&loc<=.45;
    }
    private boolean triggerQuality(Bar bar,BarSeries series,int end,boolean bull,double atr,
                                   BinanceTrendSettings x){
        double body=Math.abs(close(bar)-open(bar))/atr,loc=location(bar);
        return body>=x.minimumBodyAtr&&volumeRatio(series,end,20)>=x.minimumVolumeRatio
                &&(bull?loc>=x.minimumCloseLocation:loc<=1-x.minimumCloseLocation);
    }
    private boolean originBroken(TrendLifecycleState s,Bar bar){return TrendLifecycleSnapshot.BULL.equals(s.direction)
            ?bar.getLowPrice().doubleValue()<=s.impulseOrigin:bar.getHighPrice().doubleValue()>=s.impulseOrigin;}
    private double currentRetracement(TrendLifecycleState s,double price){double move=Math.abs(s.impulseExtreme-s.impulseOrigin);
        return move<=0?0:(TrendLifecycleSnapshot.BULL.equals(s.direction)?s.impulseExtreme-price:price-s.impulseExtreme)/move;}
    private double deepestRetracement(TrendLifecycleState s){double move=Math.abs(s.impulseExtreme-s.impulseOrigin);
        return move<=0?0:(TrendLifecycleSnapshot.BULL.equals(s.direction)?s.impulseExtreme-s.pullbackExtreme:s.pullbackExtreme-s.impulseExtreme)/move;}
    private TrendLifecycleSnapshot invalidate(TrendLifecycleState s,int end,String reason,double atr){String old=s.direction;
        s.directional(old,end);return new TrendLifecycleSnapshot(TrendLifecycleSnapshot.INVALIDATED,old,reason,0,false,
                Double.NaN,Double.NaN,atr,Double.NaN,0);}
    private void reset(TrendLifecycleState s){s.lastProcessedIndex=-1;s.phase=TrendLifecycleSnapshot.WARMUP;
        s.direction=TrendLifecycleSnapshot.NONE;s.cached=null;}
    private TrendLifecycleSnapshot snapshot(TrendLifecycleState s,String reason,double readiness,
                                            boolean action,double stop,double trigger,double atr,
                                            double retracement,int bars){return new TrendLifecycleSnapshot(s.phase,s.direction,reason,
            readiness,action,stop,trigger,atr,retracement,bars);}
    private TrendLifecycleSnapshot cache(TrendLifecycleState s,TrendLifecycleSnapshot v){s.cached=v;return v;}
    private int phaseBars(TrendLifecycleState s,int end){return s.phaseStartIndex<0?0:Math.max(0,end-s.phaseStartIndex);}
    private double atr(BarSeries s,int end){return BinanceStrategyMath.atr(s,end,14);}
    private double highest(BarSeries s,int end,int n){return BinanceStrategyMath.highestHigh(s,end,n);}
    private double lowest(BarSeries s,int end,int n){return BinanceStrategyMath.lowestLow(s,end,n);}
    private double volumeRatio(BarSeries s,int end,int n){int start=Math.max(s.getBeginIndex(),end-n+1);double sum=0;
        for(int i=start;i<=end;i++)sum+=s.getBar(i).getVolume().doubleValue();double avg=sum/Math.max(1,end-start+1);
        return avg<=0?0:s.getBar(end).getVolume().doubleValue()/avg;}
    private double location(Bar b){double range=Math.max(1e-9,b.getHighPrice().doubleValue()-b.getLowPrice().doubleValue());
        return (close(b)-b.getLowPrice().doubleValue())/range;}
    private double close(Bar b){return b.getClosePrice().doubleValue();} private double open(Bar b){return b.getOpenPrice().doubleValue();}
    private boolean positive(double v){return Double.isFinite(v)&&v>0;}
    private boolean regimeAligned(TrendLifecycleSnapshot value,BacktestRegime regime){
        return TrendLifecycleSnapshot.BULL.equals(value.direction)?"UP".equals(regime.trend)
                :TrendLifecycleSnapshot.BEAR.equals(value.direction)&&"DOWN".equals(regime.trend);
    }
    private String key(String symbol,String timeframe){return (symbol==null?"":symbol.trim().toUpperCase(Locale.ROOT))+"|"
            +(timeframe==null?"":timeframe.trim().toUpperCase(Locale.ROOT));}
    private static final String WARMUP_REASON="TREND_LIFECYCLE_WARMUP";
}
