package com.app.dc.service.simulation.strategy.trend.bear;

import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Independent closed-bar 4H/1H state for ETH bearish continuation. */
@Service
public class EthBearMultiTimeframeContextService {
    private static final long M15=900000L,H1=3600000L,H4=14400000L;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId BEIJING=ZoneId.of("Asia/Shanghai");
    private final Map<String,Session> sessions=new ConcurrentHashMap<String,Session>();
    @Autowired(required=false) private EthDailyBearContextService dailyContext;

    public void prepare(String symbol,List<TTbookOhlc> oneHour,List<TTbookOhlc> fourHour){sessions.put(key(symbol),new Session(convert(oneHour),convert(fourHour)));}
    public void prepare(String symbol,List<TTbookOhlc> daily,List<TTbookOhlc> oneHour,List<TTbookOhlc> fourHour){
        prepare(symbol,oneHour,fourHour);if(dailyContext!=null)dailyContext.prepare(symbol,daily);
    }
    public EthBearMultiTimeframeSnapshot update(String symbol,long current15Open){
        Session s=sessions.get(key(symbol));if(s==null)return EthBearMultiTimeframeSnapshot.warmup();long cutoff=current15Open+M15;
        EthDailyBearContextSnapshot daily=dailyContext==null?EthDailyBearContextSnapshot.warmup():dailyContext.update(symbol,current15Open);
        s.available4h=available(s.fourHour,H4,cutoff,s.available4h);FourContext four=context4h(s.fourHour,s.available4h,daily);
        boolean campaignWasActive=s.bearCampaignActive;
        updateBearCampaign(s,four,daily);
        // The direct breakdown path is a one-time campaign opener.  Later
        // structural lows must go through the 1H pullback/re-entry lifecycle.
        boolean distribution=daily!=null&&EthDailyBearContextSnapshot.DISTRIBUTION_RISK.equals(daily.state);
        if((!campaignWasActive&&s.bearCampaignActive)||(four.fastBreakdown&&!distribution)){
            s.fastBreakdownUntil4h=Math.max(s.fastBreakdownUntil4h,s.available4h+2);
            s.lastFastBreakdown4h=s.available4h;
        }
        // A distribution hypothesis may reserve execution only for its first
        // 4H break.  If no structural short actually consumes that window, it
        // must not silently own later routing for days or weeks.
        if(s.distributionCampaign&&!s.campaignOpened&&s.available4h>s.fastBreakdownUntil4h)
            s.endCampaign();
        int next=available(s.oneHour,H1,cutoff,s.available1h);while(s.available1h<next){s.available1h++;advance(s,s.available1h,four,daily);}
        s.snapshot=snapshot(s,four,daily);return s.snapshot;
    }
    public EthBearMultiTimeframeSnapshot current(String symbol){Session s=sessions.get(key(symbol));return s==null?EthBearMultiTimeframeSnapshot.warmup():s.snapshot;}
    public void consume(String symbol,long setup){Session s=sessions.get(key(symbol));
        // Fast 4H campaign openers use a synthetic negative setup id; they own
        // the same RUNNING lifecycle even though they did not originate from
        // the 1H pullback state's setup id.
        if(s!=null&&(s.setupId==setup||setup<0)){if(setup<0&&s.distributionCampaign)s.campaignOpened=true;s.phase=EthBearMultiTimeframeSnapshot.RUNNING;s.snapshot=snapshot(s,context4h(s.fourHour,s.available4h,dailyContext==null?EthDailyBearContextSnapshot.warmup():dailyContext.current(symbol)),dailyContext==null?EthDailyBearContextSnapshot.warmup():dailyContext.current(symbol));}}
    public void tradeClosed(String symbol){tradeClosed(symbol,false);}
    public void tradeClosed(String symbol,boolean profitProtected){Session s=sessions.get(key(symbol));if(s!=null){s.observing();s.reentryMode=profitProtected;s.cooldownUntil=s.available1h+(profitProtected?12:24);s.phase=EthBearMultiTimeframeSnapshot.COOLDOWN;}}
    public void reset(String symbol){sessions.remove(key(symbol));if(dailyContext!=null)dailyContext.reset(symbol);}

    private void advance(Session s,int end,FourContext four,EthDailyBearContextSnapshot daily){
        if(end<96||s.available4h<60){s.phase=EthBearMultiTimeframeSnapshot.WARMUP;s.reason="ETH_BEAR_MTF_WARMUP";return;}
        if(daily==null||EthDailyBearContextSnapshot.WARMUP.equals(daily.state)){s.phase=EthBearMultiTimeframeSnapshot.WARMUP;s.reason="ETH_BEAR_DAILY_WARMUP";return;}
        if(end<=s.cooldownUntil){s.phase=EthBearMultiTimeframeSnapshot.COOLDOWN;s.reason="ETH_BEAR_1H_TRADE_COOLDOWN";return;}
        if(!daily.allowsDefensiveShort()){s.observing();s.reason="ETH_BEAR_DAILY_BULL_BLOCKED";return;}
        boolean fastActive=s.bearCampaignActive||s.available4h<=s.fastBreakdownUntil4h;
        if(!four.allowsShort()&&!fastActive){s.observing();s.reason="ETH_BEAR_4H_DIRECTION_BLOCKED";return;}
        HBar b=s.oneHour.get(end);double atr=atr(s.oneHour,end,14),e20=ema(s.oneHour,end,20),e60=ema(s.oneHour,end,60);
        s.lastAtr=atr;s.lastClose=b.close;s.lastEma20=e20;s.lastEma20Slope=e20-ema(s.oneHour,end-3,20);s.lastEma60=e60;
        if(!finite(atr)||atr<=0){s.observing();s.reason="ETH_BEAR_1H_INVALID_ATR";return;}
        HBar previous=s.oneHour.get(end-1);
        s.oneHourStrongMomentum=bear(b)&&b.close<previous.low&&b.close<e20
                &&s.lastEma20Slope<0&&bodyAtr(b,atr)>=.6&&volumeRatio(s.oneHour,end,20)>=1.0
                &&closeLocation(b)<=.30;
        // Before its first real trade, a distribution campaign is allowed to
        // feed only the bounded direct A-wave trigger.  It must not manufacture
        // ordinary 1H continuation setups merely because the hypothesis exists.
        if(s.distributionCampaign&&!s.campaignOpened){s.observing();
            s.reason="ETH_BEAR_DISTRIBUTION_WAITING_DIRECT_BREAK";return;}
        if(s.reentryMode){
            s.reentryBars++;s.reentryHigh=Double.isFinite(s.reentryHigh)?Math.max(s.reentryHigh,b.high):b.high;
            if(s.reentryBars>24){s.observing();s.reason="ETH_BEAR_REENTRY_EXPIRED";return;}
            boolean resumed=s.reentryBars>=3&&bear(b)&&b.close<previous.low&&b.close<e20
                    &&s.lastEma20Slope<0&&closeLocation(b)<=.45;
            if(resumed){s.phase=EthBearMultiTimeframeSnapshot.ARMED;s.armedUntil=end+12;s.setupId++;
                s.pullbackHigh=s.reentryHigh;s.continuationConfirmed=true;s.reentryMode=false;s.reentryArmed=true;
                s.reason="ETH_BEAR_REENTRY_ARMED";return;}
            s.phase=EthBearMultiTimeframeSnapshot.PULLBACK;s.reason="ETH_BEAR_REENTRY_WAITING_LOWER_HIGH";return;
        }
        if(EthBearMultiTimeframeSnapshot.WARMUP.equals(s.phase)||EthBearMultiTimeframeSnapshot.COOLDOWN.equals(s.phase))s.observing();
        if(EthBearMultiTimeframeSnapshot.RUNNING.equals(s.phase)){s.reason="ETH_BEAR_1H_POSITION_RUNNING";return;}
        if(EthBearMultiTimeframeSnapshot.OBSERVING.equals(s.phase)){
            // Once 4H is strictly bearish, a two-day structural break is enough
            // to start a new continuation lifecycle; 96H reacted too late after
            // fast ETH C-wave breaks.
            double priorLow=lowest(s.oneHour,end-1,48);boolean impulse=b.close<priorLow&&bear(b)
                    &&bodyAtr(b,atr)>=.4&&volumeRatio(s.oneHour,end,20)>=.8&&closeLocation(b)<=.35;
            if(!impulse){s.reason="ETH_BEAR_1H_WAITING_IMPULSE";return;}
            s.phase=EthBearMultiTimeframeSnapshot.IMPULSE;s.impulseOrigin=highest(s.oneHour,end-1,24);
            s.impulseLow=b.low;s.reason="ETH_BEAR_1H_IMPULSE_CONFIRMED";return;
        }
        if(EthBearMultiTimeframeSnapshot.IMPULSE.equals(s.phase)){
            s.impulseLow=Math.min(s.impulseLow,b.low);HBar p=s.oneHour.get(end-1);
            if(b.close<=p.close&&b.high<=p.high){s.reason="ETH_BEAR_1H_IMPULSE_EXTENDING";return;}
            s.phase=EthBearMultiTimeframeSnapshot.PULLBACK;s.pullbackBars=1;s.pullbackHigh=b.high;s.reason="ETH_BEAR_1H_PULLBACK_STARTED";return;
        }
        if(EthBearMultiTimeframeSnapshot.PULLBACK.equals(s.phase)){
            s.pullbackBars++;s.pullbackHigh=Math.max(s.pullbackHigh,b.high);double move=s.impulseOrigin-s.impulseLow;
            double depth=move<=0?2:(s.pullbackHigh-s.impulseLow)/move;double atrDepth=(b.close-s.impulseLow)/atr;
            if(depth>.786||s.pullbackHigh>=s.impulseOrigin||b.close>e60+.25*atr||s.pullbackBars>48){s.observing();s.reason="ETH_BEAR_1H_PULLBACK_INVALIDATED";return;}
            boolean valid=(depth>=.236&&depth<=.618)||(depth>=.15&&depth<=.70&&atrDepth>=.75&&atrDepth<=4);
            if(s.pullbackBars>=3&&valid){s.phase=EthBearMultiTimeframeSnapshot.ARMED;s.armedUntil=end+16;s.setupId++;s.reason="ETH_BEAR_1H_PULLBACK_ARMED";return;}
            s.reason="ETH_BEAR_1H_PULLBACK_ACCUMULATING";return;
        }
        if(EthBearMultiTimeframeSnapshot.ARMED.equals(s.phase)){
            if(s.reentryArmed){
                if(end>s.armedUntil||b.close>e60+.25*atr){s.observing();s.reason="ETH_BEAR_REENTRY_INVALIDATED";return;}
                s.pullbackHigh=Math.max(s.pullbackHigh,b.high);
                s.continuationConfirmed=bear(b)&&b.close<previous.low&&b.close<e20
                        &&s.lastEma20Slope<0&&closeLocation(b)<=.45;
                s.reason=s.continuationConfirmed?"ETH_BEAR_REENTRY_CONTINUATION_CONFIRMED":"ETH_BEAR_REENTRY_WAITING_BREAK";return;
            }
            s.pullbackHigh=Math.max(s.pullbackHigh,b.high);double move=s.impulseOrigin-s.impulseLow;
            double depth=move<=0?2:(s.pullbackHigh-s.impulseLow)/move;
            if(end>s.armedUntil){s.observing();s.reason="ETH_BEAR_1H_ARMED_EXPIRED";return;}
            if(depth>.786||s.pullbackHigh>=s.impulseOrigin||b.close>e60+.25*atr){s.observing();s.reason="ETH_BEAR_1H_ARMED_INVALIDATED";return;}
            // 1H confirms that the rebound has ended; the actual local-low break
            // remains the 15m execution layer's responsibility.
            s.continuationConfirmed=bear(b)&&b.close<previous.low&&b.close<e20
                    &&s.lastEma20Slope<0&&closeLocation(b)<=.45;
            s.reason=s.continuationConfirmed?"ETH_BEAR_1H_CONTINUATION_CONFIRMED"
                    :"ETH_BEAR_1H_WAITING_CONTINUATION";
        }
    }

    private EthBearMultiTimeframeSnapshot snapshot(Session s,FourContext f,EthDailyBearContextSnapshot daily){
        double r=EthBearMultiTimeframeSnapshot.ARMED.equals(s.phase)?.90:EthBearMultiTimeframeSnapshot.PULLBACK.equals(s.phase)?.75:
                EthBearMultiTimeframeSnapshot.IMPULSE.equals(s.phase)?.65:EthBearMultiTimeframeSnapshot.RUNNING.equals(s.phase)?.90:0;
        if(EthBearMultiTimeframeSnapshot.TRANSITION_DOWN.equals(f.trend))r=Math.max(0,r-.10);
        if(daily!=null&&EthDailyBearContextSnapshot.BULL_WEAKENING.equals(daily.state))r=Math.max(0,r-.10);
        return new EthBearMultiTimeframeSnapshot(f.trend,f.confidence,s.phase,r,s.setupId,s.pullbackHigh,s.impulseLow,s.lastAtr,
                s.available1h,s.lastClose,s.lastEma20,s.lastEma20Slope,s.lastEma60,s.available4h,f.close,f.e20,f.e60,f.atr,
                s.continuationConfirmed,f.supportBelow,daily==null?EthDailyBearContextSnapshot.WARMUP:daily.state,
                daily==null?0:daily.confidence,s.available4h<=s.fastBreakdownUntil4h,
                s.bearCampaignActive,s.campaignDrawdownPct(),s.oneHourStrongMomentum,s.reason);
    }
    private FourContext context4h(List<HBar>x,int end,EthDailyBearContextSnapshot daily){
        if(end<60)return new FourContext(EthBearMultiTimeframeSnapshot.NEUTRAL,0,end,Double.NaN,Double.NaN,Double.NaN,Double.NaN);
        HBar b=x.get(end);double e20=ema(x,end,20),e60=ema(x,end,60),past60=ema(x,end-6,60),a=atr(x,end,14);
        double rh=highest(x,end,12),ph=highest(x,end-12,12),rl=lowest(x,end,12),pl=lowest(x,end-12,12);int bull=0,bear=0;
        if(e20>e60)bull++;else bear++;if(e60>past60)bull++;else bear++;if(b.close>e20)bull++;else bear++;
        if(rh>ph&&rl>pl)bull++;else if(rh<ph&&rl<pl)bear++;
        // A true bearish execution context is conjunctive, not a loose vote. This
        // prevents an ordinary bull-market correction from masquerading as a new
        // primary downtrend.
        double past20=ema(x,end-3,20),prior48Low=lowest(x,end-1,48);
        boolean structureDown=rh<ph&&rl<pl;
        boolean strictBear=e20<e60&&e60<past60&&b.close<e20&&structureDown;
        // A fast C-wave can break below the slow average before EMA20 crosses
        // EMA60. Treat only a decisive, large 4H structural breakdown as early
        // confirmation; ordinary TRANSITION_DOWN remains observation-only.
        boolean dailyRisk=daily!=null&&(EthDailyBearContextSnapshot.BEAR_RISK.equals(daily.state)
                ||EthDailyBearContextSnapshot.DISTRIBUTION_RISK.equals(daily.state)
                ||EthDailyBearContextSnapshot.BEAR.equals(daily.state));
        boolean failedDailyHigh=daily!=null&&(EthDailyBearContextSnapshot.BEAR_RISK.equals(daily.state)
                ||EthDailyBearContextSnapshot.DISTRIBUTION_RISK.equals(daily.state))
                &&daily.confidence>=.75;
        double prior24Low=lowest(x,end-1,24);
        boolean earlyBreakdown=dailyRisk&&(e20<past20||failedDailyHigh)&&b.close<e20&&b.close<prior24Low
                &&b.close<b.open&&a>0&&(b.open-b.close)/a>=.30;
        if(strictBear||earlyBreakdown)return new FourContext(EthBearMultiTimeframeSnapshot.BEAR,
                strictBear?1:.85,end,b.close,e20,e60,a,
                nearestSupportBelow(x,end,b.close),earlyBreakdown);
        if(bull>=3)return new FourContext(EthBearMultiTimeframeSnapshot.BULL,bull/4d,end,b.close,e20,e60,a,
                nearestSupportBelow(x,end,b.close));
        if(b.close<e20&&e20<past20&&e20<=e60*1.015)
            return new FourContext(EthBearMultiTimeframeSnapshot.TRANSITION_DOWN,.55,end,b.close,e20,e60,a,
                    nearestSupportBelow(x,end,b.close));
        return new FourContext(EthBearMultiTimeframeSnapshot.NEUTRAL,.5,end,b.close,e20,e60,a,
                nearestSupportBelow(x,end,b.close));
    }
    private void updateBearCampaign(Session s,FourContext four,EthDailyBearContextSnapshot daily){
        boolean distribution=daily!=null&&EthDailyBearContextSnapshot.DISTRIBUTION_RISK.equals(daily.state);
        if(!distribution)s.distributionHandled=false;
        if(daily!=null&&EthDailyBearContextSnapshot.BULL.equals(daily.state)){
            s.endCampaign();return;
        }
        if(four.fastBreakdown||EthBearMultiTimeframeSnapshot.BEAR.equals(four.trend)){
            if(!s.bearCampaignActive&&distribution&&s.distributionHandled)return;
            if(!s.bearCampaignActive){s.campaignStartClose=four.close;s.campaignLow=four.close;
                s.distributionCampaign=distribution;s.distributionHandled=distribution;
                s.campaignOpened=false;}
            s.bearCampaignActive=true;s.campaignBullBars=0;
            if(Double.isFinite(four.close))s.campaignLow=Math.min(s.campaignLow,four.close);return;
        }
        if(s.bearCampaignActive&&Double.isFinite(four.close))s.campaignLow=Math.min(s.campaignLow,four.close);
        boolean confirmedBull=EthBearMultiTimeframeSnapshot.BULL.equals(four.trend)
                &&Double.isFinite(four.close)&&Double.isFinite(four.e20)&&Double.isFinite(four.e60)
                &&four.close>four.e60&&four.e20>four.e60;
        s.campaignBullBars=confirmedBull?s.campaignBullBars+1:0;
        // Preserve execution ownership across an ordinary multi-day rebound,
        // then release it if 4H stays fully bullish and no lifecycle position is running.
        if(s.campaignBullBars>=24&&!EthBearMultiTimeframeSnapshot.RUNNING.equals(s.phase))
            s.endCampaign();
    }
    private List<HBar> convert(List<TTbookOhlc> rows){List<HBar>o=new ArrayList<HBar>();if(rows!=null)for(TTbookOhlc r:rows){if(r==null)continue;long t=LocalDateTime.parse(r.starttime,TIME).atZone(BEIJING).toInstant().toEpochMilli();o.add(new HBar(t,r.open.doubleValue(),r.high.doubleValue(),r.low.doubleValue(),r.close.doubleValue(),r.volume.doubleValue()));}Collections.sort(o,new Comparator<HBar>(){public int compare(HBar a,HBar b){return Long.compare(a.time,b.time);}});return o;}
    private int available(List<HBar>x,long d,long cutoff,int current){int i=Math.max(-1,current);while(i+1<x.size()&&x.get(i+1).time+d<=cutoff)i++;return i;}
    private double ema(List<HBar>x,int end,int p){if(end<0)return Double.NaN;int start=Math.max(0,end-p*4);double v=x.get(start).close,a=2d/(p+1);for(int i=start+1;i<=end;i++)v=x.get(i).close*a+v*(1-a);return v;}
    private double atr(List<HBar>x,int end,int p){double v=0;int n=0;for(int i=Math.max(1,end-p+1);i<=end;i++){HBar b=x.get(i);v+=Math.max(b.high-b.low,Math.max(Math.abs(b.high-x.get(i-1).close),Math.abs(b.low-x.get(i-1).close)));n++;}return n==0?0:v/n;}
    private double highest(List<HBar>x,int end,int n){double v=Double.NEGATIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.max(v,x.get(i).high);return v;}
    private double lowest(List<HBar>x,int end,int n){double v=Double.POSITIVE_INFINITY;for(int i=Math.max(0,end-n+1);i<=end;i++)v=Math.min(v,x.get(i).low);return v;}
    private double nearestSupportBelow(List<HBar>x,int end,double close){
        double nearest=Double.NaN;int start=Math.max(2,end-192);
        for(int i=start;i<=end-3;i++){
            double low=x.get(i).low;
            boolean pivot=low<x.get(i-1).low&&low<=x.get(i+1).low;
            if(pivot&&low<close&&(!Double.isFinite(nearest)||low>nearest))nearest=low;
        }
        return nearest;
    }
    private double volumeRatio(List<HBar>x,int end,int n){double a=0;int start=Math.max(0,end-n+1);for(int i=start;i<=end;i++)a+=x.get(i).volume;a/=Math.max(1,end-start+1);return a<=0?0:x.get(end).volume/a;}
    private double bodyAtr(HBar b,double a){return a<=0?0:Math.abs(b.close-b.open)/a;}private double closeLocation(HBar b){return b.high<=b.low?.5:(b.close-b.low)/(b.high-b.low);}private boolean bear(HBar b){return b.close<b.open;}private boolean finite(double v){return Double.isFinite(v);}private String key(String s){return s==null?"":s.toUpperCase(Locale.ROOT);}
    private static final class HBar{final long time;final double open,high,low,close,volume;HBar(long t,double o,double h,double l,double c,double v){time=t;open=o;high=h;low=l;close=c;volume=v;}}
    private static final class FourContext{final String trend;final double confidence;final int index;final double close,e20,e60,atr,supportBelow;final boolean fastBreakdown;FourContext(String t,double c,int i,double close,double e20,double e60,double atr){this(t,c,i,close,e20,e60,atr,Double.NaN,false);}FourContext(String t,double c,int i,double close,double e20,double e60,double atr,double support){this(t,c,i,close,e20,e60,atr,support,false);}FourContext(String t,double c,int i,double close,double e20,double e60,double atr,double support,boolean fast){trend=t;confidence=c;index=i;this.close=close;this.e20=e20;this.e60=e60;this.atr=atr;supportBelow=support;fastBreakdown=fast;}boolean allowsShort(){return EthBearMultiTimeframeSnapshot.BEAR.equals(trend);}}
    private static final class Session{final List<HBar>oneHour,fourHour;int available1h=-1,available4h=-1,pullbackBars,armedUntil=-1,cooldownUntil=-1,fastBreakdownUntil4h=-1,lastFastBreakdown4h=-1000,reentryBars,campaignBullBars;long setupId;boolean continuationConfirmed,oneHourStrongMomentum,reentryMode,reentryArmed,bearCampaignActive,distributionCampaign,distributionHandled,campaignOpened;double reentryHigh=Double.NaN,impulseOrigin=Double.NaN,impulseLow=Double.NaN,pullbackHigh=Double.NaN,lastAtr=Double.NaN,lastClose=Double.NaN,lastEma20=Double.NaN,lastEma20Slope=Double.NaN,lastEma60=Double.NaN,campaignStartClose=Double.NaN,campaignLow=Double.NaN;String phase=EthBearMultiTimeframeSnapshot.WARMUP,reason="ETH_BEAR_MTF_WARMUP";EthBearMultiTimeframeSnapshot snapshot=EthBearMultiTimeframeSnapshot.warmup();Session(List<HBar>a,List<HBar>b){oneHour=a;fourHour=b;}void observing(){phase=EthBearMultiTimeframeSnapshot.OBSERVING;pullbackBars=reentryBars=0;armedUntil=-1;continuationConfirmed=reentryMode=reentryArmed=false;reentryHigh=Double.NaN;impulseOrigin=impulseLow=pullbackHigh=Double.NaN;}double campaignDrawdownPct(){return Double.isFinite(campaignStartClose)&&campaignStartClose>0&&Double.isFinite(campaignLow)?Math.max(0,(campaignStartClose-campaignLow)/campaignStartClose):0;}void endCampaign(){bearCampaignActive=distributionCampaign=campaignOpened=false;campaignBullBars=0;campaignStartClose=campaignLow=Double.NaN;}}
}
