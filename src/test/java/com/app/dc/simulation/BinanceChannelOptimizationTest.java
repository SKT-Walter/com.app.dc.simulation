package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.strategy.core.StrategyRuntimeModels.Position;
import com.app.dc.strategy.core.deterministic.StructuralTrendSnapshot;
import com.app.dc.strategy.core.strategy.channel.BinanceChannelStrategyAlgorithm;
import com.app.dc.strategy.core.strategy.exit.BinanceChannelPositionExitPolicy;
import com.app.dc.strategy.core.strategy.exit.PositionExitDecision;
import com.app.dc.strategy.core.strategy.exit.StrategyPositionExitContext;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class BinanceChannelOptimizationTest {
    @Test
    public void ethQualityBreakoutCreatesAtrRiskWithoutFixedTakeProfit() {
        BarSeries series = breakoutSeries(220);
        TTbookOhlc current = new TTbookOhlc();
        current.close = BigDecimal.valueOf(108.0);
        Signal signal = new BinanceChannelStrategyAlgorithm().evaluate(
                "ETHUSDT", "15M", series, current);
        Assert.assertEquals(Side.BUY, signal.side);
        Assert.assertNotNull(signal.stopPrice);
        Assert.assertTrue(signal.stopPrice.doubleValue() < signal.price.doubleValue());
        Assert.assertNull(signal.takerPrice);
        Assert.assertTrue(signal.remark.startsWith("NO_FIXED_TAKE_PROFIT"));
    }

    @Test
    public void ethBreakoutRejectsWeakVolume() {
        BarSeries series = breakoutSeries(50);
        TTbookOhlc current = new TTbookOhlc();
        current.close = BigDecimal.valueOf(108.0);
        Signal signal = new BinanceChannelStrategyAlgorithm().evaluate(
                "ETHUSDT", "15M", series, current);
        Assert.assertTrue(signal.side == null || signal.side == Side.NONE);
    }

    @Test
    public void channelExitProtectsProfitAndHonorsBearReversal() {
        BinanceChannelPositionExitPolicy policy = new BinanceChannelPositionExitPolicy();
        Position position = new Position();
        position.side = Side.BUY; position.strategyName = "binanceChannel";
        position.entryPrice = 103; position.entryIndex = 50; position.entryAtr = 1.2;
        position.highestSinceEntry = 108; position.stopPrice = 100d;
        position.channelBreakoutLevel = 102.5;
        BarSeries series = breakoutSeries(220);
        PositionExitDecision hold = policy.evaluate(position, new StrategyPositionExitContext(
                series, null, StructuralTrendSnapshot.warmup(), null, "ETHUSDT", "15M"));
        Assert.assertFalse(hold.exit);
        Assert.assertTrue(position.stopPrice > position.entryPrice);

        StructuralTrendSnapshot bear = new StructuralTrendSnapshot(
                StructuralTrendSnapshot.BEAR, StructuralTrendSnapshot.BEAR,
                "ESTABLISHED", .8, 20, 0, true);
        PositionExitDecision exit = policy.evaluate(position, new StrategyPositionExitContext(
                series, null, bear, null, "ETHUSDT", "15M"));
        Assert.assertTrue(exit.exit);
        Assert.assertEquals("channel_slow_structure_reversed", exit.reason);
    }

    private BarSeries breakoutSeries(double finalVolume) {
        BarSeries series = new BaseBarSeries("channel");
        ZonedDateTime start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0,
                ZoneId.systemDefault());
        for (int i = 0; i < 70; i++) {
            double close = 100 + i * .1;
            series.addBar(new BaseBar(Duration.ofMinutes(15), start.plusMinutes(i * 15L),
                    BigDecimal.valueOf(close - .1), BigDecimal.valueOf(close + .6),
                    BigDecimal.valueOf(close - .6), BigDecimal.valueOf(close),
                    BigDecimal.valueOf(100)));
        }
        series.addBar(new BaseBar(Duration.ofMinutes(15), start.plusMinutes(70 * 15L),
                BigDecimal.valueOf(107.0), BigDecimal.valueOf(108.2),
                BigDecimal.valueOf(106.8), BigDecimal.valueOf(108.0),
                BigDecimal.valueOf(finalVolume)));
        return series;
    }
}
