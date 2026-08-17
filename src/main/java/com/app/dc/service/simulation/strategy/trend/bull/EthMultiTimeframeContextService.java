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
 * Advances ETH 4H context and 1H wave lifecycle from independently downloaded bars.
 * A higher-timeframe bar becomes visible only after its full duration has elapsed.
 */
@Service
public class EthMultiTimeframeContextService {
    private static final long FIFTEEN_MINUTES=15*60*1000L,ONE_HOUR=60*60*1000L,FOUR_HOURS=4*ONE_HOUR;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId BEIJING=ZoneId.of("Asia/Shanghai");
    private final Map<String,Session> sessions=new ConcurrentHashMap<String,Session>();

    public void prepare(String symbol,List<TTbookOhlc> oneHour,List<TTbookOhlc> fourHour){
        sessions.put(key(symbol),new Session(convert(oneHour),convert(fourHour),
                "BTCUSDT".equalsIgnoreCase(symbol)));
    }

    public EthMultiTimeframeSnapshot update(String symbol,long currentFifteenMinuteOpen){
        Session s=sessions.get(key(symbol));if(s==null)return EthMultiTimeframeSnapshot.warmup();
        long cutoff=currentFifteenMinuteOpen+FIFTEEN_MINUTES;
        s.available4h=available(s.fourHour,FOUR_HOURS,cutoff,s.available4h);
        FourHourContext four=context4h(s.fourHour,s.available4h,s.btc);
        int next=available(s.oneHour,ONE_HOUR,cutoff,s.available1h);
        while(s.available1h<next){s.available1h++;advanceOneHour(s,s.available1h,four);}
        s.snapshot=snapshot(s,four);
        return s.snapshot;
    }

    public void consume(String symbol,long setupId){Session s=sessions.get(key(symbol));if(s!=null&&s.setupId==setupId){s.phase=EthMultiTimeframeSnapshot.RUNNING;s.snapshot=snapshot(s,context4h(s.fourHour,s.available4h,s.btc));}}
    public EthMultiTimeframeSnapshot current(String symbol){Session s=sessions.get(key(symbol));return s==null?EthMultiTimeframeSnapshot.warmup():s.snapshot;}
    public double currentOneHourAtr(String symbol){Session s=sessions.get(key(symbol));return s==null?Double.NaN:s.lastOneHourAtr;}
    public void tradeClosed(String symbol){Session s=sessions.get(key(symbol));if(s!=null){s.resetLifecycle();s.cooldownUntil=s.available1h+24;s.phase=EthMultiTimeframeSnapshot.COOLDOWN;}}
    public void reset(String symbol){sessions.remove(key(symbol));}

    private void advanceOneHour(Session s,int end,FourHourContext four){
        if(end<96||s.available4h<60){s.phase=EthMultiTimeframeSnapshot.WARMUP;s.reason="ETH_MTF_WARMUP";return;}
        if(end<=s.cooldownUntil){s.phase=EthMultiTimeframeSnapshot.COOLDOWN;s.reason="ETH_1H_TRADE_COOLDOWN";return;}
        if(!four.allowsLong()){s.resetLifecycle();s.reason="ETH_4H_DIRECTION_BLOCKED";return;}
        HBar b=s.oneHour.get(end);double atr=atr(s.oneHour,end,14),ema20=ema(s.oneHour,end,20),ema60=ema(s.oneHour,end,60);s.lastOneHourAtr=atr;
        s.lastOneHourClose=b.close;s.lastOneHourEma20Slope=ema20-ema(s.oneHour,end-3,20);s.lastOneHourEma20=ema20;s.lastOneHourEma60=ema60;
        if(end>=2){HBar left=s.oneHour.get(end-2),pivot=s.oneHour.get(end-1);
            if(pivot.low<left.low&&pivot.low<b.low){s.lastOneHourSwingLow=pivot.low;s.lastOneHourSwingLowIndex=end-1;}}
        if(!finite(atr)||atr<=0){s.resetLifecycle();s.reason="ETH_1H_INVALID_ATR";return;}
        if(EthMultiTimeframeSnapshot.WARMUP.equals(s.phase)||EthMultiTimeframeSnapshot.COOLDOWN.equals(s.phase))s.observing();
        if(EthMultiTimeframeSnapshot.RUNNING.equals(s.phase)){s.reason="ETH_1H_POSITION_RUNNING";return;}
        if(EthMultiTimeframeSnapshot.OBSERVING.equals(s.phase)){
            double priorHigh=highest(s.oneHour,end-1,96);
            boolean impulse=b.close>priorHigh&&bull(b)&&bodyAtr(b,atr)>=.4&&volumeRatio(s.oneHour,end,20)>=.8&&closeLocation(b)>=.65;
            if(!impulse){s.reason="ETH_1H_WAITING_IMPULSE";return;}
            s.phase=EthMultiTimeframeSnapshot.IMPULSE;s.impulseBar=end;s.impulseOrigin=lowest(s.oneHour,end-1,24);
            s.impulseHigh=b.high;s.reason="ETH_1H_IMPULSE_CONFIRMED";return;
        }
        if(EthMultiTimeframeSnapshot.IMPULSE.equals(s.phase)){
            s.impulseHigh=Math.max(s.impulseHigh,b.high);
            HBar previous=s.oneHour.get(end-1);
            if(b.close>=previous.close&&b.low>=previous.low){s.reason="ETH_1H_IMPULSE_EXTENDING";return;}
            s.phase=EthMultiTimeframeSnapshot.PULLBACK;s.pullbackBars=1;s.pullbackLow=b.low;s.reason="ETH_1H_PULLBACK_STARTED";return;
        }
        if(EthMultiTimeframeSnapshot.PULLBACK.equals(s.phase)){
            s.pullbackBars++;s.pullbackLow=Math.min(s.pullbackLow,b.low);
            double move=s.impulseHigh-s.impulseOrigin,depth=move<=0?2:(s.impulseHigh-s.pullbackLow)/move;
            double atrDepth=(s.impulseHigh-b.close)/atr;
            if(depth>.786||s.pullbackLow<=s.impulseOrigin||b.close<ema60-.25*atr||s.pullbackBars>48){s.resetLifecycle();s.reason="ETH_1H_PULLBACK_INVALIDATED";return;}
            boolean valid=(depth>=.236&&depth<=.618)||(depth>=.15&&depth<=.70&&atrDepth>=.75&&atrDepth<=4);
            if(s.pullbackBars>=3&&valid){s.phase=EthMultiTimeframeSnapshot.ARMED;s.armedUntil=end+16;s.setupId++;s.reason="ETH_1H_PULLBACK_ARMED";return;}
            s.reason="ETH_1H_PULLBACK_ACCUMULATING";return;
        }
        if(EthMultiTimeframeSnapshot.ARMED.equals(s.phase)){
            s.pullbackLow=Math.min(s.pullbackLow,b.low);
            double move=s.impulseHigh-s.impulseOrigin,depth=move<=0?2:(s.impulseHigh-s.pullbackLow)/move;
            if(end>s.armedUntil){s.resetLifecycle();s.reason="ETH_1H_ARMED_EXPIRED";return;}
            if(depth>.786||s.pullbackLow<=s.impulseOrigin||b.close<ema60-.25*atr){s.resetLifecycle();s.reason="ETH_1H_ARMED_INVALIDATED";return;}
            s.reason="ETH_1H_WAITING_15M_TRIGGER";
        }
    }

    private EthMultiTimeframeSnapshot snapshot(Session s,FourHourContext four){
        double ready=EthMultiTimeframeSnapshot.ARMED.equals(s.phase)?.90:EthMultiTimeframeSnapshot.PULLBACK.equals(s.phase)?.75:EthMultiTimeframeSnapshot.IMPULSE.equals(s.phase)?.65:EthMultiTimeframeSnapshot.RUNNING.equals(s.phase)?.90:0;
        if(EthMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP.equals(four.trend))ready=Math.max(0,ready-.10);
        return new EthMultiTimeframeSnapshot(four.trend,four.confidence,s.phase,ready,s.setupId,
                s.pullbackLow,s.impulseHigh,s.lastOneHourAtr,s.available1h,s.lastOneHourClose,
                s.lastOneHourEma20,s.lastOneHourEma20Slope,s.lastOneHourEma60,s.lastOneHourSwingLow,s.lastOneHourSwingLowIndex,
                s.available4h,four.close,four.ema20,four.ema60,four.atr,s.reason);
    }

    private FourHourContext context4h(List<HBar> bars,int end,boolean btc){
        if(end<60)return new FourHourContext(EthMultiTimeframeSnapshot.FOUR_HOUR_NEUTRAL,0,
                end,Double.NaN,Double.NaN,Double.NaN,Double.NaN);
        HBar b=bars.get(end);double e20=ema(bars,end,20),e60=ema(bars,end,60),past60=ema(bars,end-6,60),a=atr(bars,end,14);
        if(btc){
            if(end<200)return new FourHourContext(EthMultiTimeframeSnapshot.FOUR_HOUR_NEUTRAL,0,
                    end,b.close,e20,e60,a);
            double e200=ema(bars,end,200),past200=ema(bars,end-12,200);
            if(!(b.close>e200&&e200>past200))
                return new FourHourContext(EthMultiTimeframeSnapshot.FOUR_HOUR_BEAR,.75,
                        end,b.close,e20,e60,a);
        }
        double recentHigh=highest(bars,end,12),priorHigh=highest(bars,end-12,12),recentLow=lowest(bars,end,12),priorLow=lowest(bars,end-12,12);
        int bull=0,bear=0;if(e20>e60)bull++;else bear++;if(e60>past60)bull++;else bear++;if(b.close>e20)bull++;else bear++;if(recentHigh>priorHigh&&recentLow>priorLow)bull++;else if(recentHigh<priorHigh&&recentLow<priorLow)bear++;
        if(bull>=3)return new FourHourContext(EthMultiTimeframeSnapshot.FOUR_HOUR_BULL,bull/4d,end,b.close,e20,e60,a);
        if(bear>=3)return new FourHourContext(EthMultiTimeframeSnapshot.FOUR_HOUR_BEAR,bear/4d,end,b.close,e20,e60,a);
        double e20past=ema(bars,end-3,20);
        if(b.close>e20&&e20>e20past&&e20>=e60*.985)return new FourHourContext(EthMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP,.55,end,b.close,e20,e60,a);
        return new FourHourContext(EthMultiTimeframeSnapshot.FOUR_HOUR_NEUTRAL,.5,end,b.close,e20,e60,a);
    }

    private List<HBar> convert(List<TTbookOhlc> rows){List<HBar> out=new ArrayList<HBar>();if(rows!=null)for(TTbookOhlc r:rows){if(r==null)continue;long t=LocalDateTime.parse(r.starttime,TIME).atZone(BEIJING).toInstant().toEpochMilli();out.add(new HBar(t,r.open.doubleValue(),r.high.doubleValue(),r.low.doubleValue(),r.close.doubleValue(),r.volume.doubleValue()));}Collections.sort(out,new Comparator<HBar>(){public int compare(HBar a,HBar b){return Long.compare(a.openTime,b.openTime);}});return out;}
    private int available(List<HBar> bars,long duration,long cutoff,int current){int i=Math.max(-1,current);while(i+1<bars.size()&&bars.get(i+1).openTime+duration<=cutoff)i++;return i;}
    private double ema(List<HBar> x,int end,int p){if(end<0)return Double.NaN;int start=Math.max(0,end-p*4),i=start;double v=x.get(i).close,a=2d/(p+1d);for(i++;i<=end;i++)v=x.get(i).close*a+v*(1-a);return v;}
    private double atr(List<HBar>x,int end,int p){int start=Math.max(1,end-p+1);double v=0;int n=0;for(int i=start;i<=end;i++){HBar b=x.get(i);v+=Math.max(b.high-b.low,Math.max(Math.abs(b.high-x.get(i-1).close),Math.abs(b.low-x.get(i-1).close)));n++;}return n==0?0:v/n;}
    private double highest(List<HBar>x,int end,int n){double v=Double.NEGATIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.max(v,x.get(i).high);return v;}
    private double lowest(List<HBar>x,int end,int n){double v=Double.POSITIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.min(v,x.get(i).low);return v;}
    private double volumeRatio(List<HBar>x,int end,int n){double avg=0;int start=Math.max(0,end-n+1),count=end-start+1;for(int i=start;i<=end;i++)avg+=x.get(i).volume;avg/=Math.max(1,count);return avg<=0?0:x.get(end).volume/avg;}
    private double bodyAtr(HBar b,double atr){return atr<=0?0:Math.abs(b.close-b.open)/atr;}
    private double closeLocation(HBar b){return b.high<=b.low?.5:(b.close-b.low)/(b.high-b.low);}
    private boolean bull(HBar b){return b.close>b.open;}
    private boolean finite(double v){return Double.isFinite(v);}
    private String key(String s){return s==null?"":s.toUpperCase(Locale.ROOT);}

    private static final class HBar{final long openTime;final double open,high,low,close,volume;HBar(long t,double o,double h,double l,double c,double v){openTime=t;open=o;high=h;low=l;close=c;volume=v;}}
    private static final class FourHourContext{final String trend;final double confidence;final int index;final double close,ema20,ema60,atr;FourHourContext(String t,double c,int i,double x,double e20,double e60,double a){trend=t;confidence=c;index=i;close=x;ema20=e20;ema60=e60;atr=a;}boolean allowsLong(){return EthMultiTimeframeSnapshot.FOUR_HOUR_BULL.equals(trend)||EthMultiTimeframeSnapshot.FOUR_HOUR_TRANSITION_UP.equals(trend);}}
    private static final class Session{
        final List<HBar> oneHour,fourHour;final boolean btc;int available1h=-1,available4h=-1,impulseBar=-1,pullbackBars,armedUntil=-1,cooldownUntil=-1,lastOneHourSwingLowIndex=-1;long setupId;double impulseOrigin=Double.NaN,impulseHigh=Double.NaN,pullbackLow=Double.NaN,lastOneHourAtr=Double.NaN,lastOneHourClose=Double.NaN,lastOneHourEma20=Double.NaN,lastOneHourEma20Slope=Double.NaN,lastOneHourEma60=Double.NaN,lastOneHourSwingLow=Double.NaN;String phase=EthMultiTimeframeSnapshot.WARMUP,reason="ETH_MTF_WARMUP";EthMultiTimeframeSnapshot snapshot=EthMultiTimeframeSnapshot.warmup();
        Session(List<HBar>a,List<HBar>b,boolean btc){oneHour=a;fourHour=b;this.btc=btc;}
        void observing(){phase=EthMultiTimeframeSnapshot.OBSERVING;impulseBar=-1;pullbackBars=0;armedUntil=-1;impulseOrigin=impulseHigh=pullbackLow=Double.NaN;}
        void resetLifecycle(){observing();}
    }
}
