package com.app.dc.simulation;

import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.strategy.trend.bull.*;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.*;

import java.math.BigDecimal;
import java.time.*;

public class IndependentBullTrendStateMachineTest {
    @Test public void ethRequiresOneHourArmHigherLowAndTwoCloseRecovery(){
        EthStructuralBullTrendService service=new EthStructuralBullTrendService();BarSeries s=flat("eth",100,80);
        BacktestRegime up=regime("UP","NORMAL",.35);EthMultiTimeframeSnapshot armed=armedContext("BULL",1,97);
        BullTrendSnapshot first=service.update("ETHUSDT","15M",s,up,armed);
        Assert.assertEquals(BullTrendSnapshot.ARMED,first.phase);Assert.assertSame(first,service.update("ETHUSDT","15M",s,up,armed));
        add(s,100,100.5,98.5,99,1000);service.update("ETHUSDT","15M",s,up,armed);
        add(s,99,99.5,98.0,98.8,1000);service.update("ETHUSDT","15M",s,up,armed);
        add(s,98.8,100,98.4,99.8,1000);service.update("ETHUSDT","15M",s,up,armed);
        add(s,99.8,101.5,99.6,101.3,1800);BullTrendSnapshot trigger=service.update("ETHUSDT","15M",s,up,armed);
        Assert.assertEquals(BullTrendSnapshot.TRIGGERED,trigger.phase);Assert.assertTrue(trigger.actionable);Assert.assertTrue(trigger.stopPrice<103.4);
        service.consume("ETHUSDT","15M",s.getEndIndex());
        Assert.assertEquals(BullTrendSnapshot.RUNNING,service.current("ETHUSDT","15M").phase);
        TradeRecord stopped=new TradeRecord();stopped.exitReason="stop_loss";
        service.tradeClosed("ETHUSDT",s.getEndIndex(),stopped);
        add(s,103.2,103.5,102.8,103.1,1000);
        Assert.assertEquals(BullTrendSnapshot.COOLDOWN,
                service.update("ETHUSDT","15M",s,up,armed).phase);
    }

    @Test public void ethFourHourBearBlocksAnOtherwiseArmedSetup(){
        EthStructuralBullTrendService service=new EthStructuralBullTrendService();BarSeries s=flat("eth-bear",100,80);
        BullTrendSnapshot blocked=service.update("ETHUSDT","15M",s,regime("UP","NORMAL",.35),armedContext("BEAR",1,97));
        Assert.assertEquals(BullTrendSnapshot.OBSERVING,blocked.phase);
        Assert.assertEquals("ETH_4H_DIRECTION_BLOCKED",blocked.reason);
    }

    @Test public void solRequiresHigherTimeframeArmAndFifteenMinuteRecovery(){
        SolMomentumBullTrendService service=new SolMomentumBullTrendService();BarSeries s=flat("sol",100,80);
        BacktestRegime up=regime("UP","NORMAL",.35);EthMultiTimeframeSnapshot armed=armedContext("BULL",3,97);
        Assert.assertEquals(BullTrendSnapshot.ARMED,
                service.update("SOLUSDT","15M",s,up,armed).phase);
        add(s,100,100.5,98.5,99,1000);service.update("SOLUSDT","15M",s,up,armed);
        add(s,99,99.5,98.0,98.8,1000);service.update("SOLUSDT","15M",s,up,armed);
        add(s,98.8,100,98.4,99.8,1000);service.update("SOLUSDT","15M",s,up,armed);
        add(s,99.8,101.5,99.6,101.3,1800);service.update("SOLUSDT","15M",s,up,armed);
        add(s,101.2,102.5,101.0,102.3,1800);
        BullTrendSnapshot snapshot=service.update("SOLUSDT","15M",s,up,armed);
        Assert.assertEquals(BullTrendSnapshot.TRIGGERED,snapshot.phase);Assert.assertTrue(snapshot.actionable);
        Assert.assertEquals(BullTrendSnapshot.WARMUP,
                service.update("ETHUSDT","15M",s,up,armed).phase);
    }

    @Test public void slowBearInvalidatesBothDetectors(){
        EthStructuralBullTrendService eth=new EthStructuralBullTrendService();SolMomentumBullTrendService sol=new SolMomentumBullTrendService();
        BarSeries e=flat("e",100,80),s=risingThenFlat();
        Assert.assertEquals(BullTrendSnapshot.OBSERVING,eth.update("ETHUSDT","15M",e,regime("DOWN","NORMAL",.5),armedContext("BEAR",2,97)).phase);
        Assert.assertEquals(BullTrendSnapshot.OBSERVING,sol.update("SOLUSDT","15M",s,
                regime("DOWN","NORMAL",.5),armedContext("BEAR",2,97)).phase);
    }

    @Test public void solFourHourBearBlocksArmedSetup(){
        SolMomentumBullTrendService service=new SolMomentumBullTrendService();BarSeries s=risingThenFlat();
        BullTrendSnapshot rejected=service.update("SOLUSDT","15M",s,
                regime("UP","HIGH",.9),armedContext("BEAR",4,97));
        Assert.assertEquals(BullTrendSnapshot.OBSERVING,rejected.phase);
        Assert.assertEquals("SOL_4H_DIRECTION_BLOCKED",rejected.reason);
    }

    @Test public void solLaunchHasIndependentTriggerState(){
        SolBullLaunchTrendService launch=new SolBullLaunchTrendService();
        SolMomentumBullTrendService mature=new SolMomentumBullTrendService();
        BarSeries s=flat("sol-launch",100,80);BacktestRegime up=regime("UP","NORMAL",.35);
        EthMultiTimeframeSnapshot armed=armedContext("TRANSITION_UP",9,97);
        launch.update("SOLUSDT","15M",s,up,armed);
        add(s,100,100.5,98.5,99,1000);launch.update("SOLUSDT","15M",s,up,armed);
        add(s,99,99.5,98.0,98.8,1000);launch.update("SOLUSDT","15M",s,up,armed);
        add(s,98.8,100,98.4,99.8,1000);launch.update("SOLUSDT","15M",s,up,armed);
        add(s,99.8,101.5,99.6,101.3,1800);launch.update("SOLUSDT","15M",s,up,armed);
        add(s,101.2,102.5,101.0,102.3,1800);
        BullTrendSnapshot triggered=launch.update("SOLUSDT","15M",s,up,armed);
        Assert.assertEquals(BullTrendSnapshot.TRIGGERED,triggered.phase);
        Assert.assertEquals("SOL_4H_TURN_UP_15M_BREAKOUT",triggered.triggerType);
        Assert.assertEquals(BullTrendSnapshot.WARMUP,
                mature.current("SOLUSDT","15M").phase);
        launch.consume("SOLUSDT","15M",s.getEndIndex());
        Assert.assertEquals(BullTrendSnapshot.RUNNING,
                launch.current("SOLUSDT","15M").phase);
        Assert.assertEquals(BullTrendSnapshot.WARMUP,
                mature.current("SOLUSDT","15M").phase);
    }

    private StructuralTrendSnapshot structure(String d){return structure(d,d);}
    private StructuralTrendSnapshot structure(String confirmed,String raw){return new StructuralTrendSnapshot(confirmed,raw,"ESTABLISHED",.8,200,0,true);}
    private BacktestRegime regime(String trend,String vol,double atrPct){BacktestRegime r=new BacktestRegime();r.trend=trend;r.volatility=vol;r.tradeable=true;r.confidence=.8;r.features.put("atrPercentile",atrPct);return r;}
    private EthMultiTimeframeSnapshot armedContext(String fourHour,long id,double low){return new EthMultiTimeframeSnapshot(fourHour,.8,EthMultiTimeframeSnapshot.ARMED,.90,id,low,105,2.0,"TEST_ARMED");}
    private BarSeries flat(String name,double p,int n){BarSeries s=new BaseBarSeries(name);for(int i=0;i<n;i++)add(s,p-.1,p+1.2,p-1.2,p,1000);return s;}
    private BarSeries risingThenFlat(){BarSeries s=new BaseBarSeries("sol");for(int i=0;i<60;i++){double p=100+i*.13;add(s,p-.05,p+.25,p-.2,p,1000);}for(int i=0;i<25;i++)add(s,108-.05,108.2,107.8,108,1000);return s;}
    private double highest(BarSeries s,int n){double v=0;for(int i=s.getEndIndex()-n+1;i<=s.getEndIndex();i++)v=Math.max(v,s.getBar(i).getHighPrice().doubleValue());return v;}
    private void add(BarSeries s,double o,double h,double l,double c,double v){ZonedDateTime t=ZonedDateTime.of(2026,1,1,0,0,0,0,ZoneId.systemDefault()).plusMinutes(s.getBarCount()*15L);s.addBar(new BaseBar(Duration.ofMinutes(15),t,BigDecimal.valueOf(o),BigDecimal.valueOf(h),BigDecimal.valueOf(l),BigDecimal.valueOf(c),BigDecimal.valueOf(v)));}
}
