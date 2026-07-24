package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.range.VwapReversionBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.VwapDeviationMomentumBacktestStrategy;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class VwapStrategyTest {

    @Test
    public void reversionWaitsForDirectionalRecoveryConfirmation() {
        VwapReversionBacktestStrategy strategy = new VwapReversionBacktestStrategy();

        BarSeries falling = baseSeries();
        add(falling, 99.40, 99.50, 98.60, 98.80);
        add(falling, 98.70, 98.80, 98.20, 98.40);
        Signal noRecovery = strategy.evaluate("ETHUSDT", "15m", falling, ohlc(98.40));
        assertHold(noRecovery);

        BarSeries recovering = baseSeries();
        add(recovering, 99.40, 99.50, 98.60, 98.80);
        add(recovering, 98.90, 99.40, 98.80, 99.30);
        Signal confirmed = strategy.evaluate("ETHUSDT", "15m", recovering, ohlc(99.30));

        Assert.assertEquals(Side.BUY, confirmed.side);
        Assert.assertTrue(confirmed.stopPrice.compareTo(confirmed.price) < 0);
        Assert.assertTrue(confirmed.takerPrice.compareTo(confirmed.price) > 0);
    }

    @Test
    public void stopCooldownSurvivesRoutingResetButNotNewSession() {
        VwapReversionBacktestStrategy strategy = new VwapReversionBacktestStrategy();
        BarSeries series = baseSeries();
        add(series, 99.40, 99.50, 98.60, 98.80);
        add(series, 98.90, 99.40, 98.80, 99.30);
        Assert.assertEquals(Side.BUY, strategy.evaluate("ETHUSDT", "15m", series, ohlc(99.30)).side);

        TradeRecord stop = new TradeRecord();
        stop.exitReason = "stop_loss";
        strategy.onTradeClosed("ETHUSDT", series.getEndIndex(), stop);
        strategy.resetRuntime("ETHUSDT");
        assertHold(strategy.evaluate("ETHUSDT", "15m", series, ohlc(99.30)));

        strategy.resetSession("ETHUSDT");
        Assert.assertEquals(Side.BUY, strategy.evaluate("ETHUSDT", "15m", series, ohlc(99.30)).side);
    }

    @Test
    public void momentumUsesIndependentTrendAlignedRiskModel() {
        VwapDeviationMomentumBacktestStrategy strategy = new VwapDeviationMomentumBacktestStrategy();
        BarSeries series = new BaseBarSeries("vwap-momentum");
        for (int i = 0; i < 25; i++) {
            double close = 100 + i * .12;
            add(series, close - .08, close + .15, close - .18, close);
        }
        add(series, 103.10, 103.75, 103.00, 103.65);
        add(series, 103.70, 104.55, 103.60, 104.45);

        Signal signal = strategy.evaluate("ETHUSDT", "15m", series, ohlc(104.45));

        Assert.assertEquals(Side.BUY, signal.side);
        Assert.assertTrue(signal.stopPrice.compareTo(signal.price) < 0);
        Assert.assertTrue(signal.takerPrice.compareTo(signal.price) > 0);
        BigDecimal risk = signal.price.subtract(signal.stopPrice);
        BigDecimal reward = signal.takerPrice.subtract(signal.price);
        Assert.assertEquals(0, reward.compareTo(risk.multiply(new BigDecimal("2.0"))));
    }

    private BarSeries baseSeries() {
        BarSeries series = new BaseBarSeries("vwap-reversion");
        for (int i = 0; i < 25; i++) {
            double close = 100 + Math.sin(i / 4.0) * .04;
            add(series, close - .05, close + .20, close - .20, close);
        }
        return series;
    }

    private void add(BarSeries series, double open, double high, double low, double close) {
        ZonedDateTime time = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault())
                .plusMinutes(series.getBarCount() * 15L);
        series.addBar(new BaseBar(Duration.ofMinutes(15), time,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close),
                BigDecimal.valueOf(1000)));
    }

    private TTbookOhlc ohlc(double close) {
        TTbookOhlc value = new TTbookOhlc();
        value.close = BigDecimal.valueOf(close);
        return value;
    }

    private void assertHold(Signal signal) {
        Assert.assertTrue(signal.side == null || signal.side == Side.NONE);
    }
}
