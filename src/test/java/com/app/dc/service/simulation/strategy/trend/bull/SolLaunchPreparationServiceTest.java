package com.app.dc.strategy.core.strategy.trend.bull;

import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class SolLaunchPreparationServiceTest {
    @Test
    public void requiresOrderedPreparationAndSecondBreakoutBar() {
        SolLaunchPreparationService service = new SolLaunchPreparationService();
        BarSeries series = flat(55);

        service.update("SOLUSDT", "15M", series, false);
        for (int i = 0; i < 3; i++) {
            add(series, 99.9, 100.5, 99.5, 100, 1000);
            service.update("SOLUSDT", "15M", series, false);
        }

        add(series, 100.0, 100.6, 99.8, 100.3, 3000);
        SolLaunchPreparationSnapshot volume = service.update("SOLUSDT", "15M", series, false);
        Assert.assertEquals("SOL_LAUNCH_VOLUME_PREHEAT", volume.reason);

        add(series, 100.2, 101.4, 99.8, 100.4, 1200);
        SolLaunchPreparationSnapshot acceleration = service.update("SOLUSDT", "15M", series, false);
        Assert.assertEquals("SOL_LAUNCH_ACCELERATION_WAITING_1H_TURN", acceleration.reason);

        add(series, 100.4, 101.8, 100.3, 101.6, 1500);
        SolLaunchPreparationSnapshot first = service.update("SOLUSDT", "15M", series, true);
        Assert.assertTrue(first.breakoutPending);
        Assert.assertFalse(first.confirmed);
        Assert.assertEquals("SOL_LAUNCH_FIRST_BREAKOUT_PENDING_CONFIRMATION", first.reason);

        add(series, 101.6, 102.3, 101.4, 102.1, 1500);
        SolLaunchPreparationSnapshot second = service.update("SOLUSDT", "15M", series, true);
        Assert.assertTrue(second.confirmed);
        Assert.assertEquals("SOL_LAUNCH_CONSECUTIVE_BREAKOUT_CONFIRMED", second.reason);
        Assert.assertSame(second, service.update("SOLUSDT", "15M", series, true));
    }

    @Test
    public void ordinaryVolumeCannotSkipThePreheatStage() {
        SolLaunchPreparationService service = new SolLaunchPreparationService();
        BarSeries series = flat(55);
        for (int i = 0; i < 5; i++) {
            service.update("SOLUSDT", "15M", series, false);
            add(series, 99.9, 100.5, 99.5, 100, 1000);
        }
        add(series, 100.0, 101.5, 99.8, 101.3, 1000);
        SolLaunchPreparationSnapshot result = service.update("SOLUSDT", "15M", series, true);
        Assert.assertFalse(result.breakoutPending);
        Assert.assertFalse(result.confirmed);
    }

    @Test
    public void resetStartsANewIndependentSession() {
        SolLaunchPreparationService service = new SolLaunchPreparationService();
        BarSeries series = flat(60);
        service.update("SOLUSDT", "15M", series, false);
        service.reset("SOLUSDT");
        SolLaunchPreparationSnapshot restarted = service.update("SOLUSDT", "15M", series, false);
        Assert.assertFalse(restarted.breakoutPending);
        Assert.assertFalse(restarted.confirmed);
    }

    private BarSeries flat(int count) {
        BarSeries series = new BaseBarSeries("sol-launch-preparation");
        for (int i = 0; i < count; i++) add(series, 99.9, 100.5, 99.5, 100, 1000);
        return series;
    }

    private void add(BarSeries series, double open, double high, double low, double close, double volume) {
        ZonedDateTime end = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0,
                ZoneId.systemDefault()).plusMinutes(series.getBarCount() * 15L);
        series.addBar(new BaseBar(Duration.ofMinutes(15), end,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high), BigDecimal.valueOf(low),
                BigDecimal.valueOf(close), BigDecimal.valueOf(volume)));
    }
}
