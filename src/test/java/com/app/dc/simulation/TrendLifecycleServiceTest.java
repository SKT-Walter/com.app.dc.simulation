package com.app.dc.simulation;

import com.app.dc.strategy.core.deterministic.StructuralTrendSnapshot;
import com.app.dc.strategy.core.strategy.trend.*;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.*;
import java.math.BigDecimal;
import java.time.*;

public class TrendLifecycleServiceTest {
 @Test public void bullImpulsePullbackArmAndTriggerAreSequentialAndIdempotent(){
  TrendLifecycleService service=new TrendLifecycleService();TrendLifecycleState state=service.newState();
  BarSeries series=base();BinanceTrendSettings x=settings();StructuralTrendSnapshot bull=structure("BULL");
  Assert.assertEquals(TrendLifecycleSnapshot.DIRECTIONAL,service.update(state,series,bull,x).phase);
  add(series,101,103,100.8,102.8,1800);TrendLifecycleSnapshot impulse=service.update(state,series,bull,x);
  Assert.assertEquals(TrendLifecycleSnapshot.IMPULSE,impulse.phase);Assert.assertSame(impulse,service.update(state,series,bull,x));
  add(series,102.7,102.8,101.5,101.65,1000);Assert.assertEquals(TrendLifecycleSnapshot.PULLBACK,service.update(state,series,bull,x).phase);
  add(series,101.7,102.0,101.45,101.6,1000);service.update(state,series,bull,x);
  add(series,101.6,102.3,101.4,102.2,1000);Assert.assertEquals(TrendLifecycleSnapshot.ARMED,service.update(state,series,bull,x).phase);
  add(series,102.1,103.8,102.0,103.6,1800);TrendLifecycleSnapshot trigger=service.update(state,series,bull,x);
  Assert.assertEquals(TrendLifecycleSnapshot.TRIGGERED,trigger.phase);Assert.assertTrue(trigger.actionable);
  Assert.assertTrue(trigger.stopPrice<103.6);Assert.assertTrue((103.6-trigger.stopPrice)/trigger.atr>=2-.0001);
 }

 @Test public void oppositeSlowStructureInvalidatesWithoutFutureBars(){
  TrendLifecycleService service=new TrendLifecycleService();TrendLifecycleState state=service.newState();BarSeries series=base();
  service.update(state,series,structure("BULL"),settings());add(series,101,103,100.8,102.8,1800);
  service.update(state,series,structure("BULL"),settings());add(series,102.8,103,102,102.2,1000);
  TrendLifecycleSnapshot invalid=service.update(state,series,structure("BEAR"),settings());
  Assert.assertEquals(TrendLifecycleSnapshot.INVALIDATED,invalid.phase);
 }

 private BinanceTrendSettings settings(){return new BinanceTrendSettings(true,20,3,.236,.618,.786,384,4,.30,.80,.65,1,.5,2,4,2,3.5,4,3);}
 private StructuralTrendSnapshot structure(String d){return new StructuralTrendSnapshot(d,d,"ESTABLISHED",.8,100,0,true);}
 private BarSeries base(){BarSeries s=new BaseBarSeries("life");for(int i=0;i<80;i++){double c=100+(i%6-3)*.2;add(s,c-.1,c+.5,c-.5,c,1000);}return s;}
 private void add(BarSeries s,double o,double h,double l,double c,double v){ZonedDateTime t=ZonedDateTime.of(2026,1,1,0,0,0,0,ZoneId.systemDefault()).plusMinutes(s.getBarCount()*15L);s.addBar(new BaseBar(Duration.ofMinutes(15),t,BigDecimal.valueOf(o),BigDecimal.valueOf(h),BigDecimal.valueOf(l),BigDecimal.valueOf(c),BigDecimal.valueOf(v)));}
}
