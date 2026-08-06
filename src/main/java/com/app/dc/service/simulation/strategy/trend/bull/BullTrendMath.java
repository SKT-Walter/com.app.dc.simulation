package com.app.dc.service.simulation.strategy.trend.bull;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

final class BullTrendMath {
    private BullTrendMath(){}
    static double close(BarSeries s,int i){return s.getBar(i).getClosePrice().doubleValue();}
    static double high(BarSeries s,int i){return s.getBar(i).getHighPrice().doubleValue();}
    static double low(BarSeries s,int i){return s.getBar(i).getLowPrice().doubleValue();}
    static double highest(BarSeries s,int end,int count){double v=Double.NEGATIVE_INFINITY;int start=Math.max(s.getBeginIndex(),end-count+1);for(int i=start;i<=end;i++)v=Math.max(v,high(s,i));return v;}
    static double lowest(BarSeries s,int end,int count){double v=Double.POSITIVE_INFINITY;int start=Math.max(s.getBeginIndex(),end-count+1);for(int i=start;i<=end;i++)v=Math.min(v,low(s,i));return v;}
    static double ema(BarSeries s,int end,int period){int start=Math.max(s.getBeginIndex(),end-period*4);double v=close(s,start),a=2d/(period+1d);for(int i=start+1;i<=end;i++)v=close(s,i)*a+v*(1-a);return v;}
    static double atr(BarSeries s,int end,int period){int start=Math.max(s.getBeginIndex()+1,end-period+1);double sum=0;int n=0;for(int i=start;i<=end;i++){Bar b=s.getBar(i);double h=b.getHighPrice().doubleValue(),l=b.getLowPrice().doubleValue(),pc=close(s,i-1);sum+=Math.max(h-l,Math.max(Math.abs(h-pc),Math.abs(l-pc)));n++;}return n==0?0:sum/n;}
    static double volumeRatio(BarSeries s,int end,int period){int start=Math.max(s.getBeginIndex(),end-period+1),n=end-start+1;double avg=0;for(int i=start;i<=end;i++)avg+=s.getBar(i).getVolume().doubleValue();avg=n==0?0:avg/n;return avg<=0?0:s.getBar(end).getVolume().doubleValue()/avg;}
    static double closeLocation(BarSeries s,int end){Bar b=s.getBar(end);double range=b.getHighPrice().doubleValue()-b.getLowPrice().doubleValue();return range<=0?.5:(b.getClosePrice().doubleValue()-b.getLowPrice().doubleValue())/range;}
    static double bodyAtr(BarSeries s,int end,double atr){Bar b=s.getBar(end);return atr<=0?0:Math.abs(b.getClosePrice().doubleValue()-b.getOpenPrice().doubleValue())/atr;}
    static boolean bull(BarSeries s,int end){Bar b=s.getBar(end);return b.getClosePrice().isGreaterThan(b.getOpenPrice());}
}
