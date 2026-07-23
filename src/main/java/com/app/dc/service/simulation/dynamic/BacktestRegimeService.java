package com.app.dc.service.simulation.dynamic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic classifier using only bars available at the current replay index. */
@Service
public class BacktestRegimeService {
    @Value("${backtest.regime.minimumBars:60}") private int minimumBars;
    @Value("${backtest.regime.adxThreshold:25}") private double adxThreshold;
    @Value("${backtest.regime.slopeThreshold:0.0015}") private double slopeThreshold;
    @Value("${backtest.regime.minimumConfidence:0.55}") private double minimumConfidence;

    public BacktestRegime identify(BarSeries s){
        BacktestRegime r=new BacktestRegime();if(s==null||s.getBarCount()==0)return r;int end=s.getEndIndex();r.barTime=s.getBar(end).getEndTime().toInstant().toEpochMilli();if(s.getBarCount()<minimumBars)return r;
        double fast=ema(s,end,12),slow=ema(s,end,26),past=ema(s,end-4,26),slope=past==0?0:(slow-past)/past,adx=adx(s,end,14),atr=atr(s,end,14),atrPct=atrPercentile(s,end,14,50),bw=bandwidth(s,end,20),oldBw=bandwidth(s,end-4,20),vr=volumeRatio(s,end,20);
        if(adx>=adxThreshold&&Math.abs(slope)>=slopeThreshold){if(fast>slow&&slope>0)r.trend="UP";else if(fast<slow&&slope<0)r.trend="DOWN";}
        r.volatility=atrPct<=.30?"LOW":atrPct>=.70?"HIGH":"NORMAL";r.breakoutExpansion="HIGH".equals(r.volatility)&&oldBw>0&&bw/oldBw>=1.20;
        double trendEvidence=clamp(Math.max(adx/Math.max(1,adxThreshold),Math.abs(slope)/Math.max(.000001,slopeThreshold))/2),volEvidence=clamp(Math.abs(atrPct-.5)*2);r.confidence=clamp(.45+.35*trendEvidence+.20*volEvidence);r.tradeable=r.confidence>=minimumConfidence&&vr>=.05;
        r.features.put("emaFast",fast);r.features.put("emaSlow",slow);r.features.put("emaSlowSlope",slope);r.features.put("adx",adx);r.features.put("atr",atr);r.features.put("atrPercentile",atrPct);r.features.put("bollingerBandwidth",bw);r.features.put("volumeRatio",vr);return r;
    }
    private double close(BarSeries s,int i){return s.getBar(i).getClosePrice().doubleValue();}
    private double ema(BarSeries s,int end,int p){int start=Math.max(s.getBeginIndex(),end-p*4);double v=close(s,start),k=2d/(p+1);for(int i=start+1;i<=end;i++)v=close(s,i)*k+v*(1-k);return v;}
    private double atr(BarSeries s,int end,int p){int start=Math.max(s.getBeginIndex()+1,end-p+1);double z=0;int n=0;for(int i=start;i<=end;i++){double h=s.getBar(i).getHighPrice().doubleValue(),l=s.getBar(i).getLowPrice().doubleValue(),pc=close(s,i-1);z+=Math.max(h-l,Math.max(Math.abs(h-pc),Math.abs(l-pc)));n++;}return n==0?0:z/n;}
    private double atrPercentile(BarSeries s,int end,int p,int history){List<Double> v=new ArrayList<Double>();int start=Math.max(s.getBeginIndex()+p,end-history+1);for(int i=start;i<=end;i++)v.add(atr(s,i,p));if(v.isEmpty())return .5;double x=v.get(v.size()-1);int n=0;for(double a:v)if(a<=x)n++;return (double)n/v.size();}
    private double adx(BarSeries s,int end,int p){int start=Math.max(s.getBeginIndex()+1,end-p+1);double plus=0,minus=0,tr=0;for(int i=start;i<=end;i++){double h=s.getBar(i).getHighPrice().doubleValue(),l=s.getBar(i).getLowPrice().doubleValue(),ph=s.getBar(i-1).getHighPrice().doubleValue(),pl=s.getBar(i-1).getLowPrice().doubleValue(),pc=close(s,i-1),up=h-ph,down=pl-l;plus+=up>down&&up>0?up:0;minus+=down>up&&down>0?down:0;tr+=Math.max(h-l,Math.max(Math.abs(h-pc),Math.abs(l-pc)));}if(tr==0)return 0;double pdi=100*plus/tr,mdi=100*minus/tr;return pdi+mdi==0?0:100*Math.abs(pdi-mdi)/(pdi+mdi);}
    private double bandwidth(BarSeries s,int end,int p){int start=Math.max(s.getBeginIndex(),end-p+1),n=end-start+1;if(n<2)return 0;double m=0;for(int i=start;i<=end;i++)m+=close(s,i);m/=n;double v=0;for(int i=start;i<=end;i++){double d=close(s,i)-m;v+=d*d;}return m==0?0:4*Math.sqrt(v/n)/m;}
    private double volumeRatio(BarSeries s,int end,int p){int start=Math.max(s.getBeginIndex(),end-p+1),n=end-start+1;double avg=0;for(int i=start;i<=end;i++)avg+=s.getBar(i).getVolume().doubleValue();avg/=n;return avg==0?0:s.getBar(end).getVolume().doubleValue()/avg;}
    private double clamp(double x){return Math.max(0,Math.min(1,x));}
}
