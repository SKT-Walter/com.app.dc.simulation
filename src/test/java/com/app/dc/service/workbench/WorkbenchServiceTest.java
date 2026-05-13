package com.app.dc.service.workbench;

import com.app.dc.service.simulation.runtime.StrategyBacktestSummary;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskRow;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorkbenchServiceTest {

    @Test
    public void queryBacktestListShouldMergeSummaryAndReport() {
        FakeWorkbenchService service = new FakeWorkbenchService();

        StrategyBacktestTaskRow row = new StrategyBacktestTaskRow();
        row.id = "bt-1";
        row.strategyName = "docx_t6";
        row.strategyVersion = "v5";
        row.status = "SUCCESS";
        row.createTime = "2026-05-13 09:00:00";
        row.updateTime = "2026-05-13 09:10:00";
        row.payload = "{\"backtestParam\":{\"symbol\":\"SOLUSDT\",\"symbols\":\"SOLUSDT\",\"text\":\"15m\",\"beginDate\":\"2026-04-01\",\"endDate\":\"2026-05-11\"}}";
        service.rows.add(row);

        StrategyBacktestSummary summary = new StrategyBacktestSummary();
        summary.strategyName = "docx_t6";
        summary.strategyVersion = "v5";
        summary.totalPnl = 12.5D;
        summary.forwardScore = 1.2D;
        service.summaries.put("docx_t6@v5", summary);

        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("reportPath", "/tmp/docx_t6_v5.html");
        report.put("reportJsonPath", "/tmp/docx_t6_v5.json");
        report.put("reportMarkdownPath", "/tmp/docx_t6_v5.md");
        report.put("runTime", "2026-05-13 09:20:00");
        report.put("resultCount", 4);
        report.put("exists", Boolean.TRUE);
        service.reports.put("bt-1", report);

        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("date", "2026-05-13");
        request.put("symbol", "SOLUSDT");
        Map<String, Object> data = service.queryBacktestList(request);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        Assert.assertEquals(1, items.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadSummary = (Map<String, Object>) items.get(0).get("payloadSummary");
        Assert.assertEquals("SOLUSDT", payloadSummary.get("symbol"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summaryView = (Map<String, Object>) items.get(0).get("summary");
        Assert.assertEquals(12.5D, (Double) summaryView.get("totalPnl"), 0.00001D);
        @SuppressWarnings("unchecked")
        Map<String, Object> reportView = (Map<String, Object>) items.get(0).get("report");
        Assert.assertEquals("/tmp/docx_t6_v5.html", reportView.get("reportPath"));
    }

    @Test
    public void queryPublishRecordListShouldReturnEnrichedItems() {
        FakeWorkbenchService service = new FakeWorkbenchService();
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("strategyName", "docx_c6");
        record.put("toVersion", "v2");
        record.put("eventType", "PROMOTE");
        record.put("active", Boolean.TRUE);
        record.put("effectiveTime", "2026-05-13 10:00:00");
        service.publishRecords.add(record);

        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("date", "2026-05-13");
        Map<String, Object> data = service.queryPublishRecordList(request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        Assert.assertEquals(1, items.size());
        Assert.assertEquals("docx_c6", items.get(0).get("strategyName"));
        Assert.assertEquals(Boolean.TRUE, items.get(0).get("active"));
    }

    @Test
    public void queryBacktestReportShouldExposeHtmlContent() throws Exception {
        FakeWorkbenchService service = new FakeWorkbenchService();
        Path report = Files.createTempFile("bt-report", ".html");
        Files.write(report, "<html><body>backtest-report</body></html>".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> reportMeta = new LinkedHashMap<String, Object>();
        reportMeta.put("reportPath", report.toString());
        service.reports.put("bt-2", reportMeta);

        Map<String, Object> data = service.queryBacktestReport(Collections.singletonMap("backtestTaskId", "bt-2"));
        Assert.assertEquals(Boolean.TRUE, data.get("htmlExists"));
        Assert.assertTrue(String.valueOf(data.get("htmlContent")).contains("backtest-report"));
    }

    @Test
    public void createBacktestShouldInsertPendingTaskPayload() {
        FakeWorkbenchService service = new FakeWorkbenchService();
        StrategyCandidateRow candidate = new StrategyCandidateRow();
        candidate.id = "cand-1";
        candidate.strategyName = "docx_t6";
        candidate.strategyVersion = "v5";
        candidate.parentVersion = "v4";
        candidate.runtimeType = "JAR";
        candidate.scene = "trend";
        candidate.payload = "{\"entry\":\"ok\"}";
        service.candidates.put("docx_t6@v5", candidate);

        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("strategyName", "docx_t6");
        request.put("strategyVersion", "v5");
        request.put("symbol", "SOLUSDT");
        request.put("text", "15m");
        request.put("beginDate", "2026-04-01");
        request.put("endDate", "2026-05-11");

        Map<String, Object> data = service.createBacktest(request);
        Assert.assertEquals(Boolean.TRUE, data.get("created"));
        Assert.assertEquals("PENDING", data.get("status"));
        Assert.assertEquals(1, service.insertedTasks.size());
        Assert.assertTrue(service.insertedTasks.get(0).contains("\"backtestParam\""));
        Assert.assertTrue(service.insertedTasks.get(0).contains("\"strategyName\":\"docx_t6\""));
    }

    private static class FakeWorkbenchService extends WorkbenchService {
        final List<StrategyBacktestTaskRow> rows = new ArrayList<StrategyBacktestTaskRow>();
        final Map<String, StrategyBacktestSummary> summaries = new LinkedHashMap<String, StrategyBacktestSummary>();
        final Map<String, Map<String, Object>> reports = new LinkedHashMap<String, Map<String, Object>>();
        final List<Map<String, Object>> publishRecords = new ArrayList<Map<String, Object>>();
        final Map<String, StrategyCandidateRow> candidates = new LinkedHashMap<String, StrategyCandidateRow>();
        final List<String> insertedTasks = new ArrayList<String>();

        @Override
        protected List<StrategyBacktestTaskRow> loadBacktestTasksByDate(String date, String strategyName, String strategyVersion, String status, int limit) {
            return rows;
        }

        @Override
        protected StrategyCandidateRow loadCandidate(String strategyName, String strategyVersion) {
            return candidates.get(strategyName + "@" + strategyVersion);
        }

        @Override
        protected StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion) {
            return summaries.get(strategyName + "@" + strategyVersion);
        }

        @Override
        protected Map<String, Object> loadLatestReportMeta(String taskId, String strategyName, String strategyVersion) {
            Map<String, Object> report = reports.get(taskId);
            return report == null ? super.loadLatestReportMeta(taskId, strategyName, strategyVersion) : report;
        }

        @Override
        protected List<Map<String, Object>> loadPublishRecords(String date, int limit) {
            return publishRecords;
        }

        @Override
        protected void insertBacktestTask(String id, String candidateId, String generationTaskId, String strategyName,
                                          String strategyVersion, String baselineVersion, String runtimeType, String taskType,
                                          int fitWindowDays, int validateWindowDays, int forwardWindowDays, int priority,
                                          String status, String now, String payloadJson) {
            insertedTasks.add(payloadJson);
        }
    }
}
