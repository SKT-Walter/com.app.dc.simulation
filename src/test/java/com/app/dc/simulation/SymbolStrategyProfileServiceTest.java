package com.app.dc.simulation;

import com.app.dc.strategy.core.strategy.profile.SymbolStrategyProfileService;
import com.app.dc.strategy.core.strategy.range.BinanceRangeSettings;
import com.app.dc.strategy.core.strategy.trend.BinanceTrendSettings;
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
        BinanceTrendSettings ethTrend =
                service.binanceTrendSettings("ETHUSDT", "15m");
        Assert.assertFalse(ethTrend.lifecycleEnabled);
        Assert.assertFalse(service.binanceTrendBuyEnabled("ETHUSDT","15m"));
        Assert.assertTrue(service.isStrategyEnabled("ETHUSDT","15m","binanceTrend"));
        Assert.assertTrue(service.isStrategyEnabled("ETHUSDT","15m","ethStructuralBullTrend"));
        Assert.assertTrue(service.isStrategyEnabled(
                "ETHUSDT", "15m", "binanceRange"));
        Assert.assertEquals(0.7, service.minimumTriggerRangeAtr(
                "ETHUSDT", "15m", "binanceRange"), 0.000001);
        Assert.assertEquals(0.10, service.minimumBuyRecoveryBodyAtr(
                "ETHUSDT", "15m", "binanceRange"), 0.000001);
        Assert.assertEquals(0.65, service.minimumBuyCloseLocation(
                "ETHUSDT", "15m", "binanceRange"), 0.000001);
        BinanceRangeSettings ethRange = service.binanceRangeSettings(
                "ETHUSDT", "15m", "binanceRange");
        Assert.assertTrue(ethRange.stateMachineEnabled);
        Assert.assertFalse(service.isStrategyEnabled("ETHUSDT", "15m", "bollingerMeanReversion"));
        Assert.assertFalse(service.isSideEnabled(
                "ETHUSDT", "15m", "donchianReversion", "BUY"));
        Assert.assertTrue(service.isSideEnabled(
                "ETHUSDT", "15m", "donchianReversion", "SELL"));
        Assert.assertEquals(1.40, ethRange.minimumRewardRisk, 0.000001);
        Assert.assertFalse(service.isStrategyEnabled(
                "ETHUSDT", "15m", "atrChannelBiasReversion"));
        Assert.assertFalse(service.isStrategyEnabled(
                "ETHUSDT", "15m", "compressionBreak"));

        Assert.assertEquals(10.0,
                service.takeProfitPct("SOLUSDT", "15M", "binanceTrend"), 0.000001);
        Assert.assertEquals("2026-08-sol-v3",
                service.profileVersion("SOLUSDT", "15M"));
        Assert.assertFalse(service.binanceTrendSettings(
                "SOLUSDT", "15M").lifecycleEnabled);
        Assert.assertFalse(service.binanceTrendBuyEnabled("SOLUSDT","15M"));
        Assert.assertTrue(service.isStrategyEnabled("SOLUSDT","15M","solMomentumBullTrend"));
        Assert.assertTrue(service.isStrategyEnabled(
                "SOLUSDT", "15M", "binanceRange"));
        Assert.assertEquals(0.0, service.minimumTriggerRangeAtr(
                "SOLUSDT", "15M", "binanceRange"), 0.000001);
        Assert.assertEquals(0.0, service.minimumBuyRecoveryBodyAtr(
                "SOLUSDT", "15M", "binanceRange"), 0.000001);
        Assert.assertEquals(0.65, service.minimumBuyCloseLocation(
                "SOLUSDT", "15M", "binanceRange"), 0.000001);
        Assert.assertFalse(service.binanceRangeSettings(
                "SOLUSDT", "15M", "binanceRange").stateMachineEnabled);
        Assert.assertTrue(service.isStrategyEnabled(
                "SOLUSDT", "15M", "atrChannelBiasReversion"));
        Assert.assertTrue(service.isStrategyEnabled(
                "SOLUSDT", "15M", "compressionBreak"));
        Assert.assertTrue(service.isSideEnabled(
                "SOLUSDT", "15M", "donchianReversion", "BUY"));
        Assert.assertFalse(service.isSideEnabled(
                "SOLUSDT", "15M", "donchianReversion", "SELL"));
        Assert.assertFalse(service.isSideEnabled(
                "SOLUSDT", "15M", "atrChannelReversion", "BUY"));
        Assert.assertFalse(service.isSideEnabled(
                "SOLUSDT", "15M", "atrChannelReversion", "SELL"));

        Assert.assertNull(service.takeProfitPct("BTCUSDT", "15M", "binanceTrend"));
        Assert.assertTrue(service.binanceTrendBuyEnabled("BTCUSDT","15M"));
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
