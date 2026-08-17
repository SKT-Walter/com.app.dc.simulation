package com.app.dc.simulation;

import com.app.dc.strategy.core.strategy.range.BinanceRangeSettings;
import com.app.dc.strategy.core.strategy.range.BinanceRangeState;
import com.app.dc.strategy.core.strategy.range.BinanceRangeStateMachine;
import com.app.dc.strategy.core.strategy.range.BinanceRangeStateSnapshot;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class BinanceRangeStateMachineTest {

    @Test
    public void touchReclaimAndNextBarConfirmationAreSequentialAndIdempotent() {
        BinanceRangeStateMachine machine = new BinanceRangeStateMachine();
        BinanceRangeState state = machine.newState();
        BarSeries series = stableOscillation();

        add(series, 98.70, 98.80, 98.30, 98.55);
        BinanceRangeStateSnapshot touched = machine.update(state, series, settings());
        Assert.assertEquals("TOUCHED", touched.phase);
        Assert.assertFalse(touched.actionable());
        Assert.assertSame(touched, machine.update(state, series, settings()));

        add(series, 98.55, 98.90, 98.35, 98.72);
        BinanceRangeStateSnapshot reclaimed = machine.update(state, series, settings());
        Assert.assertEquals("RECLAIMED", reclaimed.phase);
        Assert.assertFalse(reclaimed.actionable());

        add(series, 98.72, 98.95, 98.65, 98.88);
        BinanceRangeStateSnapshot confirmed = machine.update(state, series, settings());
        Assert.assertEquals("CONFIRMED", confirmed.phase);
        Assert.assertEquals("BUY", confirmed.side);
        Assert.assertTrue(confirmed.actionable());
        Assert.assertTrue(confirmed.stopPrice < 98.88);
        Assert.assertTrue(confirmed.takePrice > 98.88);
        Assert.assertTrue(confirmed.rewardRisk >= settings().minimumRewardRisk);
    }

    @Test
    public void touchExpiresWithoutReclaimAndNeverSignalsOnTouchBar() {
        BinanceRangeStateMachine machine = new BinanceRangeStateMachine();
        BinanceRangeState state = machine.newState();
        BarSeries series = stableOscillation();
        add(series, 98.70, 98.80, 98.30, 98.55);
        Assert.assertEquals("TOUCHED",
                machine.update(state, series, settings()).phase);
        for (int i = 0; i < 4; i++) {
            add(series, 98.55, 98.75, 98.35, 98.50);
            Assert.assertFalse(machine.update(state, series, settings()).actionable());
        }
    }

    @Test
    public void reclaimBarIsImmediatelyActionableWhenNextBarConfirmationIsDisabled() {
        BinanceRangeStateMachine machine = new BinanceRangeStateMachine();
        BinanceRangeState state = machine.newState();
        BarSeries series = stableOscillation();
        BinanceRangeSettings settings = settings(false);

        add(series, 98.70, 98.80, 98.30, 98.55);
        Assert.assertEquals("TOUCHED",
                machine.update(state, series, settings).phase);

        add(series, 98.55, 98.90, 98.35, 98.72);
        BinanceRangeStateSnapshot confirmed =
                machine.update(state, series, settings);
        Assert.assertEquals("CONFIRMED", confirmed.phase);
        Assert.assertEquals("BUY", confirmed.side);
        Assert.assertTrue(confirmed.actionable());
    }

    private BinanceRangeSettings settings() {
        return settings(true);
    }

    private BinanceRangeSettings settings(boolean nextBarConfirmationRequired) {
        return new BinanceRangeSettings(true, 20, 3, 1,
                2.0, 8.0, .15, 3, 3, nextBarConfirmationRequired, .50, 1.20,
                .25, 0, 0, .08, .65);
    }

    private BarSeries stableOscillation() {
        BarSeries series = new BaseBarSeries("range-state");
        double[] pattern = {98.5, 99.0, 99.5, 100.0, 100.5, 101.0,
                101.5, 101.0, 100.5, 100.0, 99.5, 99.0};
        for (int i = 0; i < 48; i++) {
            double close = pattern[i % pattern.length];
            add(series, close, close + .15, close - .15, close);
        }
        return series;
    }

    private void add(BarSeries series, double open, double high,
                     double low, double close) {
        ZonedDateTime time = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0,
                ZoneId.systemDefault()).plusMinutes(series.getBarCount() * 15L);
        series.addBar(new BaseBar(Duration.ofMinutes(15), time,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close),
                BigDecimal.valueOf(1000)));
    }
}
