package com.app.dc.service.simulation;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class BacktestQueryServiceTest {

    @Test
    public void buildOhlcSqlShouldSelectOneDeterministicBarPerStartTime() {
        BacktestQueryService.QueryAndArgs query = new BacktestQueryService().buildOhlcSql(
                "UNIUSDT", "15m", "2025-10-08", "2026-10-08");

        Assert.assertTrue(query.sql.contains(
                "ORDER BY startTime ASC,(length(fmtTime)>=10) DESC,endTime DESC,fmtTime ASC"));
        Assert.assertTrue(query.sql.endsWith("LIMIT 1 BY startTime"));
        Assert.assertEquals(Arrays.<Object>asList(
                "UNIUSDT", "15m", "2025-10-08", "2026-10-08"), query.args);
    }

    @Test
    public void buildOhlcCountSqlShouldCountUniqueBars() {
        BacktestQueryService.QueryAndArgs query = new BacktestQueryService().buildOhlcCountSql(
                "UNIUSDT", "15m", "2025-10-08", "2026-10-08");

        Assert.assertTrue(query.sql.startsWith("SELECT uniqExact(startTime) AS count"));
    }
}
