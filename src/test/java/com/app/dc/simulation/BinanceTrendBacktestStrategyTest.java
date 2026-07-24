package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.trend.BinanceTrendBacktestStrategy;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class BinanceTrendBacktestStrategyTest {

    private final BinanceTrendBacktestStrategy strategy = new BinanceTrendBacktestStrategy();

    @Test
    public void buySignalUsesFivePercentStopLoss() {
        assertFivePercentStop(series(true), Side.BUY);
    }

    @Test
    public void sellSignalUsesFivePercentStopLoss() {
        assertFivePercentStop(series(false), Side.SELL);
    }

    private void assertFivePercentStop(BarSeries series, Side expectedSide) {
        TTbookOhlc current = new TTbookOhlc();
        current.close = BigDecimal.valueOf(series.getLastBar().getClosePrice().doubleValue());

        Signal signal = strategy.evaluate("ETHUSDT", "15m", series, current);

        Assert.assertEquals(expectedSide, signal.side);
        Assert.assertNotNull(signal.stopPrice);
        BigDecimal expected = expectedSide == Side.BUY
                ? signal.price.multiply(new BigDecimal("0.95"))
                : signal.price.multiply(new BigDecimal("1.05"));
        Assert.assertEquals(0, expected.setScale(6).compareTo(signal.stopPrice));
    }

    private BarSeries series(boolean rising) {
        BarSeries series = new BaseBarSeries("binance-trend-stop");
        ZonedDateTime start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault());
        for (int i = 0; i < 70; i++) {
            double close = rising ? 100.0 + i * 0.1 : 107.0 - i * 0.1;
            series.addBar(new BaseBar(Duration.ofMinutes(15), start.plusMinutes(i * 15L),
                    BigDecimal.valueOf(close), BigDecimal.valueOf(close + 0.2),
                    BigDecimal.valueOf(close - 0.2), BigDecimal.valueOf(close),
                    BigDecimal.valueOf(1000)));
        }
        return series;
    }
}
