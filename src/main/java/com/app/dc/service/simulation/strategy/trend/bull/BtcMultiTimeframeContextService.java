package com.app.dc.service.simulation.strategy.trend.bull;

import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;

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

/**
 * BTC-specific higher-timeframe campaign state. 4H determines whether a bull
 * campaign remains alive; 1H owns impulse, pullback and close-confirmed recovery.
 * No ETH strategy state or thresholds are shared here.
 */
@Service
public class BtcMultiTimeframeContextService {
    private static final long FIFTEEN_MINUTES=15*60*1000L,ONE_HOUR=60*60*1000L,FOUR_HOURS=4*ONE_HOUR;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId BEIJING=ZoneId.of("Asia/Shanghai");
    private final Map<String,Session> sessions=new ConcurrentHashMap<String,Session>();

    public void prepare(String symbol,List<TTbookOhlc> oneHour,List<TTbookOhlc> fourHour){
        sessions.put(key(symbol),new Session(convert(oneHour),convert(fourHour)));
    }

    public BtcMultiTimeframeSnapshot update(String symbol,long currentFifteenMinuteOpen){
        Session s=sessions.get(key(symbol));
        if(s==null)return BtcMultiTimeframeSnapshot.warmup();
        long cutoff=currentFifteenMinuteOpen+FIFTEEN_MINUTES;
        s.available4h=available(s.fourHour,FOUR_HOURS,cutoff,s.available4h);
        FourHourContext four=context4h(s.fourHour,s.available4h);
        int next=available(s.oneHour,ONE_HOUR,cutoff,s.available1h);
        while(s.available1h<next){s.available1h++;advanceOneHour(s,s.available1h,four);}
        s.snapshot=snapshot(s,four);
        return s.snapshot;
    }

    public BtcMultiTimeframeSnapshot current(String symbol){
        Session s=sessions.get(key(symbol));return s==null?BtcMultiTimeframeSnapshot.warmup():s.snapshot;
    }
    public void consume(String symbol,long setupId){
        Session s=sessions.get(key(symbol));
        if(s!=null&&s.setupId==setupId){s.phase=BtcMultiTimeframeSnapshot.RUNNING;
            s.reason="BTC_1H_POSITION_RUNNING";s.snapshot=snapshot(s,context4h(s.fourHour,s.available4h));}
    }
    public void tradeClosed(String symbol){
        Session s=sessions.get(key(symbol));if(s==null)return;
        // A stopped 15m execution must not erase the live 4H bull campaign. A short
        // eight-hour pause is enough to demand a fresh 1H base without waiting for
        // another multi-week 96-hour breakout.
        s.resetLifecycle();s.cooldownUntil=s.available1h+8;
        s.phase=BtcMultiTimeframeSnapshot.COOLDOWN;s.reason="BTC_1H_REENTRY_COOLDOWN";
    }
    public void reset(String symbol){sessions.remove(key(symbol));}

    private void advanceOneHour(Session s,int end,FourHourContext four){
        if(end<96||s.available4h<200){s.phase=BtcMultiTimeframeSnapshot.WARMUP;s.reason="BTC_MTF_WARMUP";return;}
        HBar b=s.oneHour.get(end);double atr=atr(s.oneHour,end,14),ema20=ema(s.oneHour,end,20),ema60=ema(s.oneHour,end,60);
        s.lastOneHourAtr=atr;s.lastOneHourClose=b.close;s.lastOneHourEma20=ema20;
        s.lastOneHourEma20Slope=ema20-ema(s.oneHour,end-3,20);s.lastOneHourEma60=ema60;
        if(end<=s.cooldownUntil){s.phase=BtcMultiTimeframeSnapshot.COOLDOWN;s.reason="BTC_1H_REENTRY_COOLDOWN";return;}
        if(!four.allowsLong()){s.resetLifecycle();s.reason="BTC_4H_BULL_CAMPAIGN_ENDED";return;}
        if(!finite(atr)||atr<=0){s.resetLifecycle();s.reason="BTC_1H_INVALID_ATR";return;}
        if(BtcMultiTimeframeSnapshot.WARMUP.equals(s.phase)||BtcMultiTimeframeSnapshot.COOLDOWN.equals(s.phase))s.observing();
        if(BtcMultiTimeframeSnapshot.RUNNING.equals(s.phase)){s.reason="BTC_1H_POSITION_RUNNING";return;}

        if(BtcMultiTimeframeSnapshot.OBSERVING.equals(s.phase)){
            double prior96=highest(s.oneHour,end-1,96),prior24=highest(s.oneHour,end-1,24);
            boolean campaignContinuation=BtcMultiTimeframeSnapshot.FOUR_HOUR_BULL.equals(four.trend)
                    &&ema20>ema60&&s.lastOneHourEma20Slope>0&&b.close>prior24;
            boolean impulse=(b.close>prior96||campaignContinuation)&&bull(b)
                    &&bodyAtr(b,atr)>=.30&&volumeRatio(s.oneHour,end,20)>=.70&&closeLocation(b)>=.60;
            if(!impulse){s.reason="BTC_1H_WAITING_IMPULSE";return;}
            s.phase=BtcMultiTimeframeSnapshot.IMPULSE;s.impulseOrigin=lowest(s.oneHour,end-1,24);
            s.impulseHigh=b.high;s.impulseBar=end;s.reason="BTC_1H_IMPULSE_CONFIRMED";return;
        }
        if(BtcMultiTimeframeSnapshot.IMPULSE.equals(s.phase)){
            s.impulseHigh=Math.max(s.impulseHigh,b.high);HBar previous=s.oneHour.get(end-1);
            if(b.close>=previous.close&&b.low>=previous.low){s.reason="BTC_1H_IMPULSE_EXTENDING";return;}
            s.phase=BtcMultiTimeframeSnapshot.PULLBACK;s.pullbackBars=1;s.pullbackLow=b.low;
            s.recoveryLevel=Math.max(b.high,previous.high);s.reason="BTC_1H_PULLBACK_STARTED";return;
        }
        if(BtcMultiTimeframeSnapshot.PULLBACK.equals(s.phase)){
            s.pullbackBars++;s.pullbackLow=Math.min(s.pullbackLow,b.low);
            s.recoveryLevel=Math.min(s.impulseHigh,Math.max(s.recoveryLevel,b.high));
            double move=s.impulseHigh-s.impulseOrigin,depth=move<=0?2:(s.impulseHigh-s.pullbackLow)/move;
            double atrDepth=(s.impulseHigh-b.close)/atr;
            if(depth>.82||s.pullbackLow<=s.impulseOrigin||b.close<ema60-.50*atr||s.pullbackBars>64){
                s.resetLifecycle();s.reason="BTC_1H_PULLBACK_INVALIDATED";return;
            }
            boolean valid=(depth>=.20&&depth<=.70)||(depth>=.15&&depth<=.75&&atrDepth>=.60&&atrDepth<=4.5);
            if(s.pullbackBars>=3&&valid){s.phase=BtcMultiTimeframeSnapshot.ARMED;s.armedUntil=end+32;
                s.setupId++;s.reason="BTC_1H_PULLBACK_ARMED";return;}
            s.reason="BTC_1H_PULLBACK_ACCUMULATING";return;
        }
        if(BtcMultiTimeframeSnapshot.ARMED.equals(s.phase)){
            s.pullbackLow=Math.min(s.pullbackLow,b.low);
            double move=s.impulseHigh-s.impulseOrigin,depth=move<=0?2:(s.impulseHigh-s.pullbackLow)/move;
            if(end>s.armedUntil){s.resetLifecycle();s.reason="BTC_1H_ARMED_EXPIRED";return;}
            if(depth>.82||s.pullbackLow<=s.impulseOrigin||b.close<ema60-.50*atr){
                s.resetLifecycle();s.reason="BTC_1H_ARMED_INVALIDATED";return;
            }
            HBar previous=s.oneHour.get(end-1);
            boolean recovered=bull(b)&&b.close>ema20&&b.close>previous.high
                    &&b.close>s.recoveryLevel&&closeLocation(b)>=.60&&volumeRatio(s.oneHour,end,20)>=.65;
            if(recovered){s.recoveryConfirmed=true;s.reason="BTC_1H_RECOVERY_CONFIRMED";}
            else s.reason="BTC_1H_WAITING_CLOSE_RECOVERY";
        }
    }

    private BtcMultiTimeframeSnapshot snapshot(Session s,FourHourContext four){
        double ready=BtcMultiTimeframeSnapshot.ARMED.equals(s.phase)?(s.recoveryConfirmed?1:.90)
                :BtcMultiTimeframeSnapshot.PULLBACK.equals(s.phase)?.75
                :BtcMultiTimeframeSnapshot.IMPULSE.equals(s.phase)?.65
                :BtcMultiTimeframeSnapshot.RUNNING.equals(s.phase)?.90:0;
        if(BtcMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP.equals(four.trend))ready=Math.max(0,ready-.10);
        return new BtcMultiTimeframeSnapshot(four.trend,four.confidence,s.phase,ready,s.setupId,
                s.pullbackLow,s.impulseHigh,s.lastOneHourAtr,s.available1h,s.lastOneHourClose,
                s.lastOneHourEma20,s.lastOneHourEma20Slope,s.lastOneHourEma60,s.available4h,
                four.close,four.ema20,four.ema60,four.atr,s.recoveryLevel,s.recoveryConfirmed,s.reason);
    }

    private FourHourContext context4h(List<HBar> bars,int end){
        if(end<200)return FourHourContext.neutral(end);
        HBar b=bars.get(end);double e20=ema(bars,end,20),e60=ema(bars,end,60),past60=ema(bars,end-6,60);
        double e200=ema(bars,end,200),past200=ema(bars,end-12,200),a=atr(bars,end,14);
        double recentHigh=highest(bars,end,12),priorHigh=highest(bars,end-12,12);
        double recentLow=lowest(bars,end,12),priorLow=lowest(bars,end-12,12);
        int bull=0,bear=0;
        if(e20>e60)bull++;else bear++;if(e60>past60)bull++;else bear++;
        if(b.close>e20)bull++;else bear++;if(recentHigh>priorHigh&&recentLow>priorLow)bull++;
        else if(recentHigh<priorHigh&&recentLow<priorLow)bear++;
        // The campaign survives ordinary 4H pullbacks while the 200 EMA and 60 EMA
        // still rise. This is the memory that the previous ETH-derived logic lacked.
        boolean secularBull=b.close>e200*.985&&e200>past200&&e60>past60;
        String trend;double confidence;
        if(secularBull&&bull>=2){trend=BtcMultiTimeframeSnapshot.FOUR_HOUR_BULL;confidence=Math.max(.65,bull/4d);}
        else if(secularBull&&b.close>e20&&e20>ema(bars,end-3,20)){
            trend=BtcMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP;confidence=.60;
        }else if(bear>=3){trend=BtcMultiTimeframeSnapshot.FOUR_HOUR_BEAR;confidence=bear/4d;}
        else {trend=BtcMultiTimeframeSnapshot.FOUR_HOUR_NEUTRAL;confidence=.50;}
        return new FourHourContext(trend,confidence,end,b.close,e20,e60,a);
    }

    private List<HBar> convert(List<TTbookOhlc> rows){
        List<HBar> out=new ArrayList<HBar>();if(rows!=null)for(TTbookOhlc r:rows){if(r==null)continue;
            long t=LocalDateTime.parse(r.starttime,TIME).atZone(BEIJING).toInstant().toEpochMilli();
            out.add(new HBar(t,r.open.doubleValue(),r.high.doubleValue(),r.low.doubleValue(),r.close.doubleValue(),r.volume.doubleValue()));}
        Collections.sort(out,new Comparator<HBar>(){public int compare(HBar a,HBar b){return Long.compare(a.openTime,b.openTime);}});return out;
    }
    private int available(List<HBar>x,long duration,long cutoff,int current){int i=Math.max(-1,current);while(i+1<x.size()&&x.get(i+1).openTime+duration<=cutoff)i++;return i;}
    private double ema(List<HBar>x,int end,int p){if(end<0)return Double.NaN;int start=Math.max(0,end-p*4);double v=x.get(start).close,a=2d/(p+1d);for(int i=start+1;i<=end;i++)v=x.get(i).close*a+v*(1-a);return v;}
    private double atr(List<HBar>x,int end,int p){int start=Math.max(1,end-p+1),n=0;double v=0;for(int i=start;i<=end;i++){HBar b=x.get(i);v+=Math.max(b.high-b.low,Math.max(Math.abs(b.high-x.get(i-1).close),Math.abs(b.low-x.get(i-1).close)));n++;}return n==0?0:v/n;}
    private double highest(List<HBar>x,int end,int n){double v=Double.NEGATIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.max(v,x.get(i).high);return v;}
    private double lowest(List<HBar>x,int end,int n){double v=Double.POSITIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.min(v,x.get(i).low);return v;}
    private double volumeRatio(List<HBar>x,int end,int n){int start=Math.max(0,end-n+1),count=end-start+1;double avg=0;for(int i=start;i<=end;i++)avg+=x.get(i).volume;avg/=Math.max(1,count);return avg<=0?0:x.get(end).volume/avg;}
    private double bodyAtr(HBar b,double a){return a<=0?0:Math.abs(b.close-b.open)/a;}
    private double closeLocation(HBar b){return b.high<=b.low?.5:(b.close-b.low)/(b.high-b.low);}
    private boolean bull(HBar b){return b.close>b.open;}private boolean finite(double v){return Double.isFinite(v);}
    private String key(String s){return s==null?"":s.toUpperCase(Locale.ROOT);}

    private static final class HBar{final long openTime;final double open,high,low,close,volume;HBar(long t,double o,double h,double l,double c,double v){openTime=t;open=o;high=h;low=l;close=c;volume=v;}}
    private static final class FourHourContext{final String trend;final double confidence;final int index;final double close,ema20,ema60,atr;FourHourContext(String t,double c,int i,double x,double e20,double e60,double a){trend=t;confidence=c;index=i;close=x;ema20=e20;ema60=e60;atr=a;}boolean allowsLong(){return BtcMultiTimeframeSnapshot.FOUR_HOUR_BULL.equals(trend)||BtcMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP.equals(trend);}static FourHourContext neutral(int i){return new FourHourContext(BtcMultiTimeframeSnapshot.FOUR_HOUR_NEUTRAL,0,i,Double.NaN,Double.NaN,Double.NaN,Double.NaN);}}
    private static final class Session{
        final List<HBar> oneHour,fourHour;int available1h=-1,available4h=-1,impulseBar=-1,pullbackBars,
                armedUntil=-1,cooldownUntil=-1;long setupId;boolean recoveryConfirmed;
        double impulseOrigin=Double.NaN,impulseHigh=Double.NaN,pullbackLow=Double.NaN,recoveryLevel=Double.NaN,
                lastOneHourAtr=Double.NaN,lastOneHourClose=Double.NaN,lastOneHourEma20=Double.NaN,
                lastOneHourEma20Slope=Double.NaN,lastOneHourEma60=Double.NaN;
        String phase=BtcMultiTimeframeSnapshot.WARMUP,reason="BTC_MTF_WARMUP";
        BtcMultiTimeframeSnapshot snapshot=BtcMultiTimeframeSnapshot.warmup();
        Session(List<HBar>a,List<HBar>b){oneHour=a;fourHour=b;}
        void observing(){phase=BtcMultiTimeframeSnapshot.OBSERVING;impulseBar=-1;pullbackBars=0;armedUntil=-1;
            recoveryConfirmed=false;
            impulseOrigin=impulseHigh=pullbackLow=recoveryLevel=Double.NaN;}
        void resetLifecycle(){observing();}
    }
}
