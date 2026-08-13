package com.app.dc.service.simulation.runtime;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestOptimizationService;
import com.app.dc.service.simulation.BacktestSupportService;
import com.app.dc.service.simulation.scene.DeepSeekSceneTimelineService;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SliceOptimizedSceneConditionedWalkForwardRunnerTest {

    @Test
    public void sceneWalkForwardShouldIgnoreFullPeriodLossForProfitQualification() throws Exception {
        CapturingRunner runtime = new CapturingRunner(false);
        SliceOptimizedWalkForwardRunner runner = runner(runtime);

        BacktestModels.BacktestResult result = runner.runSceneConditioned(
                candidate(), param(), bars(), plan(), 2, 2, 2, 3, 20, timeline());

        Assert.assertEquals(1, runtime.fullPeriodRuns);
        Assert.assertTrue(runtime.sceneRuns > 0);
        Assert.assertEquals(BacktestModels.SCENE_CONDITIONED_WINDOW_MODE, result.windowMode);
        Assert.assertEquals(Integer.valueOf(1), result.oosPass);
        Assert.assertTrue(result.validatePnl.compareTo(BigDecimal.ZERO) > 0);
        Assert.assertTrue(result.forwardPnl.compareTo(BigDecimal.ZERO) > 0);
        Assert.assertTrue(result.fullPeriodSafety.passed);
        Assert.assertEquals(Integer.valueOf(1), runtime.fullPeriodStrategyParams.get("risk"));
        Assert.assertEquals(Integer.valueOf(3), result.sliceCount);
    }

    @Test
    public void sceneWalkForwardShouldRejectNegativeSceneForward() throws Exception {
        CapturingRunner runtime = new CapturingRunner(true);
        SliceOptimizedWalkForwardRunner runner = runner(runtime);

        BacktestModels.BacktestResult result = runner.runSceneConditioned(
                candidate(), param(), bars(), plan(), 2, 2, 2, 3, 20, timeline());

        Assert.assertEquals(Integer.valueOf(0), result.oosPass);
        Assert.assertTrue(result.validatePnl.compareTo(BigDecimal.ZERO) > 0);
        Assert.assertTrue(result.forwardPnl.compareTo(BigDecimal.ZERO) < 0);
        Assert.assertEquals("validate_pnl > 0 but forward_pnl <= 0", result.overfitReason);
    }

    private SliceOptimizedWalkForwardRunner runner(VersionedBacktestRunner runtime) throws Exception {
        SliceOptimizedWalkForwardRunner runner = new SliceOptimizedWalkForwardRunner();
        setField(runner, "versionedBacktestRunner", runtime);
        setField(runner, "supportService", new BacktestSupportService());
        setField(runner, "backtestOptimizationService", new BacktestOptimizationService());
        return runner;
    }

    private StrategyCandidateRow candidate() {
        StrategyCandidateRow row = new StrategyCandidateRow();
        row.strategyName = "scene_range_test";
        row.strategyVersion = "v1";
        row.runtimeType = "JAR";
        row.scene = "range";
        row.payload = "{}";
        return row;
    }

    private BacktestParam param() {
        BacktestParam param = new BacktestParam();
        param.strategyName = "scene_range_test";
        param.strategyVersion = "v1";
        param.symbol = "BTCUSDT";
        param.symbols = "BTCUSDT";
        param.text = "1d";
        param.beginDate = "2026-01-01";
        param.endDate = "2026-01-10";
        param.initialCapital = BigDecimal.valueOf(10000D);
        param.entryMakerFeeRatePct = BigDecimal.ZERO;
        param.exitTakerFeeRatePct = BigDecimal.ZERO;
        param.strategyParams = new LinkedHashMap<String, Object>();
        return param;
    }

    private BacktestOptimizationService.OptimizationPlan plan() {
        BacktestOptimizationService.OptimizationPlan plan = new BacktestOptimizationService.OptimizationPlan();
        plan.optimizationSupported = false;
        plan.defaultParams.put("risk", 1);
        plan.fitWindowDays = 2;
        plan.validateWindowDays = 2;
        plan.forwardWindowDays = 2;
        plan.minSliceCount = 3;
        return plan;
    }

    private List<TTbookOhlc> bars() {
        List<TTbookOhlc> rows = new ArrayList<TTbookOhlc>();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        for (int i = 0; i < 10; i++) {
            LocalDate date = LocalDate.of(2026, 1, 1).plusDays(i);
            LocalDateTime start = date.atStartOfDay();
            TTbookOhlc row = new TTbookOhlc();
            row.tradeDate = date.toString();
            row.starttime = format.format(start);
            row.endtime = format.format(start.plusDays(1).minusSeconds(1));
            row.opentime = row.starttime;
            row.text = "1D";
            row.securityid = "BTCUSDT";
            row.open = BigDecimal.ONE;
            row.high = BigDecimal.ONE;
            row.low = BigDecimal.ONE;
            row.close = BigDecimal.ONE;
            rows.add(row);
        }
        return rows;
    }

    private DeepSeekSceneTimelineService.Timeline timeline() {
        List<DeepSeekSceneTimelineService.SceneRow> rows = new ArrayList<DeepSeekSceneTimelineService.SceneRow>();
        for (int i = 0; i < 20; i++) {
            DeepSeekSceneTimelineService.SceneRow row = new DeepSeekSceneTimelineService.SceneRow();
            row.runTime = LocalDateTime.of(2026, 1, 1, 0, 0).plusHours(i * 12L)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            row.scene = "range";
            row.status = "SUCCESS";
            row.analysisType = "historical_kline_scene";
            row.promptVersion = "market_scene_v3_historical_kline_v1";
            rows.add(row);
        }
        return DeepSeekSceneTimelineService.buildTimeline(rows, 13);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class CapturingRunner extends VersionedBacktestRunner {
        private final boolean negativeForward;
        private int fullPeriodRuns;
        private int sceneRuns;
        private Map<String, Object> fullPeriodStrategyParams;

        private CapturingRunner(boolean negativeForward) {
            this.negativeForward = negativeForward;
        }

        @Override
        public BacktestModels.BacktestResult run(StrategyCandidateRow candidate,
                                                 BacktestParam param,
                                                 List<TTbookOhlc> rows) {
            fullPeriodRuns++;
            fullPeriodStrategyParams = new LinkedHashMap<String, Object>(param.strategyParams);
            return result(rows, BigDecimal.valueOf(-999D));
        }

        @Override
        public BacktestModels.BacktestResult runSceneGated(
                StrategyCandidateRow candidate,
                BacktestParam param,
                List<TTbookOhlc> rows,
                DeepSeekSceneTimelineService.Cursor cursor,
                BacktestModels.SceneShadowMetrics metrics) {
            int phase = sceneRuns++ % 3;
            BigDecimal pnl = phase == 2 && negativeForward
                    ? BigDecimal.valueOf(-20D) : BigDecimal.valueOf(20D);
            metrics.coveredBarCount = rows.size();
            metrics.matchedBarCount = rows.size();
            return result(rows, pnl);
        }

        private BacktestModels.BacktestResult result(List<TTbookOhlc> rows, BigDecimal pnl) {
            BacktestModels.BacktestResult result = new BacktestModels.BacktestResult();
            result.totalPnl = pnl;
            result.totalReturnPct = pnl.divide(BigDecimal.valueOf(10000D));
            result.totalBars = rows == null ? 0 : rows.size();
            result.tradeCount = 5;
            result.winCount = pnl.signum() > 0 ? 3 : 1;
            result.lossCount = pnl.signum() > 0 ? 2 : 4;
            result.maxDrawdownPct = BigDecimal.valueOf(0.05D);
            result.profitFactor = pnl.signum() > 0 ? BigDecimal.valueOf(1.5D) : BigDecimal.valueOf(0.7D);
            result.winRate = BigDecimal.valueOf(0.6D);
            result.totalFee = BigDecimal.ONE;
            result.tradeList = new ArrayList<BacktestModels.TradeRecord>();
            result.rejectReasonCounts = new LinkedHashMap<String, Integer>();
            return result;
        }
    }
}
