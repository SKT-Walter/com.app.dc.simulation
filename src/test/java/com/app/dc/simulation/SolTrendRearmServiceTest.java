package com.app.dc.simulation;

import com.app.dc.strategy.core.StrategyRuntimeModels.TradeRecord;
import com.app.dc.strategy.core.dynamic.MarketRegime;
import com.app.dc.strategy.core.strategy.trend.bull.*;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.*;

import java.math.BigDecimal;
import java.time.*;

public class SolTrendRearmServiceTest {
    @Test public void onlyProfitLockArmsAndQualifiedRecoveryTriggers(){
        SolTrendRearmService service=new SolTrendRearmService();BarSeries series=series();
        TradeRecord ordinary=new TradeRecord();ordinary.exitReason="sol_1h_structure_invalidation_exit";
        service.onTradeClosed("SOLUSDT","15M",series.getEndIndex()-20,ordinary);
        Assert.assertFalse(service.active("SOLUSDT","15M"));
        TradeRecord locked=new TradeRecord();locked.exitReason="sol_1h_profit_lock_exit";
        service.onTradeClosed("SOLUSDT","15M",series.getEndIndex()-20,locked);
        add(series,100,103,99.8,102.8,3000);
        SolTrendRearmDecision decision=service.evaluate("SOLUSDT","15M",series,regime(),context("BULL"));
        Assert.assertTrue(decision.triggered);Assert.assertTrue(decision.stopPrice<102.8);
        service.consume("SOLUSDT","15M");Assert.assertFalse(service.active("SOLUSDT","15M"));
    }

    @Test public void fourHourBearCannotTrigger(){
        SolTrendRearmService service=new SolTrendRearmService();BarSeries series=series();
        TradeRecord locked=new TradeRecord();locked.exitReason="sol_1h_profit_lock_exit";
        service.onTradeClosed("SOLUSDT","15M",series.getEndIndex()-20,locked);
        add(series,100,103,99.8,102.8,3000);
        Assert.assertFalse(service.evaluate("SOLUSDT","15M",series,regime(),context("BEAR")).triggered);
    }

    @Test public void rearmCannotChainFromAnotherRearmTrade(){
        SolTrendRearmService service=new SolTrendRearmService();BarSeries series=series();
        TradeRecord locked=new TradeRecord();locked.exitReason="sol_1h_profit_lock_exit";
        locked.trendTriggerType=SolTrendRearmService.TRIGGER_TYPE;
        service.onTradeClosed("SOLUSDT","15M",series.getEndIndex(),locked);
        Assert.assertFalse(service.active("SOLUSDT","15M"));
    }

    @Test public void rearmExpiresAfterThirtySixHours(){
        SolTrendRearmService service=new SolTrendRearmService();BarSeries series=series();
        TradeRecord locked=new TradeRecord();locked.exitReason="sol_1h_profit_lock_exit";
        service.onTradeClosed("SOLUSDT","15M",series.getEndIndex()-145,locked);
        add(series,100,103,99.8,102.8,3000);
        Assert.assertFalse(service.evaluate("SOLUSDT","15M",series,regime(),context("BULL")).active);
    }

    private MarketRegime regime(){MarketRegime r=new MarketRegime();r.tradeable=true;r.trend="UP";r.volatility="NORMAL";return r;}
    private EthMultiTimeframeSnapshot context(String trend){return new EthMultiTimeframeSnapshot(trend,.9,EthMultiTimeframeSnapshot.COOLDOWN,0,1,98,105,2,100,103,101,1,100,99,99,100,104,102,100,3,"TEST");}
    private BarSeries series(){BarSeries s=new BaseBarSeries("rearm");for(int i=0;i<80;i++)add(s,100,100.5,99.5,100,1000);return s;}
    private void add(BarSeries s,double o,double h,double l,double c,double v){ZonedDateTime t=ZonedDateTime.of(2026,1,1,0,0,0,0,ZoneId.systemDefault()).plusMinutes(s.getBarCount()*15L);s.addBar(new BaseBar(Duration.ofMinutes(15),t,BigDecimal.valueOf(o),BigDecimal.valueOf(h),BigDecimal.valueOf(l),BigDecimal.valueOf(c),BigDecimal.valueOf(v)));}
}
