package com.app.dc.service.simulation.runtime;

import com.app.dc.po.backtest.BacktestParam;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

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
                String.class);
        method.setAccessible(true);
        StrategyLiveRegistryPublishRow row = (StrategyLiveRegistryPublishRow) method.invoke(
                service, task, candidate, summary, "2026-06-01 13:00:00");

        Assert.assertEquals("BTCUSDT,ETHUSDT,SOLUSDT", row.symbolScope);
        Assert.assertEquals("15m", row.textScope);
    }
}
