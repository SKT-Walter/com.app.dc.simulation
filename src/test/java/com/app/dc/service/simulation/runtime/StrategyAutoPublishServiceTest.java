package com.app.dc.service.simulation.runtime;

import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
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

    @Test
    public void maybePublishShouldSplitAggregateActiveRowWhenSingleSymbolReplaces() throws Exception {
        StrategyAutoPublishService service = new StrategyAutoPublishService();
        StubAutoPublishDao dao = new StubAutoPublishDao();
        dao.activeRows.add(active("live_acc3", "v12", "BTCUSDT,ETHUSDT,SOLUSDT"));
        dao.baseline = baseline(0.20d, 100d);
        wirePublishConfig(service, dao);

        StrategyAutoPublishDecision decision = service.maybePublish(task("bt_102"), candidate("v13"), response("BTCUSDT"));

        Assert.assertTrue(decision.published);
        Assert.assertEquals("REPLACE", decision.action);
        Assert.assertEquals(2, dao.insertedRegistryRows.size());
        Assert.assertEquals("BTCUSDT", dao.insertedRegistryRows.get(0).symbolScope);
        Assert.assertEquals("v13", dao.insertedRegistryRows.get(0).strategyVersion);
        Assert.assertEquals("ETHUSDT,SOLUSDT", dao.insertedRegistryRows.get(1).symbolScope);
        Assert.assertEquals("v12", dao.insertedRegistryRows.get(1).strategyVersion);
        Assert.assertEquals(1, dao.insertedReleaseEvents.size());
        Assert.assertEquals(2, dao.retiredScopes.size());
        Assert.assertTrue(dao.retiredScopes.contains("BTCUSDT"));
        Assert.assertTrue(dao.retiredScopes.contains("BTCUSDT,ETHUSDT,SOLUSDT"));
    }

    @Test
    public void maybePublishShouldRejectZeroFeeAdjustedForwardPnl() throws Exception {
        StrategyAutoPublishService service = new StrategyAutoPublishService();
        StubAutoPublishDao dao = new StubAutoPublishDao();
        wirePublishConfig(service, dao);
        BacktestModels.BacktestResponse response = response("BTCUSDT");
        response.results.get(0).forwardPnl = BigDecimal.ZERO;
        response.results.get(0).feeAdjustedForwardPnl = BigDecimal.ZERO;

        StrategyAutoPublishDecision decision = service.maybePublish(task("bt_103"), candidate("v13"), response);

        Assert.assertFalse(decision.published);
        Assert.assertEquals("fee adjusted forward pnl <= 0", decision.reason);
    }

    @Test
    public void maybePublishShouldNotCompareNewExecutionModelAgainstLegacyInflatedBaseline() throws Exception {
        StrategyAutoPublishService service = new StrategyAutoPublishService();
        StubAutoPublishDao dao = new StubAutoPublishDao();
        dao.active = active("live_acc3", "v12", "BTCUSDT");
        dao.baseline = baseline(99.0d, 999999d);
        dao.baseline.executionModelVersion = "";
        wirePublishConfig(service, dao);

        StrategyAutoPublishDecision decision = service.maybePublish(task("bt_104"), candidate("v13"), response("BTCUSDT"));

        Assert.assertTrue(decision.published);
        Assert.assertEquals("REPLACE", decision.action);
        Assert.assertEquals("replace legacy backtest baseline with realistic execution model result", decision.reason);
    }

    private static void wirePublishConfig(StrategyAutoPublishService service, StubAutoPublishDao dao) throws Exception {
        setField(service, "enabled", true);
        setField(service, "publishSource", "test_publish");
        setField(service, "minValidateTrades", 20);
        setField(service, "maxValidateDrawdownPct", 0.15d);
        setField(service, "minValidateProfitFactor", 1.20d);
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
        result.tradeCount = 25;
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
        row.executionModelVersion = BacktestModels.EXECUTION_MODEL_VERSION;
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
        private final java.util.ArrayList<StrategyLiveRegistryPublishRow> activeRows = new java.util.ArrayList<StrategyLiveRegistryPublishRow>();
        private final java.util.ArrayList<StrategyLiveRegistryPublishRow> insertedRegistryRows = new java.util.ArrayList<StrategyLiveRegistryPublishRow>();
        private final java.util.ArrayList<StrategyReleaseEventRecord> insertedReleaseEvents = new java.util.ArrayList<StrategyReleaseEventRecord>();
        private final java.util.ArrayList<String> retiredScopes = new java.util.ArrayList<String>();

        @Override
        public StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName) {
            if (!activeRows.isEmpty()) {
                return activeRows.get(0);
            }
            return active;
        }

        @Override
        public StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName, String symbolScope) {
            return active;
        }

        @Override
        public List<StrategyLiveRegistryPublishRow> listCurrentActiveRows(String strategyName) {
            if (!activeRows.isEmpty()) {
                return new ArrayList<StrategyLiveRegistryPublishRow>(activeRows);
            }
            if (active == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(active);
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
            retiredScopes.add(symbolScope);
            if (activeRows.isEmpty()) {
                return;
            }
            java.util.Iterator<StrategyLiveRegistryPublishRow> iterator = activeRows.iterator();
            while (iterator.hasNext()) {
                StrategyLiveRegistryPublishRow row = iterator.next();
                if (row == null) {
                    continue;
                }
                if (!strategyName.equalsIgnoreCase(row.strategyName)) {
                    continue;
                }
                if (symbolScope != null && !symbolScope.equalsIgnoreCase(row.symbolScope)) {
                    continue;
                }
                if (exceptVersion != null && exceptVersion.equalsIgnoreCase(row.strategyVersion)) {
                    continue;
                }
                iterator.remove();
            }
        }

        @Override
        public void insertRegistry(StrategyLiveRegistryPublishRow row) {
            insertedRegistryRows.add(row);
            activeRows.add(row);
        }

        @Override
        public void insertReleaseEvent(StrategyReleaseEventRecord row) {
            insertedReleaseEvents.add(row);
        }
    }
}
