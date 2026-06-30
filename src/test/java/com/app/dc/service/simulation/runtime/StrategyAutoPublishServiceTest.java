package com.app.dc.service.simulation.runtime;

import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

public class StrategyAutoPublishServiceTest {

    @Test
    public void parseTaskPayloadShouldReadEnvelopeBacktestParam() throws Exception {
        StrategyAutoPublishService service = new StrategyAutoPublishService();
        StrategyBacktestTaskRow task = new StrategyBacktestTaskRow();
        task.id = "bt_1";
        task.payload = "{\"backtestParam\":{\"strategyName\":\"wb15_breakout_adx_vol_v1\",\"strategyVersion\":\"v1\","
                + "\"symbols\":\"BTCUSDT,SOLUSDT\",\"text\":\"15m\"}}";

        Method method = StrategyAutoPublishService.class.getDeclaredMethod("parseTaskPayload",
                StrategyBacktestTaskRow.class);
        method.setAccessible(true);
        BacktestParam param = (BacktestParam) method.invoke(service, task);

        Assert.assertNotNull(param);
        Assert.assertEquals("BTCUSDT,SOLUSDT", param.symbols);
        Assert.assertEquals("15m", param.text);
    }

    @Test
    public void buildRegistryRowShouldUseBacktestedSymbolScopeInsteadOfWildcard() throws Exception {
        StrategyAutoPublishService service = new StrategyAutoPublishService();
        StrategyBacktestTaskRow task = new StrategyBacktestTaskRow();
        task.id = "bt_2";
        task.payload = "{\"backtestParam\":{\"strategyName\":\"wb15_pullback_ema_vwap_v1\",\"strategyVersion\":\"v2\","
                + "\"symbols\":\"BTCUSDT|ETHUSDT|SOLUSDT\",\"text\":\"15m\"}}";

        StrategyCandidateRow candidate = new StrategyCandidateRow();
        candidate.strategyName = "wb15_pullback_ema_vwap_v1";
        candidate.strategyVersion = "v2";
        candidate.category = "trend_pullback";
        candidate.scene = "trend";
        candidate.runtimeType = "CLASSPATH";
        candidate.artifactUri = "classpath://builtin";
        candidate.entryClass = "com.app.dc.strategy.PullbackV1";
        candidate.description = "pullback candidate";
        candidate.parametersJson = "{}";
        candidate.payload = "{}";

        StrategyBacktestSummary summary = new StrategyBacktestSummary();
        summary.bestParamSetJson = "{\"adxThreshold\":20}";

        Method method = StrategyAutoPublishService.class.getDeclaredMethod(
                "buildRegistryRow",
                StrategyBacktestTaskRow.class,
                StrategyCandidateRow.class,
                StrategyBacktestSummary.class,
                String.class,
                String.class);
        method.setAccessible(true);
        StrategyLiveRegistryPublishRow row = (StrategyLiveRegistryPublishRow) method.invoke(
                service, task, candidate, summary, "", "2026-06-01 13:00:00");

        Assert.assertEquals("BTCUSDT,ETHUSDT,SOLUSDT", row.symbolScope);
        Assert.assertEquals("15m", row.textScope);
    }

    @Test
    public void maybePublishShouldReplaceLosingActiveVersionWhenCandidatePassesThresholds() throws Exception {
        StrategyAutoPublishService service = new StrategyAutoPublishService();
        StubAutoPublishDao dao = new StubAutoPublishDao();
        dao.active = active("live_acc3", "v12", "BTCUSDT");
        dao.baseline = baseline(0.85d, 120d);
        dao.todayStats = tradeStats(5, -4.6d);
        wirePublishConfig(service, dao);

        StrategyAutoPublishDecision decision = service.maybePublish(task("bt_100"), candidate("v13"), response("BTCUSDT"));

        Assert.assertTrue(decision.published);
        Assert.assertEquals("REPLACE", decision.action);
        Assert.assertTrue(decision.reason.contains("replace active losing version after profitable walk-forward review"));
        Assert.assertEquals(1, dao.insertedRegistryRows.size());
        Assert.assertEquals(1, dao.insertedReleaseEvents.size());
    }

    @Test
    public void maybePublishShouldStillBlockWhenActiveLossOverrideHasNoTrades() throws Exception {
        StrategyAutoPublishService service = new StrategyAutoPublishService();
        StubAutoPublishDao dao = new StubAutoPublishDao();
        dao.active = active("live_acc3", "v12", "BTCUSDT");
        dao.baseline = baseline(0.85d, 120d);
        dao.todayStats = tradeStats(0, -4.6d);
        wirePublishConfig(service, dao);

        StrategyAutoPublishDecision decision = service.maybePublish(task("bt_101"), candidate("v13"), response("BTCUSDT"));

        Assert.assertFalse(decision.published);
        Assert.assertEquals("forward_score not better than active baseline", decision.reason);
        Assert.assertEquals(0, dao.insertedRegistryRows.size());
    }

    private static void wirePublishConfig(StrategyAutoPublishService service, StubAutoPublishDao dao) throws Exception {
        setField(service, "enabled", true);
        setField(service, "publishSource", "test_publish");
        setField(service, "minValidateTrades", 5);
        setField(service, "maxValidateDrawdownPct", 0.30d);
        setField(service, "minValidateProfitFactor", 1.05d);
        setField(service, "lossAwareBaselineReplaceEnabled", true);
        setField(service, "lossAwareBaselineReplaceTodayPnlThreshold", 3.0d);
        setField(service, "strategyAutoPublishDao", dao);
    }

    private static StrategyBacktestTaskRow task(String taskId) {
        StrategyBacktestTaskRow task = new StrategyBacktestTaskRow();
        task.id = taskId;
        task.payload = "{\"backtestParam\":{\"strategyName\":\"live_acc3\",\"strategyVersion\":\"v13\",\"symbols\":\"BTCUSDT\",\"text\":\"15m\"}}";
        return task;
    }

    private static StrategyCandidateRow candidate(String version) {
        StrategyCandidateRow candidate = new StrategyCandidateRow();
        candidate.strategyName = "live_acc3";
        candidate.strategyVersion = version;
        candidate.category = "generated";
        candidate.scene = "range";
        candidate.runtimeType = "CLASSPATH";
        candidate.artifactUri = "classpath://builtin";
        candidate.entryClass = "com.app.dc.strategy.LiveAcc3";
        candidate.description = "candidate";
        candidate.parametersJson = "{}";
        candidate.payload = "{}";
        return candidate;
    }

    private static BacktestModels.BacktestResponse response(String symbol) {
        BacktestModels.BacktestResult result = new BacktestModels.BacktestResult();
        result.symbol = symbol;
        result.text = "15m";
        result.windowMode = "WALK_FORWARD";
        result.sliceCount = 6;
        result.fitPnl = BigDecimal.valueOf(100);
        result.validatePnl = BigDecimal.valueOf(160);
        result.forwardPnl = BigDecimal.valueOf(80);
        result.totalPnl = BigDecimal.valueOf(340);
        result.forwardScore = BigDecimal.valueOf(0.70d);
        result.validatePrimaryScore = BigDecimal.valueOf(110d);
        result.feeAdjustedValidatePnl = BigDecimal.valueOf(150d);
        result.sliceParamDriftScore = BigDecimal.ZERO;
        result.oosPass = 1;
        result.overfitPass = 1;
        result.bestParamSetJson = "{\"risk\":1}";
        result.fragileBest = 0;
        result.tradeCount = 9;
        result.maxDrawdownPct = BigDecimal.valueOf(0.12d);
        result.profitFactor = BigDecimal.valueOf(1.6d);

        BacktestModels.BacktestResponse response = new BacktestModels.BacktestResponse();
        response.strategyName = "live_acc3";
        response.strategyVersion = "v13";
        response.runtimeType = "CLASSPATH";
        response.scene = "range";
        response.symbol = symbol;
        response.symbols = Collections.singletonList(symbol);
        response.text = "15m";
        response.windowMode = "WALK_FORWARD";
        response.sliceCount = 6;
        response.trialCount = 1;
        response.bestRank = 1;
        response.bestParamSetJson = "{\"risk\":1}";
        response.minForwardContribution = BigDecimal.valueOf(0.10d);
        response.validatePrimaryScore = BigDecimal.valueOf(110d);
        response.forwardAuxScore = BigDecimal.ZERO;
        response.feeAdjustedValidatePnl = BigDecimal.valueOf(150d);
        response.sliceParamDriftScore = BigDecimal.ZERO;
        response.oosPass = 1;
        response.overfitPass = 1;
        response.results = Collections.singletonList(result);
        return response;
    }

    private static StrategyLiveRegistryPublishRow active(String strategyName, String strategyVersion, String symbolScope) {
        StrategyLiveRegistryPublishRow row = new StrategyLiveRegistryPublishRow();
        row.strategyName = strategyName;
        row.strategyVersion = strategyVersion;
        row.symbolScope = symbolScope;
        row.status = "ACTIVE";
        return row;
    }

    private static StrategyBacktestSummary baseline(double forwardScore, double validateScore) {
        StrategyBacktestSummary row = new StrategyBacktestSummary();
        row.forwardScore = forwardScore;
        row.validatePrimaryScore = validateScore;
        return row;
    }

    private static StrategyLiveTradeStatsRow tradeStats(int tradeCount, double todayPnl) {
        StrategyLiveTradeStatsRow row = new StrategyLiveTradeStatsRow();
        row.todayTradeCount = tradeCount;
        row.todayPnl = todayPnl;
        return row;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = StrategyAutoPublishService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class StubAutoPublishDao implements StrategyAutoPublishDao {
        private StrategyLiveRegistryPublishRow active;
        private StrategyBacktestSummary baseline;
        private StrategyLiveTradeStatsRow todayStats;
        private final java.util.ArrayList<StrategyLiveRegistryPublishRow> insertedRegistryRows = new java.util.ArrayList<StrategyLiveRegistryPublishRow>();
        private final java.util.ArrayList<StrategyReleaseEventRecord> insertedReleaseEvents = new java.util.ArrayList<StrategyReleaseEventRecord>();

        @Override
        public StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName) {
            return active;
        }

        @Override
        public StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName, String symbolScope) {
            return active;
        }

        @Override
        public StrategyLiveRegistryPublishRow loadLatestLiveBaseline(String strategyName) {
            return active;
        }

        @Override
        public StrategyLiveRegistryPublishRow loadLatestLiveBaseline(String strategyName, String symbolScope) {
            return active;
        }

        @Override
        public StrategyLiveRegistryPublishRow loadExactActive(String strategyName, String strategyVersion) {
            return active;
        }

        @Override
        public StrategyLiveRegistryPublishRow loadExactActive(String strategyName, String strategyVersion, String symbolScope) {
            return active;
        }

        @Override
        public List<StrategyLiveRegistryPublishRow> listExactActiveRows(String strategyName, String strategyVersion) {
            return Collections.emptyList();
        }

        @Override
        public StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion) {
            return baseline;
        }

        @Override
        public StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion, String symbolScope) {
            return baseline;
        }

        @Override
        public StrategyLiveTradeStatsRow loadTodayTradeStats(String strategyName, String strategyVersion, String symbolScope) {
            return todayStats;
        }

        @Override
        public StrategyReleaseEventRecord loadLatestReleaseEvent(String strategyName, String strategyVersion) {
            return null;
        }

        @Override
        public void retireActive(String strategyName, String exceptVersion, String retireTime) {
        }

        @Override
        public void retireActive(String strategyName, String symbolScope, String exceptVersion, String retireTime) {
        }

        @Override
        public void insertRegistry(StrategyLiveRegistryPublishRow row) {
            insertedRegistryRows.add(row);
        }

        @Override
        public void insertReleaseEvent(StrategyReleaseEventRecord row) {
            insertedReleaseEvents.add(row);
        }
    }
}
