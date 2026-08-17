package com.app.dc.service.simulation.strategy.trend.bull;

import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Independent 4H-turn-up/1H-launch parser used only by solBullLaunchTrend. */
@Service
public class SolBullLaunchContextService {
    private static final long M15=15*60*1000L,H1=60*60*1000L,H4=4*H1;
    private static final int LAUNCH_WINDOW_4H_BARS=12;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId BEIJING=ZoneId.of("Asia/Shanghai");
    private final Map<String,Session> sessions=new ConcurrentHashMap<String,Session>();
    @Autowired private SolBullLaunchConvictionPolicy convictionPolicy;

    public void prepare(String symbol,List<TTbookOhlc> oneHour,List<TTbookOhlc> fourHour){
        sessions.put(key(symbol),new Session(convert(oneHour),convert(fourHour)));
    }

    public EthMultiTimeframeSnapshot update(String symbol,long current15Open){
        Session s=sessions.get(key(symbol));if(s==null)return EthMultiTimeframeSnapshot.warmup();
        long cutoff=current15Open+M15;
        int next4=available(s.fourHour,H4,cutoff,s.available4h);
        while(s.available4h<next4){s.available4h++;updateLaunchWindow(s,context4h(s.fourHour,s.available4h));}
        FourHourContext four=context4h(s.fourHour,s.available4h);
        int next1=available(s.oneHour,H1,cutoff,s.available1h);
        while(s.available1h<next1){s.available1h++;advanceOneHour(s,s.available1h,four);}
        s.snapshot=snapshot(s,four);return s.snapshot;
    }

    public EthMultiTimeframeSnapshot current(String symbol){Session s=sessions.get(key(symbol));return s==null?EthMultiTimeframeSnapshot.warmup():s.snapshot;}
    public void consume(String symbol,long setupId){Session s=sessions.get(key(symbol));if(s!=null&&s.setupId==setupId){s.phase=EthMultiTimeframeSnapshot.RUNNING;s.attempted=true;s.snapshot=snapshot(s,context4h(s.fourHour,s.available4h));}}
    public void tradeClosed(String symbol){Session s=sessions.get(key(symbol));if(s!=null){s.observing();s.cooldownUntil=s.available1h+24;s.phase=EthMultiTimeframeSnapshot.COOLDOWN;s.reason="SOL_LAUNCH_TRADE_COOLDOWN";}}
    public void reset(String symbol){sessions.remove(key(symbol));}

    private void updateLaunchWindow(Session s,FourHourContext four){
        boolean bullish=(EthMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP.equals(four.trend)
                ||EthMultiTimeframeSnapshot.FOUR_HOUR_BULL.equals(four.trend))
                &&four.close>four.ema20&&four.ema20Slope>0
                &&(four.close>four.ema60||four.ema20>=four.ema60*.97);
        if(bullish){
            s.bullish4hBars++;s.bearish4hBars=0;
            if(!s.episodeActive&&s.bullish4hBars>=2){
                s.episodeActive=true;s.launchUntil4h=four.index+LAUNCH_WINDOW_4H_BARS;
                s.attempted=false;s.observing();s.reason="SOL_4H_LAUNCH_WINDOW_OPENED";
            }
        }else{
            s.bullish4hBars=0;
            if(EthMultiTimeframeSnapshot.FOUR_HOUR_BEAR.equals(four.trend))s.bearish4hBars++;
            else s.bearish4hBars=0;
            if(s.bearish4hBars>=2){
                s.episodeActive=false;s.launchUntil4h=-1;s.attempted=false;
                s.observing();s.reason="SOL_4H_LAUNCH_INVALIDATED";
            }
        }
    }

    private void advanceOneHour(Session s,int end,FourHourContext four){
        if(end<60||s.available4h<60){s.phase=EthMultiTimeframeSnapshot.WARMUP;s.reason="SOL_LAUNCH_MTF_WARMUP";return;}
        HBar b=s.oneHour.get(end),previous=s.oneHour.get(end-1);
        double atr=atr(s.oneHour,end,14),ema20=ema(s.oneHour,end,20),ema60=ema(s.oneHour,end,60);
        s.lastAtr=atr;s.lastClose=b.close;s.lastEma20=ema20;s.lastEma20Slope=ema20-ema(s.oneHour,end-3,20);s.lastEma60=ema60;
        if(end>=2){HBar left=s.oneHour.get(end-2),pivot=s.oneHour.get(end-1);if(pivot.low<left.low&&pivot.low<b.low){s.lastSwingLow=pivot.low;s.lastSwingLowIndex=end-1;}}
        if(end<=s.cooldownUntil){s.phase=EthMultiTimeframeSnapshot.COOLDOWN;s.reason="SOL_LAUNCH_TRADE_COOLDOWN";return;}
        if(EthMultiTimeframeSnapshot.RUNNING.equals(s.phase)){s.reason="SOL_LAUNCH_POSITION_RUNNING";return;}
        boolean window=s.launchUntil4h>=four.index&&!s.attempted
                &&!EthMultiTimeframeSnapshot.FOUR_HOUR_BEAR.equals(four.trend)
                &&four.close>four.ema20&&four.ema20Slope>0
                &&(four.close>four.ema60||four.ema20>=four.ema60*.97);
        if(!window){s.observing();s.reason=s.attempted?"SOL_LAUNCH_EPISODE_CONSUMED":"SOL_4H_LAUNCH_WINDOW_CLOSED";return;}
        if(!convictionPolicy.allows(four.close,four.ema20,four.ema60,four.atr,four.roc3Pct)){
            s.observing();s.reason="SOL_4H_LAUNCH_CONVICTION_PENDING";return;
        }
        if(!Double.isFinite(atr)||atr<=0){s.observing();s.reason="SOL_LAUNCH_INVALID_ATR";return;}
        if(EthMultiTimeframeSnapshot.WARMUP.equals(s.phase)||EthMultiTimeframeSnapshot.COOLDOWN.equals(s.phase))s.observing();
        if(EthMultiTimeframeSnapshot.OBSERVING.equals(s.phase)){
            double priorHigh=highest(s.oneHour,end-1,32),previousEma20=ema(s.oneHour,end-1,20);
            boolean trendQuality=b.close>ema20&&s.lastEma20Slope>0
                    &&(b.close>ema60||ema20>=ema60*.97);
            boolean breakout=trendQuality&&b.close>priorHigh&&bull(b)
                    &&bodyAtr(b,atr)>=.35&&volumeRatio(s.oneHour,end,20)>=.85
                    &&closeLocation(b)>=.60;
            boolean reclaim=trendQuality&&previous.close<=previousEma20&&b.close>ema20
                    &&b.close>previous.high&&b.low>lowest(s.oneHour,end-1,8)
                    &&bull(b)&&bodyAtr(b,atr)>=.25
                    &&volumeRatio(s.oneHour,end,20)>=.75&&closeLocation(b)>=.55;
            if(!breakout&&!reclaim){s.reason="SOL_1H_WAITING_LAUNCH_SETUP";return;}
            // Launch differs deliberately from the mature continuation parser:
            // the first qualified 1H thrust arms 15m immediately. Waiting for a
            // complete 1H pullback would turn this back into the mature strategy.
            s.phase=EthMultiTimeframeSnapshot.ARMED;s.impulseOrigin=lowest(s.oneHour,end-1,24);
            s.impulseHigh=b.high;s.pullbackLow=b.low;s.armedUntil=end+8;s.setupId++;
            s.setupMode=breakout?"BREAKOUT":"RECLAIM";
            s.reason="SOL_1H_LAUNCH_"+s.setupMode+"_ARMED";return;
        }
        if(EthMultiTimeframeSnapshot.IMPULSE.equals(s.phase)){
            s.impulseHigh=Math.max(s.impulseHigh,b.high);
            if(b.close>=previous.close&&b.low>=previous.low){s.reason="SOL_1H_LAUNCH_IMPULSE_EXTENDING";return;}
            s.phase=EthMultiTimeframeSnapshot.PULLBACK;s.pullbackBars=1;s.pullbackLow=b.low;s.reason="SOL_1H_LAUNCH_PULLBACK_STARTED";return;
        }
        if(EthMultiTimeframeSnapshot.PULLBACK.equals(s.phase)){
            s.pullbackBars++;s.pullbackLow=Math.min(s.pullbackLow,b.low);
            double move=s.impulseHigh-s.impulseOrigin,depth=move<=0?2:(s.impulseHigh-s.pullbackLow)/move;
            double atrDepth=(s.impulseHigh-b.close)/atr;
            if(depth>.786||s.pullbackLow<=s.impulseOrigin||b.close<ema60-.25*atr||s.pullbackBars>32){s.attempted=true;s.observing();s.reason="SOL_1H_LAUNCH_PULLBACK_INVALIDATED";return;}
            boolean valid=(depth>=.236&&depth<=.618)||(depth>=.15&&depth<=.70&&atrDepth>=.75&&atrDepth<=4);
            if(s.pullbackBars>=3&&valid){s.phase=EthMultiTimeframeSnapshot.ARMED;s.armedUntil=end+16;s.setupId++;s.reason="SOL_1H_LAUNCH_PULLBACK_ARMED";return;}
            s.reason="SOL_1H_LAUNCH_PULLBACK_ACCUMULATING";return;
        }
        if(EthMultiTimeframeSnapshot.ARMED.equals(s.phase)){
            s.pullbackLow=Math.min(s.pullbackLow,b.low);
            double move=s.impulseHigh-s.impulseOrigin,depth=move<=0?2:(s.impulseHigh-s.pullbackLow)/move;
            if(end>s.armedUntil||depth>.618||s.pullbackLow<=s.impulseOrigin||b.close<ema60-.10*atr){s.attempted=true;s.observing();s.reason="SOL_1H_LAUNCH_ARMED_INVALIDATED";return;}
            s.reason="SOL_1H_LAUNCH_"+s.setupMode+"_WAITING_15M";
        }
    }

    private EthMultiTimeframeSnapshot snapshot(Session s,FourHourContext f){double ready=EthMultiTimeframeSnapshot.ARMED.equals(s.phase)?.90:EthMultiTimeframeSnapshot.PULLBACK.equals(s.phase)?.75:EthMultiTimeframeSnapshot.IMPULSE.equals(s.phase)?.65:EthMultiTimeframeSnapshot.RUNNING.equals(s.phase)?.90:0;return new EthMultiTimeframeSnapshot(f.trend,f.confidence,s.phase,ready,s.setupId,s.pullbackLow,s.impulseHigh,s.lastAtr,s.available1h,s.lastClose,s.lastEma20,s.lastEma20Slope,s.lastEma60,s.lastSwingLow,s.lastSwingLowIndex,s.available4h,f.close,f.ema20,f.ema60,f.atr,s.reason);}
    private FourHourContext context4h(List<HBar>x,int end){if(end<60)return FourHourContext.none(end);HBar b=x.get(end);double e20=ema(x,end,20),e60=ema(x,end,60),past20=ema(x,end-3,20),past60=ema(x,end-6,60),a=atr(x,end,14),roc3=(b.close/x.get(end-3).close-1)*100;double rh=highest(x,end,12),ph=highest(x,end-12,12),rl=lowest(x,end,12),pl=lowest(x,end-12,12);int bull=0,bear=0;if(e20>e60)bull++;else bear++;if(e60>past60)bull++;else bear++;if(b.close>e20)bull++;else bear++;if(rh>ph&&rl>pl)bull++;else if(rh<ph&&rl<pl)bear++;String trend=bull>=3?EthMultiTimeframeSnapshot.FOUR_HOUR_BULL:bear>=3?EthMultiTimeframeSnapshot.FOUR_HOUR_BEAR:(b.close>e20&&e20>past20&&(b.close>e60||e20>=e60*.97)?EthMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP:EthMultiTimeframeSnapshot.FOUR_HOUR_NEUTRAL);return new FourHourContext(trend,bull>=3?bull/4d:bear>=3?bear/4d:.6,end,b.close,e20,e60,e20-past20,a,roc3);}
    private List<HBar> convert(List<TTbookOhlc> rows){List<HBar> out=new ArrayList<HBar>();if(rows!=null)for(TTbookOhlc r:rows){if(r==null)continue;long t=LocalDateTime.parse(r.starttime,TIME).atZone(BEIJING).toInstant().toEpochMilli();out.add(new HBar(t,r.open.doubleValue(),r.high.doubleValue(),r.low.doubleValue(),r.close.doubleValue(),r.volume.doubleValue()));}Collections.sort(out,new Comparator<HBar>(){public int compare(HBar a,HBar b){return Long.compare(a.time,b.time);}});return out;}
    private int available(List<HBar>x,long duration,long cutoff,int current){int i=Math.max(-1,current);while(i+1<x.size()&&x.get(i+1).time+duration<=cutoff)i++;return i;}
    private double ema(List<HBar>x,int end,int p){if(end<0)return Double.NaN;int start=Math.max(0,end-p*4);double v=x.get(start).close,a=2d/(p+1);for(int i=start+1;i<=end;i++)v=x.get(i).close*a+v*(1-a);return v;}
    private double atr(List<HBar>x,int end,int p){double v=0;int n=0;for(int i=Math.max(1,end-p+1);i<=end;i++){HBar b=x.get(i);v+=Math.max(b.high-b.low,Math.max(Math.abs(b.high-x.get(i-1).close),Math.abs(b.low-x.get(i-1).close)));n++;}return n==0?0:v/n;}
    private double highest(List<HBar>x,int end,int n){double v=Double.NEGATIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.max(v,x.get(i).high);return v;}private double lowest(List<HBar>x,int end,int n){double v=Double.POSITIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.min(v,x.get(i).low);return v;}private double volumeRatio(List<HBar>x,int end,int n){double v=0;int start=Math.max(0,end-n+1);for(int i=start;i<=end;i++)v+=x.get(i).volume;v/=Math.max(1,end-start+1);return v<=0?0:x.get(end).volume/v;}private double bodyAtr(HBar b,double a){return a<=0?0:Math.abs(b.close-b.open)/a;}private double closeLocation(HBar b){return b.high<=b.low?.5:(b.close-b.low)/(b.high-b.low);}private boolean bull(HBar b){return b.close>b.open;}private String key(String s){return s==null?"":s.toUpperCase(Locale.ROOT);}
    private static final class HBar{final long time;final double open,high,low,close,volume;HBar(long t,double o,double h,double l,double c,double v){time=t;open=o;high=h;low=l;close=c;volume=v;}}
    private static final class FourHourContext{final String trend;final double confidence;final int index;final double close,ema20,ema60,ema20Slope,atr,roc3Pct;FourHourContext(String t,double c,int i,double x,double a,double b,double slope,double atr,double roc3Pct){trend=t;confidence=c;index=i;close=x;ema20=a;ema60=b;ema20Slope=slope;this.atr=atr;this.roc3Pct=roc3Pct;}static FourHourContext none(int i){return new FourHourContext(EthMultiTimeframeSnapshot.FOUR_HOUR_NEUTRAL,0,i,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN);}}
    private static final class Session{final List<HBar> oneHour,fourHour;int available1h=-1,available4h=-1,launchUntil4h=-1,pullbackBars,armedUntil=-1,cooldownUntil=-1,lastSwingLowIndex=-1,bullish4hBars,bearish4hBars;long setupId;boolean attempted,episodeActive;double impulseOrigin=Double.NaN,impulseHigh=Double.NaN,pullbackLow=Double.NaN,lastAtr=Double.NaN,lastClose=Double.NaN,lastEma20=Double.NaN,lastEma20Slope=Double.NaN,lastEma60=Double.NaN,lastSwingLow=Double.NaN;String setupMode="BREAKOUT",phase=EthMultiTimeframeSnapshot.WARMUP,reason="SOL_LAUNCH_MTF_WARMUP";EthMultiTimeframeSnapshot snapshot=EthMultiTimeframeSnapshot.warmup();Session(List<HBar>a,List<HBar>b){oneHour=a;fourHour=b;}void observing(){phase=EthMultiTimeframeSnapshot.OBSERVING;pullbackBars=0;armedUntil=-1;setupMode="BREAKOUT";impulseOrigin=impulseHigh=pullbackLow=Double.NaN;}}
}
