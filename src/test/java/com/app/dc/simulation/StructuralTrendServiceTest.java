package com.app.dc.simulation;

import com.app.dc.strategy.core.deterministic.StructuralTrendService;
import com.app.dc.strategy.core.deterministic.StructuralTrendSnapshot;
import com.app.dc.strategy.core.deterministic.StructuralTrendState;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class StructuralTrendServiceTest {

    @Test
    public void confirmsSlowBullAndRetainsItThroughOrdinaryPullback() throws Exception {
        StructuralTrendService service = service();
        StructuralTrendState state = service.newState();
        BarSeries series = new BaseBarSeries("structural-bull");
        ZonedDateTime time = ZonedDateTime.of(2025, 6, 1, 0, 0, 0, 0,
                ZoneId.of("Asia/Shanghai"));
        double price = 100;
        StructuralTrendSnapshot snapshot = StructuralTrendSnapshot.warmup();

        for (int i = 0; i < 80; i++) {
            price *= 1.003;
            series.addBar(bar(time.plusMinutes(i * 15L), price, price * .998, price * 1.002));
            snapshot = service.update(state, series);
        }
        Assert.assertEquals(StructuralTrendSnapshot.BULL, snapshot.direction);

        for (int i = 80; i < 84; i++) {
            price *= .985;
            series.addBar(bar(time.plusMinutes(i * 15L), price, price * .995, price * 1.002));
            snapshot = service.update(state, series);
        }
        Assert.assertEquals("short counter move must not erase the structural bull",
                StructuralTrendSnapshot.BULL, snapshot.direction);
        Assert.assertEquals("PULLBACK", snapshot.phase);
    }

    @Test
    public void reversalRequiresIndependentConfirmation() throws Exception {
        StructuralTrendService service = service();
        StructuralTrendState state = service.newState();
        BarSeries series = new BaseBarSeries("structural-reversal");
        ZonedDateTime time = ZonedDateTime.of(2025, 6, 1, 0, 0, 0, 0,
                ZoneId.of("Asia/Shanghai"));
        double price = 100;
        StructuralTrendSnapshot snapshot = StructuralTrendSnapshot.warmup();

        for (int i = 0; i < 80; i++) {
            price *= 1.003;
            series.addBar(bar(time.plusMinutes(i * 15L), price, price * .998, price * 1.002));
            snapshot = service.update(state, series);
        }
        Assert.assertEquals(StructuralTrendSnapshot.BULL, snapshot.direction);

        for (int i = 80; i < 140; i++) {
            price *= .99;
            series.addBar(bar(time.plusMinutes(i * 15L), price, price * .998, price * 1.002));
            snapshot = service.update(state, series);
        }
        Assert.assertEquals(StructuralTrendSnapshot.BEAR, snapshot.direction);
    }

    private StructuralTrendService service() throws Exception {
        StructuralTrendService service = new StructuralTrendService();
        set(service, "enabled", true);
        set(service, "minimumBars", 30);
        set(service, "fastEmaBars", 8);
        set(service, "slowEmaBars", 24);
        set(service, "slopeLookbackBars", 4);
        set(service, "structureLookbackBars", 12);
        set(service, "confirmationBars", 3);
        set(service, "reversalConfirmationBars", 5);
        set(service, "pullbackToleranceBars", 12);
        set(service, "minimumConfidence", .45);
        set(service, "fullSpreadPct", .02);
        set(service, "fullSlopePct", .01);
        return service;
    }

    private BaseBar bar(ZonedDateTime time, double close, double low, double high) {
        BigDecimal c = BigDecimal.valueOf(close);
        return new BaseBar(Duration.ofMinutes(15), time, c, BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), c, BigDecimal.valueOf(100));
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
