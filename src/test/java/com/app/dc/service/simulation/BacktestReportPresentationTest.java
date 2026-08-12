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
        Assert.assertTrue(html.contains("\u6263\u8d39\u540e\u9a8c\u8bc1\u6536\u76ca"));
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
        Assert.assertTrue(html.contains("BTCUSDT"));
    }

    private String invoke(BacktestReportService service, String name, Map<String, Object> value) throws Exception {
        Method method = BacktestReportService.class.getDeclaredMethod(name, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(service, value);
    }
}
