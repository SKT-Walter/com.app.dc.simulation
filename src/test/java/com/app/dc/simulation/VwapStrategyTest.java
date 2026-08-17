package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.strategy.core.StrategyRuntimeModels.TradeRecord;
import com.app.dc.strategy.core.strategy.range.VwapReversionStrategyAlgorithm;
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
        VwapReversionStrategyAlgorithm strategy = new VwapReversionStrategyAlgorithm();

        BarSeries falling = baseSeries();
        add(falling, 99.40, 99.50, 98.60, 98.80);
        add(falling, 98.70, 98.80, 98.20, 98.40);
        assertHold(strategy.evaluate("ETHUSDT", "15m", falling, ohlc(98.40)));

        BarSeries recovering = baseSeries();
        add(recovering, 99.40, 99.50, 98.60, 98.80);
        add(recovering, 98.90, 99.40, 98.80, 99.30);
        Signal confirmed = strategy.evaluate("ETHUSDT", "15m", recovering, ohlc(99.30));

        Assert.assertEquals(Side.BUY, confirmed.side);
        Assert.assertTrue(confirmed.stopPrice.compareTo(confirmed.price) < 0);
        Assert.assertTrue(confirmed.takerPrice.compareTo(confirmed.price) > 0);
    }

    @Test
    public void insufficientRewardRiskIsRejected() {
        VwapReversionStrategyAlgorithm strategy = new VwapReversionStrategyAlgorithm();
        BarSeries series = volatileBaseSeries();
        add(series, 99.40, 101.50, 97.50, 98.80);
        add(series, 98.90, 101.50, 97.50, 99.30);

        assertHold(strategy.evaluate("ETHUSDT", "15m", series, ohlc(99.30)));
    }

    @Test
    public void stopCooldownSurvivesRoutingResetButNotNewSession() {
        VwapReversionStrategyAlgorithm strategy = new VwapReversionStrategyAlgorithm();
        BarSeries series = recoverySeries();
        Assert.assertEquals(Side.BUY, strategy.evaluate("ETHUSDT", "15m", series, ohlc(99.30)).side);

        TradeRecord stop = new TradeRecord();
        stop.exitReason = "stop_loss";
        strategy.onTradeClosed("ETHUSDT", series.getEndIndex(), stop);
        strategy.resetRuntime("ETHUSDT");
        assertHold(strategy.evaluate("ETHUSDT", "15m", series, ohlc(99.30)));
        Assert.assertEquals(Integer.valueOf(1),
                strategy.snapshotRejectStats("ETHUSDT").get(VwapReversionStrategyAlgorithm.COOLDOWN_REJECTION));

        strategy.resetSession("ETHUSDT");
        Assert.assertEquals(Side.BUY, strategy.evaluate("ETHUSDT", "15m", series, ohlc(99.30)).side);
        Assert.assertTrue(strategy.snapshotRejectStats("ETHUSDT").isEmpty());
    }

    @Test
    public void takeProfitDoesNotStartCooldown() {
        VwapReversionStrategyAlgorithm strategy = new VwapReversionStrategyAlgorithm();
        BarSeries series = recoverySeries();
        TradeRecord take = new TradeRecord();
        take.exitReason = "take_profit";
        strategy.onTradeClosed("ETHUSDT", series.getEndIndex(), take);

        Assert.assertEquals(Side.BUY, strategy.evaluate("ETHUSDT", "15m", series, ohlc(99.30)).side);
    }

    private BarSeries recoverySeries() {
        BarSeries series = baseSeries();
        add(series, 99.40, 99.50, 98.60, 98.80);
        add(series, 98.90, 99.40, 98.80, 99.30);
        return series;
    }

    private BarSeries baseSeries() {
        BarSeries series = new BaseBarSeries("vwap-reversion");
        for (int i = 0; i < 25; i++) {
            double close = 100 + Math.sin(i / 4.0) * .04;
            add(series, close - .05, close + .20, close - .20, close);
        }
        return series;
    }

    private BarSeries volatileBaseSeries() {
        BarSeries series = new BaseBarSeries("vwap-volatile");
        for (int i = 0; i < 25; i++) {
            double close = 100 + Math.sin(i / 4.0) * .04;
            add(series, close - .10, close + 2.50, close - 2.50, close);
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
