package com.app.dc.service.simulation;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class BacktestReportPresentationTest {

    @Test
    public void firstScreenUsesPlainBusinessLanguage() throws Exception {
        BacktestReportService service = new BacktestReportService();
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("headline", "\u5df2\u901a\u8fc7\u56de\u6d4b\u9a8c\u8bc1");
        summary.put("actionLabel", "\u7b49\u5f85\u7cfb\u7edf\u81ea\u52a8\u53d1\u5e03");
        summary.put("statusClass", "pass");
        summary.put("keyFeeAdjustedValidatePnl", new BigDecimal("12.34"));
        summary.put("keyFeeAdjustedForwardPnl", new BigDecimal("3.21"));
        summary.put("keyDrawdown", new BigDecimal("0.081"));
        summary.put("keyTradeCount", 26);
        summary.put("reasons", Arrays.asList("\u6263\u8d39\u540e\u4ecd\u7136\u76c8\u5229"));
        summary.put("nextSteps", Arrays.asList("\u7531\u7cfb\u7edf\u81ea\u52a8\u53d1\u5e03"));

        String html = invoke(service, "renderSimpleUserSummarySection", summary);

        Assert.assertTrue(html.contains("\u4e00\u5206\u949f\u770b\u61c2\u56de\u6d4b"));
        Assert.assertTrue(html.contains("\u6263\u8d39\u540e\u573a\u666f\u9a8c\u8bc1\u6536\u76ca"));
        Assert.assertTrue(html.contains("8.10%"));
        Assert.assertFalse(html.contains("Validate \u4e3b\u5206"));
    }

    @Test
    public void sceneQualificationIsClearlyShownInReport() throws Exception {
        BacktestReportService service = new BacktestReportService();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("symbol", "BTCUSDT");
        row.put("strategyScene", "trend");
        row.put("message", "\u573a\u666f\u56de\u6d4b\u901a\u8fc7");
        row.put("tradeCount", 7);
        row.put("totalPnl", new BigDecimal("5.2"));
        row.put("maxDrawdownPct", new BigDecimal("0.03"));
        row.put("blockedSignalCount", 4);
        Map<String, Object> scene = new LinkedHashMap<String, Object>();
        scene.put("status", "QUALIFIED");
        scene.put("rows", Arrays.asList(row));
        scene.put("sceneRecordCount", 20);
        scene.put("tradeCount", 7);
        scene.put("totalPnl", new BigDecimal("5.2"));
        scene.put("blockedSignalCount", 4);
        scene.put("forcedExitCount", 1);

        String html = invoke(service, "renderSceneShadowSection", scene);

        Assert.assertTrue(html.contains("\u573a\u666f\u5185\u8868\u73b0"));
        Assert.assertTrue(html.contains("\u573a\u666f\u8d44\u683c\u901a\u8fc7"));
        Assert.assertTrue(html.contains("\u6b63\u5f0f\u51c6\u5165"));
        Assert.assertTrue(html.contains("BTCUSDT"));
    }

    @Test
    public void newQualificationFailuresUsePlainChinese() throws Exception {
        BacktestReportService service = new BacktestReportService();

        Assert.assertEquals("\u56de\u6d4b\u672a\u4f7f\u7528\u573a\u666f\u6761\u4ef6\u5316\u6eda\u52a8\u9a8c\u8bc1",
                translate(service, "window_mode is not SCENE_CONDITIONED_WALK_FORWARD"));
        Assert.assertEquals("\u5b8c\u6574\u5468\u671f\u6267\u884c\u53d1\u73b0\u7f3a\u5c11\u52a8\u6001\u6b62\u635f\u6b62\u76c8\u7684\u4fe1\u53f7",
                translate(service, "full-period execution found signals without dynamic stop/take"));
        Assert.assertEquals("\u6b62\u76c8\u7a7a\u95f4\u592a\u5c0f\uff0c\u65e0\u6cd5\u8986\u76d6\u624b\u7eed\u8d39\u548c\u6ed1\u70b9",
                translate(service, "signal_economics_target_too_close"));
    }

    private String invoke(BacktestReportService service, String name, Map<String, Object> value) throws Exception {
        Method method = BacktestReportService.class.getDeclaredMethod(name, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(service, value);
    }

    private String translate(BacktestReportService service, String value) throws Exception {
        Method method = BacktestReportService.class.getDeclaredMethod("translateReason", String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, value);
    }
}
