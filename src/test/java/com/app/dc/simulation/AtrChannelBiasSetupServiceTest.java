package com.app.dc.simulation;

import com.app.dc.service.simulation.strategy.range.AtrChannelBiasSetupService;
import com.app.dc.service.simulation.strategy.range.AtrChannelBiasSetupSnapshot;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class AtrChannelBiasSetupServiceTest {
    @Test
    public void repeatedUpdateOfSameClosedBarIsIdempotentAndResetClearsCache() {
        AtrChannelBiasSetupService service = new AtrChannelBiasSetupService();
        BarSeries series = series();
        AtrChannelBiasSetupSnapshot first = service.update("SOLUSDT", "15M", series);
        AtrChannelBiasSetupSnapshot repeated = service.update("SOLUSDT", "15M", series);
        Assert.assertSame(first, repeated);
        Assert.assertTrue(first.readiness >= 0 && first.readiness <= 1);

        service.reset("SOLUSDT");
        AtrChannelBiasSetupSnapshot missing = service.current(
                "SOLUSDT", "15M", series.getEndIndex());
        Assert.assertEquals(AtrChannelBiasSetupSnapshot.WATCH, missing.phase);
        Assert.assertEquals("ATR_CHANNEL_NOT_UPDATED", missing.reason);
    }

    private BarSeries series() {
        BarSeries result = new BaseBarSeries("atr-channel-setup");
        ZonedDateTime start = ZonedDateTime.of(
                2026, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault());
        for (int i = 0; i < 60; i++) {
            double close = 100 + i * .20 + Math.sin(i / 4.0) * .30;
            result.addBar(new BaseBar(Duration.ofMinutes(15),
                    start.plusMinutes(i * 15L),
                    BigDecimal.valueOf(close - .15), BigDecimal.valueOf(close + .45),
                    BigDecimal.valueOf(close - .45), BigDecimal.valueOf(close),
                    BigDecimal.valueOf(1000 + i * 3)));
        }
        return result;
    }
}
