package com.app.dc.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.trend.bear.EthDailyBearContextService;
import com.app.dc.service.simulation.strategy.trend.bear.EthDailyBearContextSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeContextService;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeSnapshot;
import com.app.dc.service.simulation.BacktestQueryService;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;

public class EthDailyBearContextServiceTest {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Test public void onlyClosedDailyBarsAreVisibleAndRisingMarketIsBull(){
        EthDailyBearContextService service=new EthDailyBearContextService();List<TTbookOhlc> rows=rising(80);
        service.prepare("ETHUSDT",rows);
        long beforeLastClose=time(rows.get(79))-15*60*1000L;
        EthDailyBearContextSnapshot before=service.update("ETHUSDT",beforeLastClose);
        Assert.assertEquals(78,before.barIndex);
        EthDailyBearContextSnapshot after=service.update("ETHUSDT",time(rows.get(79))+24*60*60*1000L-15*60*1000L);
        Assert.assertEquals(79,after.barIndex);Assert.assertEquals(EthDailyBearContextSnapshot.BULL,after.state);
    }

    @Test public void decisiveDailyBreakCreatesBearRiskBeforeSlowAverageCross(){
        List<TTbookOhlc> rows=rising(70);double p=169;
        for(int i=0;i<10;i++){double close=p-5;rows.add(row(70+i,p+1,p+2,close-2,close));p=close;}
        EthDailyBearContextService service=new EthDailyBearContextService();service.prepare("ETHUSDT",rows);
        TTbookOhlc last=rows.get(rows.size()-1);
        EthDailyBearContextSnapshot snapshot=service.update("ETHUSDT",time(last)+24*60*60*1000L-15*60*1000L);
        Assert.assertEquals(EthDailyBearContextSnapshot.BEAR_RISK,snapshot.state);
        Assert.assertTrue(snapshot.allowsNormalShort());
    }

    @Test public void actualApril2023FailedHighOverridesBullState() throws Exception {
        BacktestQueryService query=new BacktestQueryService();Field dir=BacktestQueryService.class.getDeclaredField("localDataDir");
        dir.setAccessible(true);dir.set(query,"./config/data");
        List<TTbookOhlc> rows=query.queryLocalOhlc("ETHUSDT","1d","2023-01-01","2023-12-31");
        EthDailyBearContextService service=new EthDailyBearContextService();service.prepare("ETHUSDT",rows);
        TTbookOhlc target=null;for(TTbookOhlc row:rows)if(row.starttime.startsWith("2023-04-19"))target=row;
        Assert.assertNotNull(target);
        EthDailyBearContextSnapshot snapshot=service.update("ETHUSDT",time(target)+24*60*60*1000L-15*60*1000L);
        Assert.assertEquals(EthDailyBearContextSnapshot.BEAR_RISK,snapshot.state);
        Assert.assertEquals("ETH_DAILY_FAILED_HIGH_REVERSAL",snapshot.reason);
    }

    @Test public void actualApril2023FailedHighOpensFastFourHourWindow() throws Exception {
        BacktestQueryService query=query();
        List<TTbookOhlc> daily=query.queryLocalOhlc("ETHUSDT","1d","2023-01-01","2023-12-31");
        List<TTbookOhlc> one=query.queryLocalOhlc("ETHUSDT","1h","2023-01-01","2023-12-31");
        List<TTbookOhlc> four=query.queryLocalOhlc("ETHUSDT","4h","2023-01-01","2023-12-31");
        EthDailyBearContextService dailyService=new EthDailyBearContextService();
        EthBearMultiTimeframeContextService service=new EthBearMultiTimeframeContextService();
        Field dailyField=EthBearMultiTimeframeContextService.class.getDeclaredField("dailyContext");
        dailyField.setAccessible(true);dailyField.set(service,dailyService);service.prepare("ETHUSDT",daily,one,four);
        long start=ZonedDateTime.of(2023,4,20,8,0,0,0,ZONE).toInstant().toEpochMilli();boolean found=false;
        for(int i=0;i<96*3;i++){EthBearMultiTimeframeSnapshot snapshot=service.update("ETHUSDT",start+i*15*60*1000L);
            if(snapshot.fastBearBreakdownActive){found=true;break;}}
        Assert.assertTrue("failed daily high should open an early 4H campaign",found);
    }

    private BacktestQueryService query() throws Exception {BacktestQueryService query=new BacktestQueryService();
        Field dir=BacktestQueryService.class.getDeclaredField("localDataDir");dir.setAccessible(true);dir.set(query,"./config/data");return query;}

    private List<TTbookOhlc> rising(int count){List<TTbookOhlc> rows=new ArrayList<TTbookOhlc>();for(int i=0;i<count;i++){double p=100+i;rows.add(row(i,p-.3,p+.8,p-.8,p));}return rows;}
    private TTbookOhlc row(int day,double o,double h,double l,double c){TTbookOhlc x=new TTbookOhlc();x.securityid="ETHUSDT";x.text="1D";x.starttime=FORMAT.format(ZonedDateTime.of(2023,1,1,8,0,0,0,ZONE).plusDays(day));x.open=BigDecimal.valueOf(o);x.high=BigDecimal.valueOf(h);x.low=BigDecimal.valueOf(l);x.close=BigDecimal.valueOf(c);x.volume=BigDecimal.valueOf(1000);return x;}
    private long time(TTbookOhlc x){return LocalDateTime.parse(x.starttime,FORMAT).atZone(ZONE).toInstant().toEpochMilli();}
}
