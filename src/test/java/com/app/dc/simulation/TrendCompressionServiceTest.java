package com.app.dc.simulation;

import com.app.dc.strategy.core.deterministic.MarketContextFactory;
import com.app.dc.strategy.core.deterministic.StrategyEvaluationContext;
import com.app.dc.strategy.core.deterministic.TrendCompressionService;
import com.app.dc.strategy.core.deterministic.TrendCompressionSnapshot;
import com.app.dc.strategy.core.deterministic.TrendCompressionState;
import com.app.dc.strategy.core.dynamic.MarketRegime;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TrendCompressionServiceTest {

    @Test
    public void armsInDirectionalLowVolatilityAndTriggersAfterExpansion() {
        BarSeries series = risingSeries();
        MarketContextFactory factory = new MarketContextFactory();
        TrendCompressionService service = new TrendCompressionService();
        TrendCompressionState state = service.newState();

        TrendCompressionSnapshot snapshot = null;
        for (int i = 0; i < 3; i++) {
            add(series, 110 + i * .08, 110.18 + i * .08,
                    109.94 + i * .08, 110.12 + i * .08, 1000);
            snapshot = service.update(state, context(factory, series, "UP", "LOW"));
        }
        Assert.assertNotNull(snapshot);
        Assert.assertEquals(TrendCompressionSnapshot.ARMED, snapshot.phase);
        Assert.assertEquals("UP", snapshot.direction);

        add(series, 110.35, 112.40, 110.30, 112.10, 1800);
        snapshot = service.update(state, context(factory, series, "UP", "NORMAL"));
        Assert.assertFalse(snapshot.triggered);
        Assert.assertEquals(TrendCompressionSnapshot.BREAKOUT_PENDING, snapshot.phase);

        add(series, 111.50, 112.30, 110.35, 112.20, 1300);
        snapshot = service.update(state, context(factory, series, "UP", "NORMAL"));
        Assert.assertTrue(snapshot.triggered);
        Assert.assertEquals(TrendCompressionSnapshot.TRIGGERED, snapshot.phase);
        Assert.assertEquals("UP", snapshot.direction);
    }

    private StrategyEvaluationContext context(MarketContextFactory factory,
                                              BarSeries series,
                                              String trend, String volatility) {
        MarketRegime regime = new MarketRegime();
        regime.trend = trend;
        regime.volatility = volatility;
        regime.confidence = .80;
        regime.tradeable = true;
        regime.barTime = series.getEndIndex() * 900000L;
        return factory.create("ETHUSDT", "15M", series, null, regime);
    }

    private BarSeries risingSeries() {
        BarSeries series = new BaseBarSeries("trend-compression");
        for (int i = 0; i < 90; i++) {
            double close = 100 + i * .11;
            add(series, close - .04, close + .12, close - .12, close, 1000);
        }
        return series;
    }

    private void add(BarSeries series, double open, double high, double low,
                     double close, double volume) {
        ZonedDateTime start = ZonedDateTime.of(2026, 1, 1, 0, 0,
                0, 0, ZoneId.systemDefault());
        series.addBar(new BaseBar(Duration.ofMinutes(15),
                start.plusMinutes(series.getBarCount() * 15L),
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close),
                BigDecimal.valueOf(volume)));
    }
}
