package com.app.dc.simulation;

import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.EthDailyBearContextSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.EthStructuralBearTrendService;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeContextService;
import com.app.dc.service.simulation.strategy.trend.bear.EthDailyBearContextService;
import com.app.dc.service.simulation.BacktestQueryService;
import com.app.dc.service.simulation.dynamic.BacktestRegimeService;
import com.app.dc.po.TTbookOhlc;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.lang.reflect.Field;

public class EthStructuralBearTrendAdmissionTest {
    @Test public void transitionDownIsObservationOnly(){
        Assert.assertFalse(context(EthBearMultiTimeframeSnapshot.TRANSITION_DOWN,false,Double.NaN).directionAllowsShort());
        Assert.assertTrue(context(EthBearMultiTimeframeSnapshot.BEAR,false,Double.NaN).directionAllowsShort());
    }

    @Test public void armedSetupCannotTriggerBeforeOneHourContinuationConfirmation(){
        EthStructuralBearTrendService service=new EthStructuralBearTrendService();
        BarSeries series=series();BacktestRegime regime=new BacktestRegime();regime.tradeable=true;regime.trend="DOWN";regime.volatility="NORMAL";
        BearTrendSnapshot snapshot=service.update("ETHUSDT","15M",series,regime,
                context(EthBearMultiTimeframeSnapshot.BEAR,false,80));
        Assert.assertEquals(BearTrendSnapshot.ARMED,snapshot.phase);
        Assert.assertFalse(snapshot.actionable);
    }

    @Test public void fastFourHourBreakdownCanUseStrongFifteenMinuteMomentumWithoutPullback(){
        EthStructuralBearTrendService service=new EthStructuralBearTrendService();BarSeries series=flatSeries();
        add(series,100,100.2,98.6,98.8,2200);BacktestRegime regime=new BacktestRegime();regime.tradeable=true;regime.trend="DOWN";regime.volatility="HIGH";
        EthBearMultiTimeframeSnapshot context=new EthBearMultiTimeframeSnapshot(
                EthBearMultiTimeframeSnapshot.BEAR,.85,EthBearMultiTimeframeSnapshot.OBSERVING,.85,9,
                Double.NaN,Double.NaN,2,100,99,100,-1,103,100,99,101,103,2,
                false,Double.NaN,EthDailyBearContextSnapshot.BEAR_RISK,.75,true,false,"FAST_TEST");
        BearTrendSnapshot snapshot=service.update("ETHUSDT","15M",series,regime,context);
        Assert.assertTrue(snapshot.actionable);
        Assert.assertEquals("FAILED_HIGH_BEAR_BREAKDOWN",snapshot.triggerType);
        Assert.assertTrue("failed-high stop must remain finite",Double.isFinite(snapshot.stopPrice));
        Assert.assertTrue("failed-high stop must stay above entry",snapshot.stopPrice>98.8);
    }

    @Test public void actualApril2023FailedHighProducesExecutableShort() throws Exception {
        BacktestQueryService query=new BacktestQueryService();set(query,"localDataDir","./config/data");
        List<TTbookOhlc> m15=query.queryLocalOhlc("ETHUSDT","15m","2023-01-01","2023-04-30");
        List<TTbookOhlc> h1=query.queryLocalOhlc("ETHUSDT","1h","2023-01-01","2023-04-30");
        List<TTbookOhlc> h4=query.queryLocalOhlc("ETHUSDT","4h","2023-01-01","2023-04-30");
        List<TTbookOhlc> d1=query.queryLocalOhlc("ETHUSDT","1d","2023-01-01","2023-04-30");
        EthDailyBearContextService daily=new EthDailyBearContextService();EthBearMultiTimeframeContextService mtf=new EthBearMultiTimeframeContextService();
        set(mtf,"dailyContext",daily);mtf.prepare("ETHUSDT",d1,h1,h4);EthStructuralBearTrendService service=new EthStructuralBearTrendService();set(service,"multiTimeframe",mtf);
        BacktestRegimeService regimes=new BacktestRegimeService();set(regimes,"minimumBars",60);set(regimes,"adxThreshold",25d);set(regimes,"slopeThreshold",.0015d);set(regimes,"minimumConfidence",.55d);
        BarSeries actual=new BaseBarSeries("actual-april-2023");Map<String,Integer> reasons=new LinkedHashMap<String,Integer>();boolean action=false;
        DateTimeFormatter f=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        for(TTbookOhlc row:m15){ZonedDateTime t=LocalDateTime.parse(row.starttime,f).atZone(ZoneId.of("Asia/Shanghai"));
            actual.addBar(new BaseBar(Duration.ofMinutes(15),t,row.open,row.high,row.low,row.close,row.volume));
            BearTrendSnapshot snapshot=service.update("ETHUSDT","15M",actual,regimes.identify(actual),mtf.update("ETHUSDT",t.toInstant().toEpochMilli()));
            if(!t.isBefore(ZonedDateTime.of(2023,4,20,0,0,0,0,ZoneId.of("Asia/Shanghai")))){reasons.put(snapshot.reason,reasons.containsKey(snapshot.reason)?reasons.get(snapshot.reason)+1:1);if(snapshot.actionable){Assert.assertTrue(Double.isFinite(snapshot.stopPrice));action=true;break;}}}
        Assert.assertTrue("April failed-high path rejected by: "+reasons,action);
    }

    @Test public void actualMarch2024DistributionProducesEarlyAWaveShort() throws Exception {
        BacktestQueryService query=new BacktestQueryService();set(query,"localDataDir","./config/data");
        List<TTbookOhlc> m15=query.queryLocalOhlc("ETHUSDT","15m","2024-01-01","2024-03-31");
        List<TTbookOhlc> h1=query.queryLocalOhlc("ETHUSDT","1h","2024-01-01","2024-03-31");
        List<TTbookOhlc> h4=query.queryLocalOhlc("ETHUSDT","4h","2024-01-01","2024-03-31");
        List<TTbookOhlc> d1=query.queryLocalOhlc("ETHUSDT","1d","2024-01-01","2024-03-31");
        EthDailyBearContextService daily=new EthDailyBearContextService();
        EthBearMultiTimeframeContextService mtf=new EthBearMultiTimeframeContextService();
        set(mtf,"dailyContext",daily);mtf.prepare("ETHUSDT",d1,h1,h4);
        EthStructuralBearTrendService service=new EthStructuralBearTrendService();set(service,"multiTimeframe",mtf);
        BacktestRegimeService regimes=new BacktestRegimeService();set(regimes,"minimumBars",60);
        set(regimes,"adxThreshold",25d);set(regimes,"slopeThreshold",.0015d);set(regimes,"minimumConfidence",.55d);
        BarSeries actual=new BaseBarSeries("actual-march-2024-distribution");
        DateTimeFormatter f=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        ZonedDateTime earliest=ZonedDateTime.of(2024,3,16,0,0,0,0,ZoneId.of("Asia/Shanghai"));
        ZonedDateTime latest=ZonedDateTime.of(2024,3,20,23,59,0,0,ZoneId.of("Asia/Shanghai"));
        ZonedDateTime triggeredAt=null;
        for(TTbookOhlc row:m15){
            ZonedDateTime t=LocalDateTime.parse(row.starttime,f).atZone(ZoneId.of("Asia/Shanghai"));
            actual.addBar(new BaseBar(Duration.ofMinutes(15),t,row.open,row.high,row.low,row.close,row.volume));
            EthBearMultiTimeframeSnapshot mtfSnapshot=mtf.update("ETHUSDT",t.toInstant().toEpochMilli());
            BearTrendSnapshot snapshot=service.update("ETHUSDT","15M",actual,regimes.identify(actual),mtfSnapshot);
            if(!t.isBefore(earliest)&&snapshot.actionable){
                Assert.assertEquals("DISTRIBUTION_A_WAVE_BREAKDOWN",snapshot.triggerType);
                Assert.assertTrue("direct A-wave trigger requires falling 1H EMA20",
                        mtfSnapshot.oneHourEma20Slope<0);
                Assert.assertTrue("direct A-wave trigger requires price below 1H EMA20",
                        mtfSnapshot.oneHourClose<mtfSnapshot.oneHourEma20);
                Assert.assertTrue(Double.isFinite(snapshot.stopPrice));triggeredAt=t;break;
            }
        }
        Assert.assertNotNull("multi-day distribution should trigger the A-wave lifecycle",triggeredAt);
        Assert.assertFalse("A-wave trigger arrived too late: "+triggeredAt,triggeredAt.isAfter(latest));
    }

    private void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}

    private EthBearMultiTimeframeSnapshot context(String trend,boolean confirmed,double support){
        return new EthBearMultiTimeframeSnapshot(trend,.9,EthBearMultiTimeframeSnapshot.ARMED,.9,1,
                105,95,2,100,99,100,-1,103,100,99,101,103,2,
                confirmed,support,"TEST");
    }
    private BarSeries series(){BarSeries s=new BaseBarSeries("eth-bear-admission");
        ZonedDateTime start=ZonedDateTime.of(2026,1,1,0,0,0,0,ZoneId.systemDefault());
        for(int i=0;i<80;i++){double p=105-i*.07;s.addBar(new BaseBar(Duration.ofMinutes(15),start.plusMinutes(i*15L),
                BigDecimal.valueOf(p+.1),BigDecimal.valueOf(p+.6),BigDecimal.valueOf(p-.6),BigDecimal.valueOf(p),BigDecimal.valueOf(1000)));}
        return s;}
    private BarSeries flatSeries(){BarSeries s=new BaseBarSeries("eth-fast-bear");for(int i=0;i<80;i++)add(s,100.1,100.6,99.4,100,1000);return s;}
    private void add(BarSeries s,double o,double h,double l,double c,double v){ZonedDateTime t=ZonedDateTime.of(2026,1,1,0,0,0,0,ZoneId.systemDefault()).plusMinutes(s.getBarCount()*15L);s.addBar(new BaseBar(Duration.ofMinutes(15),t,BigDecimal.valueOf(o),BigDecimal.valueOf(h),BigDecimal.valueOf(l),BigDecimal.valueOf(c),BigDecimal.valueOf(v)));}
}
