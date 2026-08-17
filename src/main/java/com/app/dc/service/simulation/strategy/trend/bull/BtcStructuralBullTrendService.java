package com.app.dc.service.simulation.strategy.trend.bull;

import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** BTC-only 4H campaign, 1H recovery and 15m execution lifecycle. */
@Service
public class BtcStructuralBullTrendService {
    public static final String STRATEGY="btcStructuralBullTrend";
    private static final int TRIGGER_VALIDITY=4,COOLDOWN_BARS=32;
    private static final double MINIMUM_RISK_RATIO=.018,MAXIMUM_RISK_RATIO=.032;
    @Autowired private BtcMultiTimeframeContextService multiTimeframe;
    private final Map<String,BtcStructuralBullTrendState> states=new ConcurrentHashMap<String,BtcStructuralBullTrendState>();

    public BullTrendSnapshot update(String symbol,String timeframe,BarSeries series,
                                    StructuralTrendSnapshot ignored,BacktestRegime regime){
        BtcMultiTimeframeSnapshot context=BtcMultiTimeframeSnapshot.warmup();
        if(series!=null&&series.getBarCount()>0)context=multiTimeframe.update(symbol,
                series.getLastBar().getEndTime().toInstant().toEpochMilli()-15*60*1000L);
        return update(symbol,timeframe,series,regime,context);
    }

    public BullTrendSnapshot update(String symbol,String timeframe,BarSeries series,
                                    BacktestRegime regime,BtcMultiTimeframeSnapshot context){
        if(!supports(symbol,timeframe)||series==null||series.getBarCount()<60)return BullTrendSnapshot.none(STRATEGY);
        String key=key(symbol,timeframe);BtcStructuralBullTrendState s=states.get(key);
        if(s==null){s=new BtcStructuralBullTrendState();states.put(key,s);}
        int end=series.getEndIndex();if(s.lastIndex>end){s=new BtcStructuralBullTrendState();states.put(key,s);}
        if(s.lastIndex==end)return s.snapshot;s.lastIndex=end;
        if(end<=s.cooldownUntil)return cache(s,end,BullTrendSnapshot.COOLDOWN,"BTC_BULL_TRADE_COOLDOWN",0,false,Double.NaN,null);
        if(BullTrendSnapshot.RUNNING.equals(s.phase))return cache(s,end,s.phase,"BTC_BULL_POSITION_RUNNING",.9,false,s.stopPrice,s.triggerType);
        if(BullTrendSnapshot.TRIGGERED.equals(s.phase)){
            if(!s.consumed&&end<=s.triggerUntil)return cache(s,end,s.phase,"BTC_BULL_TRIGGER_VALID",1,true,s.stopPrice,s.triggerType);
            s.observing();
        }
        if(context==null||BtcMultiTimeframeSnapshot.WARMUP.equals(context.oneHourPhase)){
            s.observing();return cache(s,end,BullTrendSnapshot.WARMUP,"BTC_MTF_WARMUP",0,false,Double.NaN,null);
        }
        if(!context.directionAllowsLong()){
            s.observing();return cache(s,end,BullTrendSnapshot.OBSERVING,"BTC_4H_BULL_CAMPAIGN_ENDED",0,false,Double.NaN,null);
        }
        if(!context.armed()){
            s.observing();String phase=BtcMultiTimeframeSnapshot.IMPULSE.equals(context.oneHourPhase)?BullTrendSnapshot.IMPULSE:
                    BtcMultiTimeframeSnapshot.PULLBACK.equals(context.oneHourPhase)?BullTrendSnapshot.PULLBACK:
                            BtcMultiTimeframeSnapshot.ARMED.equals(context.oneHourPhase)?BullTrendSnapshot.ARMED:BullTrendSnapshot.OBSERVING;
            return cache(s,end,phase,context.reason,context.readiness,false,Double.NaN,null);
        }
        if(s.setupId!=context.setupId)s.setup(context.setupId);
        return trigger(s,series,end,regime,context);
    }

    private BullTrendSnapshot trigger(BtcStructuralBullTrendState s,BarSeries x,int end,
                                      BacktestRegime regime,BtcMultiTimeframeSnapshot context){
        double atr=BullTrendMath.atr(x,end,14),close=BullTrendMath.close(x,end);
        if(!Double.isFinite(atr)||atr<=0)return cache(s,end,BullTrendSnapshot.INVALIDATED,"BTC_BULL_INVALID_ATR",0,false,Double.NaN,null);
        double ema20=BullTrendMath.ema(x,end,20),prior4=BullTrendMath.highest(x,end-1,4);
        boolean regimeOk=regime!=null&&!"DOWN".equals(regime.trend);
        boolean quality=regimeOk&&BullTrendMath.bull(x,end)&&close>BullTrendMath.close(x,end-1)
                &&close>prior4&&close>ema20&&BullTrendMath.closeLocation(x,end)>=.60
                &&BullTrendMath.volumeRatio(x,end,20)>=.65&&(close-ema20)/atr<=1.50;
        if(!quality)return cache(s,end,BullTrendSnapshot.ARMED,"BTC_15M_WAITING_EXECUTION_ACCEPTANCE",context.readiness,false,Double.NaN,null);
        double structuralStop=context.pullbackLow-.50*context.oneHourAtr;
        if(!Double.isFinite(structuralStop)||structuralStop<=0||structuralStop>=close)
            return cache(s,end,BullTrendSnapshot.INVALIDATED,"BTC_BULL_INVALID_STRUCTURE_STOP",0,false,Double.NaN,null);
        double structuralRisk=(close-structuralStop)/close;
        if(structuralRisk>MAXIMUM_RISK_RATIO)
            return cache(s,end,BullTrendSnapshot.ARMED,"BTC_BULL_STOP_TOO_WIDE",context.readiness,false,Double.NaN,null);
        s.softStopPrice=context.pullbackLow;
        s.stopPrice=Math.min(structuralStop,close*(1-MINIMUM_RISK_RATIO));
        s.phase=BullTrendSnapshot.TRIGGERED;s.triggerUntil=end+TRIGGER_VALIDITY;
        s.triggerType="BTC_4H_CAMPAIGN_1H_RECOVERY_15M_ACCEPTANCE";s.consumed=false;
        return cache(s,end,s.phase,"BTC_BULL_MULTITIMEFRAME_TRIGGERED",1,true,s.stopPrice,s.triggerType);
    }

    public BullTrendSnapshot current(String symbol,String timeframe){BtcStructuralBullTrendState s=states.get(key(symbol,timeframe));return s==null?BullTrendSnapshot.none(STRATEGY):s.snapshot;}
    public double currentSoftStop(String symbol,String timeframe){BtcStructuralBullTrendState s=states.get(key(symbol,timeframe));return s==null?Double.NaN:s.softStopPrice;}
    public void consume(String symbol,String timeframe,int bar){BtcStructuralBullTrendState s=states.get(key(symbol,timeframe));if(s!=null&&s.lastIndex==bar&&BullTrendSnapshot.TRIGGERED.equals(s.phase)){s.consumed=true;s.phase=BullTrendSnapshot.RUNNING;multiTimeframe.consume(symbol,s.setupId);s.snapshot=new BullTrendSnapshot(STRATEGY,s.phase,"BTC_BULL_SIGNAL_CONSUMED",.9,false,s.stopPrice,s.triggerType,bar);}}
    public void tradeClosed(String symbol,int exitBar,TradeRecord trade){for(Map.Entry<String,BtcStructuralBullTrendState> e:states.entrySet())if(e.getKey().startsWith(symbol.toUpperCase()+"|")){BtcStructuralBullTrendState s=e.getValue();s.observing();s.cooldownUntil=exitBar+COOLDOWN_BARS;}multiTimeframe.tradeClosed(symbol);}
    public void reset(String symbol){String prefix=symbol==null?"":symbol.toUpperCase()+"|";states.keySet().removeIf(k->k.startsWith(prefix));multiTimeframe.reset(symbol);}
    private BullTrendSnapshot cache(BtcStructuralBullTrendState s,int end,String phase,String reason,double ready,boolean action,double stop,String trigger){s.phase=phase;s.snapshot=new BullTrendSnapshot(STRATEGY,phase,reason,ready,action,stop,trigger,end);return s.snapshot;}
    private boolean supports(String symbol,String timeframe){return "BTCUSDT".equalsIgnoreCase(symbol)&&"15M".equalsIgnoreCase(timeframe);}
    private String key(String symbol,String timeframe){return (symbol==null?"":symbol.toUpperCase())+"|"+(timeframe==null?"":timeframe.toUpperCase());}
}
