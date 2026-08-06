package com.app.dc.service.simulation.strategy.trend.bull;

import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Stateful SOL compression-to-expansion continuation detector. */
@Service
public class SolMomentumBullTrendService {
    public static final String STRATEGY="solMomentumBullTrend";
    private static final int PREPARATION_BARS=8,ARMED_VALIDITY=6,TRIGGER_VALIDITY=4,COOLDOWN_BARS=8;
    private final Map<String,SolMomentumBullTrendState> states=new ConcurrentHashMap<String,SolMomentumBullTrendState>();

    public BullTrendSnapshot update(String symbol,String timeframe,BarSeries series,
                                    StructuralTrendSnapshot structural,BacktestRegime regime){
        if(!supports(symbol,timeframe)||series==null||series.getBarCount()<65)return BullTrendSnapshot.none(STRATEGY);
        String key=key(symbol,timeframe);SolMomentumBullTrendState s=states.get(key);
        if(s==null){s=new SolMomentumBullTrendState();states.put(key,s);}
        int end=series.getEndIndex();if(s.lastIndex>end){s=new SolMomentumBullTrendState();states.put(key,s);}
        if(s.lastIndex==end)return s.snapshot;s.lastIndex=end;
        double atr=BullTrendMath.atr(series,end,14);
        if(!Double.isFinite(atr)||atr<=0)return cache(s,end,BullTrendSnapshot.INVALIDATED,"SOL_BULL_INVALID_ATR",0,false,Double.NaN,null);
        if(end<=s.cooldownUntil)return cache(s,end,BullTrendSnapshot.COOLDOWN,"SOL_BULL_STOP_COOLDOWN",0,false,Double.NaN,null);
        if(BullTrendSnapshot.RUNNING.equals(s.phase))return cache(s,end,s.phase,"SOL_BULL_POSITION_RUNNING",.9,false,s.stopPrice,s.triggerType);
        if(BullTrendSnapshot.TRIGGERED.equals(s.phase)){
            if(!s.consumed&&end<=s.triggerUntil)return cache(s,end,s.phase,"SOL_BULL_TRIGGER_VALID",1,true,s.stopPrice,s.triggerType);
            s.observing();
        }
        boolean bullStructure=structural!=null&&structural.ready&&structural.isBull()
                &&structural.confidence>=.60&&!StructuralTrendSnapshot.BEAR.equals(structural.rawDirection);
        if(!bullStructure){s.observing();return cache(s,end,BullTrendSnapshot.OBSERVING,
                structural==null||!structural.ready?"SOL_BULL_STRUCTURE_WARMUP":"SOL_BULL_STRUCTURE_NOT_BULL",.45,false,Double.NaN,null);}
        if(BullTrendSnapshot.WARMUP.equals(s.phase)||BullTrendSnapshot.INVALIDATED.equals(s.phase)
                ||BullTrendSnapshot.COOLDOWN.equals(s.phase))s.observing();
        if(BullTrendSnapshot.ARMED.equals(s.phase))return armed(s,series,end,atr,regime);
        return prepare(s,series,end,atr,regime);
    }

    private BullTrendSnapshot prepare(SolMomentumBullTrendState s,BarSeries x,int end,double atr,BacktestRegime regime){
        double ema20=BullTrendMath.ema(x,end,20),ema60=BullTrendMath.ema(x,end,60);
        double past60=BullTrendMath.ema(x,Math.max(x.getBeginIndex(),end-8),60);
        double close=BullTrendMath.close(x,end),range20=BullTrendMath.highest(x,end,20)-BullTrendMath.lowest(x,end,20);
        double atrPct=regime==null||regime.features==null||regime.features.get("atrPercentile")==null?.5:regime.features.get("atrPercentile");
        boolean aligned=ema20>ema60&&ema60>past60&&close>=ema60&&Math.abs(close-ema20)/atr<=1.5;
        boolean compressed=atrPct<=.40||range20<=4*atr;
        if(!aligned||!compressed){s.preparationBars=0;s.phase=BullTrendSnapshot.OBSERVING;return cache(s,end,s.phase,!aligned?"SOL_BULL_TREND_NOT_ALIGNED":"SOL_BULL_WAITING_COMPRESSION",.45,false,Double.NaN,null);}
        s.preparationBars++;s.phase=BullTrendSnapshot.PREPARING;
        if(s.preparationBars<PREPARATION_BARS)return cache(s,end,s.phase,"SOL_BULL_COMPRESSION_ACCUMULATING",.75,false,Double.NaN,null);
        s.compressionLow=BullTrendMath.lowest(x,end,20);s.compressionHigh=BullTrendMath.highest(x,end,20);
        s.armedUntil=end+ARMED_VALIDITY;s.phase=BullTrendSnapshot.ARMED;
        return cache(s,end,s.phase,"SOL_BULL_BREAKOUT_ARMED",.90,false,Double.NaN,null);
    }

    private BullTrendSnapshot armed(SolMomentumBullTrendState s,BarSeries x,int end,double atr,BacktestRegime regime){
        if(end>s.armedUntil){s.observing();return cache(s,end,BullTrendSnapshot.INVALIDATED,"SOL_BULL_ARMED_EXPIRED",0,false,Double.NaN,null);}
        double close=BullTrendMath.close(x,end),ema20=BullTrendMath.ema(x,end,20),ema60=BullTrendMath.ema(x,end,60);
        double priorHigh=BullTrendMath.highest(x,end-1,20),body=BullTrendMath.bodyAtr(x,end,atr);
        boolean breakout=close>priorHigh&&BullTrendMath.bull(x,end)&&body>=.5&&body<=1.5
                &&BullTrendMath.volumeRatio(x,end,20)>=1&&BullTrendMath.closeLocation(x,end)>=.70
                &&(close-ema20)/atr<=2;
        if(breakout){
            double structuralStop=s.compressionLow-.5*atr,distance=close-structuralStop;
            if(distance>4*atr){s.observing();return cache(s,end,BullTrendSnapshot.INVALIDATED,"SOL_BULL_STOP_TOO_WIDE",0,false,Double.NaN,null);}
            s.stopPrice=Math.min(structuralStop,close-2*atr);s.phase=BullTrendSnapshot.TRIGGERED;
            s.triggerUntil=end+TRIGGER_VALIDITY;s.triggerType="SOL_COMPRESSION_BREAKOUT";s.consumed=false;
            return cache(s,end,s.phase,"SOL_BULL_MOMENTUM_TRIGGERED",1,true,s.stopPrice,s.triggerType);
        }
        boolean highVol=regime!=null&&"HIGH".equals(regime.volatility);
        boolean strongBear=!BullTrendMath.bull(x,end)&&body>1;
        if(highVol||strongBear||BullTrendMath.low(x,end)<s.compressionLow||close<ema60){
            String reason=highVol?"SOL_BULL_HIGH_VOLATILITY_REJECTED":strongBear?"SOL_BULL_STRONG_BEAR_REJECTED":"SOL_BULL_COMPRESSION_BROKEN";
            s.observing();return cache(s,end,BullTrendSnapshot.INVALIDATED,reason,0,false,Double.NaN,null);
        }
        return cache(s,end,s.phase,"SOL_BULL_WAITING_BREAKOUT",.90,false,Double.NaN,null);
    }

    public BullTrendSnapshot current(String symbol,String timeframe){SolMomentumBullTrendState s=states.get(key(symbol,timeframe));return s==null?BullTrendSnapshot.none(STRATEGY):s.snapshot;}
    public void consume(String symbol,String timeframe,int bar){SolMomentumBullTrendState s=states.get(key(symbol,timeframe));if(s!=null&&s.lastIndex==bar&&BullTrendSnapshot.TRIGGERED.equals(s.phase)){s.consumed=true;s.phase=BullTrendSnapshot.RUNNING;s.snapshot=new BullTrendSnapshot(STRATEGY,s.phase,"SOL_BULL_SIGNAL_CONSUMED",.9,false,s.stopPrice,s.triggerType,bar);}}
    public void tradeClosed(String symbol,int exitBar,TradeRecord trade){for(Map.Entry<String,SolMomentumBullTrendState> e:states.entrySet())if(e.getKey().startsWith(symbol.toUpperCase()+"|")){SolMomentumBullTrendState s=e.getValue();s.observing();if(trade!=null&&trade.exitReason!=null&&trade.exitReason.contains("stop")){s.cooldownUntil=exitBar+COOLDOWN_BARS;s.phase=BullTrendSnapshot.COOLDOWN;}}}
    public void reset(String symbol){String prefix=symbol==null?"":symbol.toUpperCase()+"|";states.keySet().removeIf(k->k.startsWith(prefix));}
    private BullTrendSnapshot cache(SolMomentumBullTrendState s,int end,String phase,String reason,double ready,boolean action,double stop,String trigger){s.phase=phase;s.snapshot=new BullTrendSnapshot(STRATEGY,phase,reason,ready,action,stop,trigger,end);return s.snapshot;}
    private boolean supports(String symbol,String timeframe){return "SOLUSDT".equalsIgnoreCase(symbol)&&("15M".equalsIgnoreCase(timeframe)||"15m".equals(timeframe));}
    private String key(String symbol,String timeframe){return (symbol==null?"":symbol.toUpperCase())+"|"+(timeframe==null?"":timeframe.toUpperCase());}
}
