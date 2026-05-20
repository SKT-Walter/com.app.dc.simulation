package com.app.dc.service.simulation.runtime;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestSupportService;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class WalkForwardBacktestRunnerTest {

    @Test
    public void runShouldUseAvailableRangeWhenRequestedBeginDateIsEarlierThanHistory() throws Exception {
        WalkForwardBacktestRunner runner = new WalkForwardBacktestRunner();
        CapturingVersionedBacktestRunner fakeRunner = new CapturingVersionedBacktestRunner();
        setField(runner, "versionedBacktestRunner", fakeRunner);
        setField(runner, "supportService", new BacktestSupportService());

        StrategyCandidateRow candidate = new StrategyCandidateRow();
        candidate.strategyName = "震荡_网格";
        candidate.strategyVersion = "v1";
        candidate.scene = "range";
        candidate.payload = "{}";

        BacktestParam param = new BacktestParam();
        param.symbol = "币安人生USDT";
        param.text = "15m";
        param.beginDate = "2024-05-15";
        param.endDate = "2026-05-15";
        param.initialCapital = BigDecimal.valueOf(10000);
        param.entryMakerFeeRatePct = BigDecimal.ZERO;
        param.exitTakerFeeRatePct = BigDecimal.ZERO;

        List<TTbookOhlc> rows = buildRows(LocalDate.of(2025, 10, 20), 220);
        BacktestModels.BacktestResult result = runner.run(candidate, param, rows, 120, 30, 14, 3);

        Assert.assertEquals(Integer.valueOf(4), result.sliceCount);
        Assert.assertEquals("2025-10-20", fakeRunner.beginDates.get(0));
        Assert.assertEquals("2026-02-16", fakeRunner.endDates.get(0));
    }

    @Test
    public void runShouldAllowPartialFirstDayWhenAvailableHistoryStartsMidday() throws Exception {
        WalkForwardBacktestRunner runner = new WalkForwardBacktestRunner();
        CapturingVersionedBacktestRunner fakeRunner = new CapturingVersionedBacktestRunner();
        setField(runner, "versionedBacktestRunner", fakeRunner);
        setField(runner, "supportService", new BacktestSupportService());

        StrategyCandidateRow candidate = new StrategyCandidateRow();
        candidate.strategyName = "震荡_网格";
        candidate.strategyVersion = "v1";
        candidate.scene = "range";
        candidate.payload = "{}";

        BacktestParam param = new BacktestParam();
        param.symbol = "币安人生USDT";
        param.text = "15m";
        param.beginDate = "2024-05-15";
        param.endDate = "2026-05-15";
        param.initialCapital = BigDecimal.valueOf(10000);
        param.entryMakerFeeRatePct = BigDecimal.ZERO;
        param.exitTakerFeeRatePct = BigDecimal.ZERO;

        List<TTbookOhlc> rows = buildRowsWithPartialFirstDay(LocalDate.of(2025, 10, 20), 220, 44);
        BacktestModels.BacktestResult result = runner.run(candidate, param, rows, 120, 30, 14, 3);

        Assert.assertEquals(Integer.valueOf(4), result.sliceCount);
        Assert.assertEquals("2025-10-20", fakeRunner.beginDates.get(0));
        Assert.assertEquals("2026-02-16", fakeRunner.endDates.get(0));
    }

    private List<TTbookOhlc> buildRows(LocalDate beginDate, int days) {
        List<TTbookOhlc> rows = new ArrayList<TTbookOhlc>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        for (int day = 0; day < days; day++) {
            LocalDate date = beginDate.plusDays(day);
            for (int slot = 0; slot < 96; slot++) {
                LocalDateTime start = date.atStartOfDay().plusMinutes(slot * 15L);
                TTbookOhlc row = new TTbookOhlc();
                row.tradeDate = date.toString();
                row.starttime = fmt.format(start);
                row.endtime = fmt.format(start.plusMinutes(15));
                row.text = "15M";
                row.securityid = "币安人生USDT";
                row.open = BigDecimal.ONE;
                row.high = BigDecimal.ONE;
                row.low = BigDecimal.ONE;
                row.close = BigDecimal.ONE;
                row.opentime = row.starttime;
                row.closetime = row.endtime;
                row.createtime = fmt.format(LocalDateTime.ofInstant(start.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
                rows.add(row);
            }
        }
        return rows;
    }

    private List<TTbookOhlc> buildRowsWithPartialFirstDay(LocalDate beginDate, int days, int firstDayStartSlot) {
        List<TTbookOhlc> rows = new ArrayList<TTbookOhlc>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        for (int day = 0; day < days; day++) {
            LocalDate date = beginDate.plusDays(day);
            int startSlot = day == 0 ? Math.max(0, firstDayStartSlot) : 0;
            for (int slot = startSlot; slot < 96; slot++) {
                LocalDateTime start = date.atStartOfDay().plusMinutes(slot * 15L);
                TTbookOhlc row = new TTbookOhlc();
                row.tradeDate = date.toString();
                row.starttime = fmt.format(start);
                row.endtime = fmt.format(start.plusMinutes(15));
                row.text = "15M";
                row.securityid = "币安人生USDT";
                row.open = BigDecimal.ONE;
                row.high = BigDecimal.ONE;
                row.low = BigDecimal.ONE;
                row.close = BigDecimal.ONE;
                row.opentime = row.starttime;
                row.closetime = row.endtime;
                row.createtime = fmt.format(LocalDateTime.ofInstant(start.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
                rows.add(row);
            }
        }
        return rows;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class CapturingVersionedBacktestRunner extends VersionedBacktestRunner {
        final List<String> beginDates = new ArrayList<String>();
        final List<String> endDates = new ArrayList<String>();

        @Override
        public BacktestModels.BacktestResult run(StrategyCandidateRow candidate, BacktestParam rawParam, List<TTbookOhlc> ohlcList) {
            beginDates.add(rawParam.beginDate);
            endDates.add(rawParam.endDate);
            BacktestModels.BacktestResult result = new BacktestModels.BacktestResult();
            result.tradeCount = 0;
            result.winCount = 0;
            result.lossCount = 0;
            result.flatCount = 0;
            result.stopExitCount = 0;
            result.takeExitCount = 0;
            result.stopExitWinCount = 0;
            result.stopExitLossCount = 0;
            result.takeExitWinCount = 0;
            result.takeExitLossCount = 0;
            result.totalBars = ohlcList == null ? 0 : ohlcList.size();
            result.tradeList = new ArrayList<BacktestModels.TradeRecord>();
            result.rejectReasonCounts = new java.util.LinkedHashMap<String, Integer>();
            result.maxDrawdownPct = BigDecimal.ZERO;
            result.totalReturnPct = BigDecimal.ZERO;
            return result;
        }
    }
}
