package com.app.dc.service.simulation.runtime;

import com.app.dc.po.backtest.BacktestParam;
import com.gateway.connector.utils.JsonUtils;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class StrategyBacktestPullJobTest {

    @Test
    public void runnableWorkerShouldCountAsActiveWatchdogProgress() {
        Assert.assertTrue(StrategyBacktestPullJob.isActivelyComputing(Thread.State.RUNNABLE));
        Assert.assertFalse(StrategyBacktestPullJob.isActivelyComputing(Thread.State.BLOCKED));
        Assert.assertFalse(StrategyBacktestPullJob.isActivelyComputing(Thread.State.WAITING));
        Assert.assertFalse(StrategyBacktestPullJob.isActivelyComputing(Thread.State.TIMED_WAITING));
        Assert.assertFalse(StrategyBacktestPullJob.isActivelyComputing(Thread.State.TERMINATED));
    }

    @Test
    public void fetchWindowShouldIncludeInFlightDuplicatesWithoutOverfillingWorkers() {
        Assert.assertEquals(3, StrategyBacktestPullJob.computeFetchLimit(1, 3, 2));
        Assert.assertEquals(3, StrategyBacktestPullJob.computeFetchLimit(20, 3, 2));
        Assert.assertEquals(3, StrategyBacktestPullJob.computeFetchLimit(3, 3, 0));
        Assert.assertEquals(0, StrategyBacktestPullJob.computeFetchLimit(3, 3, 3));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void buildSuccessPayloadShouldStripStrategyPayloadFromBacktestParam() throws Exception {
        StrategyBacktestPullJob job = new StrategyBacktestPullJob();
        StrategyBacktestTaskRow task = new StrategyBacktestTaskRow();
        task.id = "bt_test_1";

        BacktestParam backtestParam = new BacktestParam();
        backtestParam.strategyName = "wb15_channel_c404";
        backtestParam.strategyVersion = "v2";
        backtestParam.symbol = "TRXUSDT";
        backtestParam.symbols = "TRXUSDT";
        backtestParam.text = "15M";
        backtestParam.beginDate = "2026-01-01";
        backtestParam.endDate = "2026-06-12";
        backtestParam.strategyPayload = repeat("X", 200000);
        backtestParam.strategyParams.put("atrLength", 21);

        StrategyBacktestTaskPayloadEnvelope envelope = new StrategyBacktestTaskPayloadEnvelope();
        envelope.backtestParam = backtestParam;
        envelope.runningProgress = Collections.<String, Object>singletonMap("phase", "optimize");
        task.payload = JsonUtils.Serializer(envelope);

        Map<String, Object> taskResult = new LinkedHashMap<String, Object>();
        taskResult.put("reportPath", "/tmp/report.html");
        taskResult.put("elapsedMs", 1234L);

        Method method = StrategyBacktestPullJob.class.getDeclaredMethod(
                "buildSuccessPayload", StrategyBacktestTaskRow.class, Map.class);
        method.setAccessible(true);
        String payload = (String) method.invoke(job, task, taskResult);

        Map<String, Object> root = JsonUtils.Deserialize(payload, Map.class);
        Map<String, Object> compactBacktestParam = (Map<String, Object>) root.get("backtestParam");
        Map<String, Object> savedTaskResult = (Map<String, Object>) root.get("taskResult");

        Assert.assertNotNull(compactBacktestParam);
        Assert.assertEquals("", String.valueOf(compactBacktestParam.get("strategyPayload")));
        Assert.assertEquals("TRXUSDT", String.valueOf(compactBacktestParam.get("symbol")));
        Assert.assertFalse(root.containsKey("runningProgress"));
        Assert.assertEquals("/tmp/report.html", String.valueOf(savedTaskResult.get("reportPath")));
        Assert.assertTrue(payload.length() < task.payload.length());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void buildRunningPayloadShouldStripStrategyPayloadFromBacktestParam() throws Exception {
        StrategyBacktestPullJob job = new StrategyBacktestPullJob();
        StrategyBacktestTaskRow task = new StrategyBacktestTaskRow();
        task.id = "bt_test_2";

        BacktestParam backtestParam = new BacktestParam();
        backtestParam.strategyName = "wb15_channel_c404";
        backtestParam.strategyVersion = "v2";
        backtestParam.symbol = "SOLUSDT";
        backtestParam.text = "15M";
        backtestParam.strategyPayload = repeat("Y", 120000);

        StrategyBacktestTaskPayloadEnvelope envelope = new StrategyBacktestTaskPayloadEnvelope();
        envelope.backtestParam = backtestParam;
        task.payload = JsonUtils.Serializer(envelope);

        Map<String, Object> progress = new LinkedHashMap<String, Object>();
        progress.put("phase", "walk_forward");
        progress.put("sliceNo", 2);

        Method method = StrategyBacktestPullJob.class.getDeclaredMethod(
                "buildRunningPayload", StrategyBacktestTaskRow.class, Map.class);
        method.setAccessible(true);
        String payload = (String) method.invoke(job, task, progress);

        Map<String, Object> root = JsonUtils.Deserialize(payload, Map.class);
        Map<String, Object> compactBacktestParam = (Map<String, Object>) root.get("backtestParam");
        Map<String, Object> runningProgress = (Map<String, Object>) root.get("runningProgress");

        Assert.assertNotNull(compactBacktestParam);
        Assert.assertEquals("", String.valueOf(compactBacktestParam.get("strategyPayload")));
        Assert.assertEquals("SOLUSDT", String.valueOf(compactBacktestParam.get("symbol")));
        Assert.assertEquals("walk_forward", String.valueOf(runningProgress.get("phase")));
        Assert.assertTrue(payload.length() < task.payload.length());
    }

    @Test
    public void buildParamShouldPreferNestedEnvelopeRangeOverWorkflowMetadata() throws Exception {
        StrategyBacktestPullJob job = new StrategyBacktestPullJob();
        StrategyBacktestTaskRow task = new StrategyBacktestTaskRow();
        task.id = "bt_live_recheck";
        task.fitWindowDays = 120;
        task.validateWindowDays = 30;
        task.forwardWindowDays = 14;

        BacktestParam nested = new BacktestParam();
        nested.strategyName = "wb15_trend_t001";
        nested.strategyVersion = "v2";
        nested.symbol = "BTCUSDT";
        nested.symbols = "BTCUSDT";
        nested.text = "15m";
        nested.beginDate = "2025-07-17";
        nested.endDate = "2026-07-17";

        Map<String, Object> workflowPayload = new LinkedHashMap<String, Object>();
        workflowPayload.put("backtestParam", nested);
        workflowPayload.put("workflowBatchId", "workflow_live_recheck_test");
        workflowPayload.put("symbol", "BTCUSDT");
        workflowPayload.put("text", "15m");
        workflowPayload.put("scene", "trend");
        task.payload = JsonUtils.Serializer(workflowPayload);

        StrategyCandidateRow candidate = new StrategyCandidateRow();
        candidate.strategyName = nested.strategyName;
        candidate.strategyVersion = nested.strategyVersion;
        candidate.runtimeType = "JAR";
        candidate.scene = "trend";
        candidate.payload = "{}";

        Method method = StrategyBacktestPullJob.class.getDeclaredMethod(
                "buildParam", StrategyBacktestTaskRow.class, StrategyCandidateRow.class);
        method.setAccessible(true);
        BacktestParam resolved = (BacktestParam) method.invoke(job, task, candidate);

        Assert.assertEquals("2025-07-17", resolved.beginDate);
        Assert.assertEquals("2026-07-17", resolved.endDate);
        Assert.assertEquals("BTCUSDT", resolved.symbol);
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(Math.max(0, count));
        while (builder.length() < count) {
            builder.append(value);
        }
        return builder.substring(0, count);
    }
}
