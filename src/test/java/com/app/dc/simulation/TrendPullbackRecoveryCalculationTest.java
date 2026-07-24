package com.app.dc.simulation;

import com.app.dc.service.simulation.strategy.range.TrendPullbackRecoveryBacktestStrategy;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TrendPullbackRecoveryCalculationTest {

    @Test
    public void linearMacdMatchesLegacyNestedEmaCalculation() throws Exception {
        BarSeries series = series(240);
        TrendPullbackRecoveryBacktestStrategy strategy = new TrendPullbackRecoveryBacktestStrategy();
        Method method = TrendPullbackRecoveryBacktestStrategy.class.getDeclaredMethod(
                "macdBar", BarSeries.class, int.class, int.class, int.class, int.class);
        method.setAccessible(true);

        double actual = (Double) method.invoke(strategy, series, 239, 12, 26, 9);
        double expected = legacyMacdBar(series, 239, 12, 26, 9);

        Assert.assertEquals(expected, actual, 1e-12);
    }

    private double legacyMacdBar(BarSeries series, int end, int fast, int slow, int signal) {
        int start = slow - 1;
        double[] dif = new double[end - start + 1];
        for (int i = start; i <= end; i++) {
            dif[i - start] = legacyEma(series, i, fast) - legacyEma(series, i, slow);
        }
        double dea = arrayEma(dif, signal);
        return dif[dif.length - 1] - dea;
    }

    private double legacyEma(BarSeries series, int end, int period) {
        double value = 0;
        for (int i = 0; i < period; i++) value += close(series, i);
        value /= period;
        double k = 2.0 / (period + 1.0);
        for (int i = period; i <= end; i++) value = close(series, i) * k + value * (1 - k);
        return value;
    }

    private double arrayEma(double[] values, int period) {
        double value = 0;
        for (int i = 0; i < period; i++) value += values[i];
        value /= period;
        double k = 2.0 / (period + 1.0);
        for (int i = period; i < values.length; i++) value = values[i] * k + value * (1 - k);
        return value;
    }

    private double close(BarSeries series, int index) {
        return series.getBar(index).getClosePrice().doubleValue();
    }

    private BarSeries series(int count) {
        BarSeries series = new BaseBarSeries("tpr-macd");
        ZonedDateTime start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault());
        for (int i = 0; i < count; i++) {
            double close = 1000 + i * .17 + Math.sin(i / 7.0) * 3;
            series.addBar(new BaseBar(Duration.ofMinutes(15), start.plusMinutes(i * 15L),
                    BigDecimal.valueOf(close - .5), BigDecimal.valueOf(close + 1),
                    BigDecimal.valueOf(close - 1), BigDecimal.valueOf(close),
                    BigDecimal.valueOf(1000 + i)));
        }
        return series;
    }
}
