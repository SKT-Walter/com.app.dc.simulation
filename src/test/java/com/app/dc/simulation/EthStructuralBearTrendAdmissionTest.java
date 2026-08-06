package com.app.dc.simulation;

import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.EthStructuralBearTrendService;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

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
}
