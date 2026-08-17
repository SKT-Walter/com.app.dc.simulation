package com.app.dc.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.strategy.core.strategy.trend.bull.EthMultiTimeframeContextService;
import com.app.dc.strategy.core.strategy.trend.bull.EthMultiTimeframeSnapshot;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class EthMultiTimeframeContextServiceTest {
    private static final DateTimeFormatter F=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    @Test public void incompleteOneHourBarIsNotVisible(){
        LocalDateTime base=LocalDateTime.of(2026,1,1,0,0);
        List<TTbookOhlc> four=new ArrayList<TTbookOhlc>();
        for(int i=0;i<201;i++){double p=100+i*.25;four.add(row(base.plusHours(i*4L),p-.2,p+.6,p-.5,p,1000,"4H"));}
        List<TTbookOhlc> one=new ArrayList<TTbookOhlc>();
        LocalDateTime oneBase=base.plusHours(620);
        for(int i=0;i<170;i++)one.add(row(oneBase.plusHours(i),100,101,99,100,1000,"1H"));
        one.add(row(oneBase.plusHours(170),100,104,99.8,103.5,2500,"1H"));
        EthMultiTimeframeContextService service=new EthMultiTimeframeContextService();service.prepare("ETHUSDT",one,four);
        long open=oneBase.plusHours(170).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        EthMultiTimeframeSnapshot before=service.update("ETHUSDT",open+30*60*1000L-15*60*1000L);
        Assert.assertNotEquals(EthMultiTimeframeSnapshot.IMPULSE,before.oneHourPhase);
        EthMultiTimeframeSnapshot after=service.update("ETHUSDT",open+60*60*1000L-15*60*1000L);
        Assert.assertEquals(EthMultiTimeframeSnapshot.IMPULSE,after.oneHourPhase);
        Assert.assertEquals(EthMultiTimeframeSnapshot.FOUR_HOUR_BULL,after.fourHourTrend);
    }
    private TTbookOhlc row(LocalDateTime t,double o,double h,double l,double c,double v,String text){TTbookOhlc x=new TTbookOhlc();x.starttime=F.format(t);x.open=BigDecimal.valueOf(o);x.high=BigDecimal.valueOf(h);x.low=BigDecimal.valueOf(l);x.close=BigDecimal.valueOf(c);x.volume=BigDecimal.valueOf(v);x.text=text;return x;}
}
