package com.app.dc.service.simulation.strategy.trend.bear;

import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Streaming daily ETH backdrop using only daily bars closed before the current 15m bar. */
@Service
public class EthDailyBearContextService {
    private static final long M15=900000L,D1=86400000L;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId BEIJING=ZoneId.of("Asia/Shanghai");
    private final Map<String,Session> sessions=new ConcurrentHashMap<String,Session>();

    public void prepare(String symbol,List<TTbookOhlc> rows){sessions.put(key(symbol),new Session(convert(rows)));}
    public EthDailyBearContextSnapshot update(String symbol,long current15Open){
        Session s=sessions.get(key(symbol));if(s==null)return EthDailyBearContextSnapshot.warmup();
        long cutoff=current15Open+M15;while(s.available+1<s.bars.size()&&s.bars.get(s.available+1).time+D1<=cutoff)s.available++;
        if(s.available==s.lastEvaluated)return s.snapshot;s.lastEvaluated=s.available;
        EthDailyBearContextSnapshot raw=classify(s.bars,s.available);
        if("ETH_DAILY_FAILED_HIGH_REVERSAL".equals(raw.reason))s.failedHighRiskUntil=s.available+10;
        if(s.available<=s.failedHighRiskUntil
                &&EthDailyBearContextSnapshot.BULL_WEAKENING.equals(raw.state))
            s.snapshot=new EthDailyBearContextSnapshot(EthDailyBearContextSnapshot.BEAR_RISK,.76,
                    raw.close,raw.ema20,raw.ema60,raw.atr,raw.barIndex,"ETH_DAILY_FAILED_HIGH_RISK_ACTIVE");
        else{s.snapshot=raw;if(EthDailyBearContextSnapshot.BULL.equals(raw.state))s.failedHighRiskUntil=-1;}
        return s.snapshot;
    }
    public EthDailyBearContextSnapshot current(String symbol){Session s=sessions.get(key(symbol));return s==null?EthDailyBearContextSnapshot.warmup():s.snapshot;}
    public void reset(String symbol){sessions.remove(key(symbol));}

    private EthDailyBearContextSnapshot classify(List<DBar>x,int end){
        if(end<60)return EthDailyBearContextSnapshot.warmup();
        DBar b=x.get(end);double e20=ema(x,end,20),e60=ema(x,end,60),p20=ema(x,end-3,20),p60=ema(x,end-5,60),a=atr(x,end,14);
        double rh=highest(x,end,10),ph=highest(x,end-10,10),rl=lowest(x,end,10),pl=lowest(x,end-10,10);
        boolean rising=rh>ph&&rl>pl,falling=rh<ph&&rl<pl;
        double recent20High=highest(x,end-1,20);
        double closeLocation=b.high<=b.low?.5:(b.close-b.low)/(b.high-b.low);
        boolean failedHighReversal=b.close<b.open&&a>0&&(b.open-b.close)/a>=1.00
                &&closeLocation<=.25&&recent20High-b.high<=.75*a
                &&b.close<x.get(end-1).close;
        // A failed high is the exception that invalidates an otherwise still
        // bullish moving-average state, so it must be evaluated first.
        if(failedHighReversal)
            return snap(EthDailyBearContextSnapshot.BEAR_RISK,.78,b,e20,e60,a,end,
                    "ETH_DAILY_FAILED_HIGH_REVERSAL");
        if(b.close>e20&&e20>e60&&e20>p20&&e60>p60&&rising)
            return snap(EthDailyBearContextSnapshot.BULL,.9,b,e20,e60,a,end,"ETH_DAILY_BULL_CONFIRMED");
        if(e20<e60&&e60<p60&&b.close<e20&&falling)
            return snap(EthDailyBearContextSnapshot.BEAR,.9,b,e20,e60,a,end,"ETH_DAILY_BEAR_CONFIRMED");
        double prior10Low=lowest(x,end-1,10),prior20Low=lowest(x,end-1,20);
        boolean decisive=b.close<b.open&&a>0&&(b.open-b.close)/a>=.8;
        boolean consecutiveDown=x.get(end-2).close>x.get(end-1).close&&x.get(end-1).close>b.close;
        // A primary decline starts before EMA20 crosses EMA60.  Treat a close
        // below the prior ten-day structure while EMA20 is rolling over as
        // BEAR_RISK, so the execution layers can observe the first leg instead
        // of joining only near the final capitulation.
        boolean earlyStructuralBreak=e20<p20&&b.close<e20&&b.close<prior10Low
                &&(decisive||consecutiveDown||falling);
        if(earlyStructuralBreak||(e20<p20&&(b.close<e60||b.close<prior20Low)
                &&(decisive||consecutiveDown||falling)))
            return snap(EthDailyBearContextSnapshot.BEAR_RISK,.72,b,e20,e60,a,end,"ETH_DAILY_BEAR_RISK");
        return snap(EthDailyBearContextSnapshot.BULL_WEAKENING,.55,b,e20,e60,a,end,
                e20>e60?"ETH_DAILY_BULL_WEAKENING":"ETH_DAILY_MIXED_WEAKENING");
    }
    private EthDailyBearContextSnapshot snap(String state,double c,DBar b,double e20,double e60,double a,int i,String reason){return new EthDailyBearContextSnapshot(state,c,b.close,e20,e60,a,i,reason);}
    private List<DBar> convert(List<TTbookOhlc> rows){List<DBar>o=new ArrayList<DBar>();if(rows!=null)for(TTbookOhlc r:rows){if(r==null)continue;long t=LocalDateTime.parse(r.starttime,TIME).atZone(BEIJING).toInstant().toEpochMilli();o.add(new DBar(t,r.open.doubleValue(),r.high.doubleValue(),r.low.doubleValue(),r.close.doubleValue()));}Collections.sort(o,new Comparator<DBar>(){public int compare(DBar a,DBar b){return Long.compare(a.time,b.time);}});return o;}
    private double ema(List<DBar>x,int end,int p){int start=Math.max(0,end-p*4);double v=x.get(start).close,a=2d/(p+1);for(int i=start+1;i<=end;i++)v=x.get(i).close*a+v*(1-a);return v;}
    private double atr(List<DBar>x,int end,int p){double v=0;int n=0;for(int i=Math.max(1,end-p+1);i<=end;i++){DBar b=x.get(i);v+=Math.max(b.high-b.low,Math.max(Math.abs(b.high-x.get(i-1).close),Math.abs(b.low-x.get(i-1).close)));n++;}return n==0?0:v/n;}
    private double highest(List<DBar>x,int end,int n){double v=Double.NEGATIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.max(v,x.get(i).high);return v;}
    private double lowest(List<DBar>x,int end,int n){double v=Double.POSITIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.min(v,x.get(i).low);return v;}
    private String key(String s){return s==null?"":s.toUpperCase(Locale.ROOT);}
    private static final class Session{final List<DBar>bars;int available=-1,lastEvaluated=-2,failedHighRiskUntil=-1;EthDailyBearContextSnapshot snapshot=EthDailyBearContextSnapshot.warmup();Session(List<DBar>x){bars=x;}}
    private static final class DBar{final long time;final double open,high,low,close;DBar(long t,double o,double h,double l,double c){time=t;open=o;high=h;low=l;close=c;}}
}
