package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.range.BinanceRangeBacktestStrategy;
import com.app.dc.service.simulation.strategy.range.BinanceRangeSetupAnalyzer;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class BinanceRangeStrategyTest {

    @Test
    public void entersOnlyAfterLowerEdgeIsTouchedAndRecovered() {
        BarSeries series = stableBox();
        add(series, 98.15, 98.60, 97.90, 98.50);

        BinanceRangeSetupAnalyzer.Snapshot setup = BinanceRangeSetupAnalyzer.analyze(series);
        Assert.assertTrue(setup.stable);
        Assert.assertEquals("BUY", setup.side);
        Assert.assertTrue(setup.rewardRisk >= BinanceRangeSetupAnalyzer.MIN_REWARD_RISK);

        Signal signal = new BinanceRangeBacktestStrategy()
                .evaluate("ETHUSDT", "15m", series, ohlc(98.50));
        Assert.assertEquals(Side.BUY, signal.side);
        Assert.assertTrue(signal.stopPrice.doubleValue() < 98.50);
        Assert.assertTrue(signal.takerPrice.doubleValue() > 98.50);
    }

    @Test
    public void rejectsTouchWithoutDirectionalRecovery() {
        BarSeries series = stableBox();
        add(series, 98.50, 98.60, 97.90, 98.10);

        BinanceRangeSetupAnalyzer.Snapshot setup = BinanceRangeSetupAnalyzer.analyze(series);
        Assert.assertEquals("HOLD", setup.side);
        Assert.assertEquals("RANGE_RECOVERY_NOT_CONFIRMED", setup.reason);
    }

    @Test
    public void rejectsConfirmedRecoveryWhenTriggerRangeIsBelowProfileMinimum() {
        BarSeries series = stableBox();
        add(series, 98.15, 98.60, 97.90, 98.50);

        BinanceRangeSetupAnalyzer.Snapshot setup =
                BinanceRangeSetupAnalyzer.analyze(series, 5.0);
        Assert.assertEquals("HOLD", setup.side);
        Assert.assertEquals("RANGE_TRIGGER_RANGE_TOO_SMALL", setup.reason);
        Assert.assertTrue(setup.triggerRangeAtr < 5.0);
    }

    @Test
    public void ethStyleBuyConfirmationRequiresBodyAndCloseQuality() {
        BarSeries weakBody = stableBox();
        add(weakBody, 98.40, 98.60, 97.90, 98.50);

        BinanceRangeSetupAnalyzer.Snapshot rejected =
                BinanceRangeSetupAnalyzer.analyze(weakBody, 0.0, 0.12, 0.70);
        Assert.assertEquals("HOLD", rejected.side);
        Assert.assertEquals("RANGE_BUY_CONFIRMATION_TOO_WEAK", rejected.reason);

        BarSeries confirmed = stableBox();
        add(confirmed, 97.95, 98.60, 97.90, 98.50);
        BinanceRangeSetupAnalyzer.Snapshot accepted =
                BinanceRangeSetupAnalyzer.analyze(confirmed, 0.0, 0.12, 0.70);
        Assert.assertEquals("BUY", accepted.side);
        Assert.assertTrue(accepted.bodyAtr >= 0.12);
        Assert.assertTrue(accepted.closeLocation >= 0.70);
    }

    @Test
    public void currentBreakoutDoesNotMoveReferenceBoundaryAndInvalidatesEntry() {
        BarSeries series = stableBox();
        add(series, 98.10, 98.60, 94.00, 98.50);

        BinanceRangeSetupAnalyzer.Snapshot setup = BinanceRangeSetupAnalyzer.analyze(series);
        Assert.assertEquals(98.0, setup.low, .000001);
        Assert.assertEquals("HOLD", setup.side);
        Assert.assertEquals("RANGE_BREAKOUT_INVALIDATED", setup.reason);
    }

    @Test
    public void stopExitStartsSixBarCooldownAndSessionResetClearsIt() {
        BarSeries series = stableBox();
        add(series, 98.15, 98.60, 97.90, 98.50);
        BinanceRangeBacktestStrategy strategy = new BinanceRangeBacktestStrategy();
        Assert.assertEquals(Side.BUY,
                strategy.evaluate("ETHUSDT", "15m", series, ohlc(98.50)).side);

        TradeRecord trade = new TradeRecord();
        trade.exitReason = "stop_loss";
        strategy.onTradeClosed("ETHUSDT", series.getEndIndex(), trade);
        Signal cooled = strategy.evaluate("ETHUSDT", "15m", series, ohlc(98.50));
        Assert.assertTrue(cooled.side == null || cooled.side == Side.NONE);
        Assert.assertEquals(Integer.valueOf(1), strategy.snapshotRejectStats("ETHUSDT")
                .get(BinanceRangeBacktestStrategy.COOLDOWN_REJECTION));

        strategy.resetSession("ETHUSDT");
        Assert.assertEquals(Side.BUY,
                strategy.evaluate("ETHUSDT", "15m", series, ohlc(98.50)).side);
    }

    private BarSeries stableBox() {
        BarSeries series = new BaseBarSeries("stable-range");
        for (int i = 0; i < 30; i++) {
            double close = i % 2 == 0 ? 101 : 99;
            if (i == 29) close = 98.20;
            add(series, close, 102, 98, close);
        }
        return series;
    }

    private void add(BarSeries series, double open, double high, double low, double close) {
        ZonedDateTime time = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0,
                ZoneId.systemDefault()).plusMinutes(series.getBarCount() * 15L);
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
}
