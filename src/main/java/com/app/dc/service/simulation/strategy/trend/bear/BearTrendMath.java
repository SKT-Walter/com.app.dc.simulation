package com.app.dc.service.simulation.strategy.trend.bear;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

final class BearTrendMath {
    private BearTrendMath(){}
    static double close(BarSeries s,int i){return s.getBar(i).getClosePrice().doubleValue();}
    static double open(BarSeries s,int i){return s.getBar(i).getOpenPrice().doubleValue();}
    static double high(BarSeries s,int i){return s.getBar(i).getHighPrice().doubleValue();}
    static double low(BarSeries s,int i){return s.getBar(i).getLowPrice().doubleValue();}
    static double highest(BarSeries s,int end,int count){double v=Double.NEGATIVE_INFINITY;for(int i=Math.max(s.getBeginIndex(),end-count+1);i<=end;i++)v=Math.max(v,high(s,i));return v;}
    static double lowest(BarSeries s,int end,int count){double v=Double.POSITIVE_INFINITY;for(int i=Math.max(s.getBeginIndex(),end-count+1);i<=end;i++)v=Math.min(v,low(s,i));return v;}
    static double ema(BarSeries s,int end,int p){int start=Math.max(s.getBeginIndex(),end-p*4);double v=close(s,start),a=2d/(p+1d);for(int i=start+1;i<=end;i++)v=close(s,i)*a+v*(1-a);return v;}
    static double atr(BarSeries s,int end,int p){int start=Math.max(s.getBeginIndex()+1,end-p+1);double sum=0;int n=0;for(int i=start;i<=end;i++){Bar b=s.getBar(i);double h=b.getHighPrice().doubleValue(),l=b.getLowPrice().doubleValue(),pc=close(s,i-1);sum+=Math.max(h-l,Math.max(Math.abs(h-pc),Math.abs(l-pc)));n++;}return n==0?0:sum/n;}
    static double volumeRatio(BarSeries s,int end,int p){int start=Math.max(s.getBeginIndex(),end-p+1),n=end-start+1;double avg=0;for(int i=start;i<=end;i++)avg+=s.getBar(i).getVolume().doubleValue();avg/=Math.max(1,n);return avg<=0?0:s.getBar(end).getVolume().doubleValue()/avg;}
    static double closeLocation(BarSeries s,int i){Bar b=s.getBar(i);double r=b.getHighPrice().doubleValue()-b.getLowPrice().doubleValue();return r<=0?.5:(b.getClosePrice().doubleValue()-b.getLowPrice().doubleValue())/r;}
    static boolean bear(BarSeries s,int i){Bar b=s.getBar(i);return b.getClosePrice().isLessThan(b.getOpenPrice());}
}
