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

public class TrendPullbackRecoveryMacdTest {

    @Test
    public void incrementalMacdMatchesLegacyCalculationIncludingHistoricalLookup() throws Exception {
        BarSeries series = new BaseBarSeries("macd-equivalence");
        ZonedDateTime start = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
        for (int i = 0; i < 140; i++) {
            double close = 1000.0 + i * 0.7 + Math.sin(i / 4.0) * 8.0;
            series.addBar(new BaseBar(Duration.ofMinutes(15), start.plusMinutes(i * 15L),
                    BigDecimal.valueOf(close - 1.0), BigDecimal.valueOf(close + 3.0),
                    BigDecimal.valueOf(close - 3.0), BigDecimal.valueOf(close),
                    BigDecimal.valueOf(1000 + i)));
        }

        TrendPullbackRecoveryBacktestStrategy strategy = new TrendPullbackRecoveryBacktestStrategy();
        Method method = TrendPullbackRecoveryBacktestStrategy.class.getDeclaredMethod(
                "macdBar", BarSeries.class, int.class, int.class, int.class, int.class);
        method.setAccessible(true);

        for (int end : new int[]{35, 50, 90, 139, 138}) {
            double actual = (Double) method.invoke(strategy, series, end, 12, 26, 9);
            double expected = legacyMacd(series, end, 12, 26, 9);
            Assert.assertEquals("end=" + end, expected, actual, 1e-12);
        }
    }

    private double legacyMacd(BarSeries series, int end, int fast, int slow, int signal) {
        int start = slow - 1;
        double[] dif = new double[end - start + 1];
        for (int i = start; i <= end; i++) {
            dif[i - start] = legacyEma(series, i, fast) - legacyEma(series, i, slow);
        }
        double dea = arrayEma(dif, signal);
        return dif[dif.length - 1] - dea;
    }

    private double legacyEma(BarSeries series, int end, int period) {
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += series.getBar(i).getClosePrice().doubleValue();
        }
        double ema = sum / period;
        double k = 2.0 / (period + 1.0);
        for (int i = period; i <= end; i++) {
            ema = series.getBar(i).getClosePrice().doubleValue() * k + ema * (1.0 - k);
        }
        return ema;
    }

    private double arrayEma(double[] values, int period) {
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += values[i];
        }
        double ema = sum / period;
        double k = 2.0 / (period + 1.0);
        for (int i = period; i < values.length; i++) {
            ema = values[i] * k + ema * (1.0 - k);
        }
        return ema;
    }
}
