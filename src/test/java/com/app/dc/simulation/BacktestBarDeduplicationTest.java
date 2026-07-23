package com.app.dc.simulation;

import com.app.dc.service.simulation.BacktestService;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class BacktestBarDeduplicationTest {
    @Test
    public void duplicateTimestampReplacesBarButIsNotASecondDecisionPoint() {
        BacktestService service = new BacktestService();
        BarSeries series = new BaseBarSeries("dedup");
        ZonedDateTime time = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0,
                ZoneId.of("Asia/Shanghai"));

        Assert.assertTrue(service.addBar(series, bar(time, "100")));
        Assert.assertFalse(service.addBar(series, bar(time, "101")));
        Assert.assertEquals(1, series.getBarCount());
        Assert.assertEquals(101.0, series.getLastBar().getClosePrice().doubleValue(), 0.0);

        Assert.assertTrue(service.addBar(series, bar(time.plusMinutes(15), "102")));
        Assert.assertEquals(2, series.getBarCount());
    }

    private BaseBar bar(ZonedDateTime time, String close) {
        BigDecimal value = new BigDecimal(close);
        return new BaseBar(Duration.ofMinutes(15), time, value, value, value, value,
                BigDecimal.ONE);
    }
}
