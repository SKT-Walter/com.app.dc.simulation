package com.app.dc.service.simulation.strategy.trend.bull;

import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast continuation re-entry after a proven SOL trend was closed by profit lock.
 * Waiting state never enters the strategy candidate set; only a complete trigger does.
 */
@Service
public class SolTrendRearmService {
    public static final String TRIGGER_TYPE="SOL_CONTINUATION_REARM";
    /**
     * Rearm is deliberately a short continuation window, not a replacement for the
     * full impulse/pullback lifecycle. SOL either resumes quickly after a profit
     * lock or it must build a completely new setup.
     */
    private static final int MINIMUM_WAIT_BARS=16,VALIDITY_BARS=144;
    private final Map<String,State> states=new ConcurrentHashMap<String,State>();

    public void onTradeClosed(String symbol,String timeframe,int exitBar,TradeRecord trade){
        State s=state(symbol,timeframe);
        boolean profitLock=trade!=null&&trade.exitReason!=null
                &&trade.exitReason.toLowerCase(Locale.ROOT).contains("sol_1h_profit_lock");
        boolean alreadyRearmed=trade!=null&&TRIGGER_TYPE.equals(trade.trendTriggerType);
        if(!profitLock||alreadyRearmed){s.clear();return;}
        s.active=true;s.consumed=false;s.armedAt=exitBar;
        s.earliestBar=exitBar+MINIMUM_WAIT_BARS;s.validUntil=exitBar+VALIDITY_BARS;
        s.lastBar=-1;s.lowSinceExit=Double.NaN;
    }

    public SolTrendRearmDecision evaluate(String symbol,String timeframe,BarSeries series,
                                          BacktestRegime regime,EthMultiTimeframeSnapshot context){
        State s=states.get(key(symbol,timeframe));
        if(s==null||!s.active||s.consumed)return SolTrendRearmDecision.inactive();
        if(series==null||series.getBarCount()<60)return SolTrendRearmDecision.waiting("SOL_REARM_DATA_WARMUP");
        int end=series.getEndIndex();
        if(end>s.validUntil){s.clear();return SolTrendRearmDecision.inactive();}
        if(s.lastBar==end)return s.lastDecision;
        s.lastBar=end;
        double atr=BullTrendMath.atr(series,end,14);
        double low=BullTrendMath.low(series,end);
        s.lowSinceExit=Double.isFinite(s.lowSinceExit)?Math.min(s.lowSinceExit,low):low;
        if(context==null||!EthMultiTimeframeSnapshot.FOUR_HOUR_BULL.equals(context.fourHourTrend)
                ||context.fourHourConfidence<.75){
            return remember(s,SolTrendRearmDecision.waiting("SOL_REARM_4H_BULL_REQUIRED"));
        }
        if(end<s.earliestBar)return remember(s,SolTrendRearmDecision.waiting("SOL_REARM_MINIMUM_WAIT"));
        boolean oneHourRecovered=Double.isFinite(context.oneHourClose)
                &&Double.isFinite(context.oneHourEma20)&&Double.isFinite(context.oneHourEma60)
                &&Double.isFinite(context.oneHourEma20Slope)
                &&context.oneHourClose>context.oneHourEma20
                &&context.oneHourClose>context.oneHourEma60
                &&context.oneHourEma20Slope>0;
        if(!oneHourRecovered)return remember(s,SolTrendRearmDecision.waiting("SOL_REARM_1H_RECOVERY_REQUIRED"));
        if(!Double.isFinite(atr)||atr<=0)return remember(s,SolTrendRearmDecision.waiting("SOL_REARM_INVALID_ATR"));
        double close=BullTrendMath.close(series,end),ema20=BullTrendMath.ema(series,end,20);
        boolean regimeReady=regime!=null&&("UP".equals(regime.trend)||"NONE".equals(regime.trend));
        boolean breakout=regimeReady&&BullTrendMath.bull(series,end)
                &&close>BullTrendMath.highest(series,end-1,8)
                &&BullTrendMath.closeLocation(series,end)>=.60
                &&BullTrendMath.volumeRatio(series,end,20)>=.80
                &&close>ema20&&(close-ema20)/atr<=2.5;
        if(!breakout)return remember(s,SolTrendRearmDecision.waiting("SOL_REARM_15M_BREAKOUT_REQUIRED"));
        double anchor=BullTrendMath.lowest(series,end,8);
        double soft=anchor-.5*atr,distance=close-soft;
        if(distance<=0||distance>5*atr)return remember(s,SolTrendRearmDecision.waiting("SOL_REARM_STOP_INVALID"));
        double disaster=Math.max(4*atr,Double.isFinite(context.oneHourAtr)?1.5*context.oneHourAtr:0);
        disaster=Math.min(disaster,.06*close);
        return remember(s,SolTrendRearmDecision.triggered(Math.min(soft,close-disaster),soft));
    }

    public void consume(String symbol,String timeframe){State s=states.get(key(symbol,timeframe));if(s!=null){s.consumed=true;s.active=false;}}
    public void reset(String symbol){String p=(symbol==null?"":symbol.toUpperCase(Locale.ROOT))+"|";states.keySet().removeIf(k->k.startsWith(p));}
    public boolean active(String symbol,String timeframe){State s=states.get(key(symbol,timeframe));return s!=null&&s.active&&!s.consumed;}
    private SolTrendRearmDecision remember(State s,SolTrendRearmDecision d){s.lastDecision=d;return d;}
    private State state(String symbol,String timeframe){String k=key(symbol,timeframe);State s=states.get(k);if(s==null){s=new State();states.put(k,s);}return s;}
    private String key(String symbol,String timeframe){return (symbol==null?"":symbol.toUpperCase(Locale.ROOT))+"|"+(timeframe==null?"":timeframe.toUpperCase(Locale.ROOT));}
    private static final class State{boolean active,consumed;int armedAt,earliestBar,validUntil,lastBar=-1;double lowSinceExit=Double.NaN;SolTrendRearmDecision lastDecision=SolTrendRearmDecision.inactive();void clear(){active=consumed=false;armedAt=earliestBar=validUntil=0;lastBar=-1;lowSinceExit=Double.NaN;lastDecision=SolTrendRearmDecision.inactive();}}
}
