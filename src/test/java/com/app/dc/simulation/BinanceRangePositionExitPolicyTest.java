package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.strategy.exit.BinanceRangePositionExitPolicy;
import com.app.dc.service.simulation.strategy.exit.PositionExitDecision;
import com.app.dc.service.simulation.strategy.exit.StrategyPositionExitContext;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class BinanceRangePositionExitPolicyTest {
    @Test public void protectsBuyAndSellAfterHalfRiskExcursion() {
        BinanceRangePositionExitPolicy policy = new BinanceRangePositionExitPolicy();

        Position buy = position(Side.BUY, 98d);
        buy.highestSinceEntry = 101.2;
        buy.lowestSinceEntry = 99.5;
        PositionExitDecision buyDecision = policy.evaluate(buy, context(101));
        Assert.assertFalse(buyDecision.exit);
        Assert.assertEquals(100.08, buy.stopPrice, 0.000001);
        Assert.assertEquals("range_breakeven_protection_exit", buy.stopExitReason);

        Position sell = position(Side.SELL, 102d);
        sell.highestSinceEntry = 100.5;
        sell.lowestSinceEntry = 98.8;
        PositionExitDecision sellDecision = policy.evaluate(sell, context(99));
        Assert.assertFalse(sellDecision.exit);
        Assert.assertEquals(99.92, sell.stopPrice, 0.000001);
    }

    @Test public void doesNotProtectBeforeThresholdOrOutsideEth15m() {
        BinanceRangePositionExitPolicy policy = new BinanceRangePositionExitPolicy();
        Position position = position(Side.BUY, 98d);
        position.highestSinceEntry = 100.9;
        position.lowestSinceEntry = 99.5;
        policy.evaluate(position, context(100.5));
        Assert.assertEquals(98d, position.stopPrice, 0.000001);

        position.highestSinceEntry = 102;
        policy.evaluate(position, new StrategyPositionExitContext(
                series(101), null, StructuralTrendSnapshot.warmup(),
                null, "SOLUSDT", "15M"));
        Assert.assertEquals(98d, position.stopPrice, 0.000001);
    }

    private Position position(Side side, double stop) {
        Position position = new Position();
        position.side = side;
        position.strategyName = "binanceRange";
        position.entryPrice = 100;
        position.initialRiskPriceDistance = 2;
        position.stopPrice = stop;
        return position;
    }

    private StrategyPositionExitContext context(double close) {
        return new StrategyPositionExitContext(series(close), null,
                StructuralTrendSnapshot.warmup(), null, "ETHUSDT", "15M");
    }

    private BarSeries series(double close) {
        BarSeries series = new BaseBarSeries("range-exit");
        ZonedDateTime time = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0,
                ZoneId.systemDefault());
        series.addBar(new BaseBar(Duration.ofMinutes(15), time,
                BigDecimal.valueOf(close), BigDecimal.valueOf(close + .2),
                BigDecimal.valueOf(close - .2), BigDecimal.valueOf(close),
                BigDecimal.valueOf(1000)));
        return series;
    }
}
