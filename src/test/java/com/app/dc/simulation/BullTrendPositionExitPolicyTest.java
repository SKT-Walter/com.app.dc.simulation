package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.strategy.exit.*;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.*;
import java.math.BigDecimal;
import java.time.*;

public class BullTrendPositionExitPolicyTest {
 @Test public void chandelierOnlyTightensAndSlowBearExits(){
  BullTrendPositionExitPolicy policy=new BullTrendPositionExitPolicy();BarSeries series=series();
  Position p=new Position();p.side=Side.BUY;p.strategyName="solMomentumBullTrend";p.entryPrice=100;p.entryIndex=0;p.entryAtr=1;p.stopPrice=97d;p.highestSinceEntry=104;
  PositionExitDecision hold=policy.evaluate(p,new StrategyPositionExitContext(series,null,structure("BULL")));
  Assert.assertFalse(hold.exit);Assert.assertTrue(p.stopPrice>97);double tightened=p.stopPrice;
  p.highestSinceEntry=103;policy.evaluate(p,new StrategyPositionExitContext(series,null,structure("BULL")));
  Assert.assertTrue(p.stopPrice>=tightened);Assert.assertEquals("bull_atr_structure_trailing_exit",p.stopExitReason);
  PositionExitDecision exit=policy.evaluate(p,new StrategyPositionExitContext(series,null,structure("BEAR")));
  Assert.assertTrue(exit.exit);Assert.assertEquals("bull_slow_structure_reversed",exit.reason);
 }
 private StructuralTrendSnapshot structure(String d){return new StructuralTrendSnapshot(d,d,"ESTABLISHED",.8,50,0,true);}
 private BarSeries series(){BarSeries s=new BaseBarSeries("exit");for(int i=0;i<8;i++){double c=100+i*.5;ZonedDateTime t=ZonedDateTime.of(2026,1,1,0,0,0,0,ZoneId.systemDefault()).plusMinutes(i*15L);s.addBar(new BaseBar(Duration.ofMinutes(15),t,BigDecimal.valueOf(c-.2),BigDecimal.valueOf(c+.5),BigDecimal.valueOf(c-.6),BigDecimal.valueOf(c),BigDecimal.valueOf(1000)));}return s;}
}
