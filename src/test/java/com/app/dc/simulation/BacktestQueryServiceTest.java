package com.app.dc.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestQueryService;
import org.junit.Assert;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.math.BigDecimal;
import java.time.Instant;

public class BacktestQueryServiceTest {

    @Test
    public void duplicateBarSelectionIsIndependentOfInputOrder() {
        BacktestQueryService service = new BacktestQueryService();
        TTbookOhlc older = row("2026-06-01 00:00:00.000", "2026-06-01 00:00:00", "100");
        TTbookOhlc newer = row("2026-06-01 00:00:00.000", "2026-06-01 08:00:00", "101");
        List<TTbookOhlc> first = service.normalizeRows(Arrays.asList(older, newer));
        List<TTbookOhlc> reversed = service.normalizeRows(Arrays.asList(newer, older));
        Assert.assertEquals(1, first.size());
        Assert.assertEquals(1, reversed.size());
        Assert.assertEquals(new BigDecimal("101"), first.get(0).close);
        Assert.assertEquals(first.get(0).close, reversed.get(0).close);
    }

    private TTbookOhlc row(String start, String fmt, String close) {
        TTbookOhlc row = new TTbookOhlc();
        row.starttime = start;
        row.fmttime = fmt;
        row.open = row.high = row.low = row.close = new BigDecimal(close);
        row.volume = BigDecimal.ONE;
        return row;
    }
    @Test
    public void localJsonFiltersClosedBarsAndSorts() throws Exception {
        Path dir = Files.createTempDirectory("sim-market-");
        String json = "[" + bar("1767226500000", "102", "true") + "," + bar("1767225600000", "100", "true") + "," + bar("1767227400000", "104", "false") + "]";
        Files.write(dir.resolve("ETHUSDT.json"), json.getBytes(StandardCharsets.UTF_8));
        BacktestQueryService service = service(dir);
        List<TTbookOhlc> rows = service.queryOhlc("ETHUSDT", "15m", "2026-01-01", "2026-01-01");
        Assert.assertEquals(2, rows.size());
        Assert.assertEquals("100", rows.get(0).close.stripTrailingZeros().toPlainString());
        Assert.assertTrue(service.getLastSource("ETHUSDT").startsWith("JSON:"));
    }

    @Test(expected = IllegalStateException.class)
    public void existingButMalformedJsonFails() throws Exception {
        Path dir = Files.createTempDirectory("sim-bad-");
        Files.write(dir.resolve("ETHUSDT.json"), "not-json".getBytes(StandardCharsets.UTF_8));
        service(dir).queryOhlc("ETHUSDT", "15m", "2026-01-01", "2026-01-01");
    }

    @Test
    public void clickHouseSqlUsesExpectedViewAndFilters() {
        BacktestQueryService.QueryAndArgs q = new BacktestQueryService().buildOhlcSql("ETHUSDT", "15M", "2026-01-01", "2026-01-02");
        Assert.assertTrue(q.sql.contains("FROM dc.kline_view"));
        Assert.assertTrue(q.sql.endsWith("ORDER BY startTime ASC"));
        Assert.assertEquals(4, q.args.size());
    }

    @Test
    public void annualJsonFilesAreMergedAcrossRequestedYears() throws Exception {
        Path dir = Files.createTempDirectory("sim-annual-");
        Files.write(dir.resolve("ETHUSDT_2025.json"),
                ("[" + bar(epoch("2025-12-31T15:45:00Z"), "100", "true") + "]").getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("ETHUSDT_2026.json"),
                ("[" + bar(epoch("2025-12-31T16:00:00Z"), "101", "true") + "]").getBytes(StandardCharsets.UTF_8));
        BacktestQueryService service = service(dir);
        List<TTbookOhlc> rows = service.queryOhlc("ETHUSDT", "15m", "2025-12-31", "2026-01-01");
        Assert.assertEquals(2, rows.size());
        Assert.assertTrue(service.getLastSource("ETHUSDT").contains("ETHUSDT_2025.json"));
        Assert.assertTrue(service.getLastSource("ETHUSDT").contains("ETHUSDT_2026.json"));
    }

    @Test
    public void legacyJsonHasPrecedenceOverAnnualFiles() throws Exception {
        Path dir = Files.createTempDirectory("sim-legacy-");
        Files.write(dir.resolve("ETHUSDT.json"),
                ("[" + bar(epoch("2025-12-31T16:00:00Z"), "111", "true") + "]").getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("ETHUSDT_2026.json"),
                ("[" + bar(epoch("2025-12-31T16:00:00Z"), "222", "true") + "]").getBytes(StandardCharsets.UTF_8));
        List<TTbookOhlc> rows = service(dir).queryOhlc("ETHUSDT", "15m", "2026-01-01", "2026-01-01");
        Assert.assertEquals(new BigDecimal("111"), rows.get(0).close);
    }

    @Test
    public void partialAnnualRangeFailsInsteadOfMixingClickHouse() throws Exception {
        Path dir = Files.createTempDirectory("sim-partial-");
        Files.write(dir.resolve("ETHUSDT_2025.json"),
                ("[" + bar(epoch("2025-12-31T15:45:00Z"), "100", "true") + "]").getBytes(StandardCharsets.UTF_8));
        try {
            service(dir).queryOhlc("ETHUSDT", "15m", "2025-12-31", "2026-01-01");
            Assert.fail("partial annual range must fail");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("ETHUSDT_2026.json"));
        }
    }

    @Test public void auxiliaryTimeframesUseIntervalSpecificAnnualFiles() throws Exception {
        Path dir=Files.createTempDirectory("sim-mtf-");
        String json="["+barFor("1767225600000","100","true","1h")+"]";
        Files.write(dir.resolve("ETHUSDT_1h_2026.json"),json.getBytes(StandardCharsets.UTF_8));
        BacktestQueryService service=service(dir);
        List<TTbookOhlc> rows=service.queryLocalOhlc("ETHUSDT","1h","2026-01-01","2026-01-01");
        Assert.assertEquals(1,rows.size());Assert.assertEquals("1H",rows.get(0).text);
    }

    private BacktestQueryService service(Path dir) throws Exception {
        BacktestQueryService s = new BacktestQueryService();
        java.lang.reflect.Field f = BacktestQueryService.class.getDeclaredField("localDataDir");
        f.setAccessible(true);
        f.set(s, dir.toString());
        return s;
    }

    private String bar(String ts, String close, String closed) {
        return barFor(ts,close,closed,"15m");
    }
    private String barFor(String ts,String close,String closed,String text){return "{\"SecurityID\":\"ETHUSDT\",\"OpenPrice\":\"100\",\"HighPrice\":\"105\",\"LowPrice\":\"95\",\"ClosePrice\":\""+close+"\",\"Volume\":\"10\",\"Info1\":\""+ts+"\",\"Info3\":\""+text+"\",\"Info4\":\""+closed+"\"}";}

    private String epoch(String instant) {
        return String.valueOf(Instant.parse(instant).toEpochMilli());
    }
}
