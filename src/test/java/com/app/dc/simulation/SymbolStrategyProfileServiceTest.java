package com.app.dc.simulation;

import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collections;

public class SymbolStrategyProfileServiceTest {

    @Test
    public void loadsExactSymbolOverridesAndFallsBackForUnknownSymbols() throws Exception {
        SymbolStrategyProfileService service = service();

        Assert.assertEquals("2026-07-v1", service.profileVersion("ETHUSDT", "15m"));
        Assert.assertEquals(10.0,
                service.takeProfitPct("ETHUSDT", "15m", "binanceTrend"), 0.000001);
        Assert.assertTrue(service.isStrategyEnabled(
                "ETHUSDT", "15m", "binanceRange"));
        Assert.assertEquals(0.7, service.minimumTriggerRangeAtr(
                "ETHUSDT", "15m", "binanceRange"), 0.000001);

        Assert.assertEquals(10.0,
                service.takeProfitPct("SOLUSDT", "15M", "binanceTrend"), 0.000001);
        Assert.assertTrue(service.isStrategyEnabled(
                "SOLUSDT", "15M", "binanceRange"));
        Assert.assertEquals(0.0, service.minimumTriggerRangeAtr(
                "SOLUSDT", "15M", "binanceRange"), 0.000001);

        Assert.assertNull(service.takeProfitPct("BTCUSDT", "15M", "binanceTrend"));
        Assert.assertTrue(service.isStrategyEnabled(
                "BTCUSDT", "15M", "binanceRange"));
    }

    private SymbolStrategyProfileService service() throws Exception {
        Path project = LocalBacktestRunner.resolveProjectDir(
                Collections.<String, String>emptyMap());
        SymbolStrategyProfileService service = new SymbolStrategyProfileService();
        Field field = SymbolStrategyProfileService.class.getDeclaredField("profileDir");
        field.setAccessible(true);
        field.set(service, project.resolve("config/strategy-profiles").toString());
        service.load();
        return service;
    }
}
