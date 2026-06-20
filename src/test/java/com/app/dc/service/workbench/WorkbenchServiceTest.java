package com.app.dc.service.workbench;

import com.app.dc.service.simulation.BacktestSupportService;
import com.app.dc.service.simulation.runtime.StrategyBacktestSummary;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskRow;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import com.app.dc.service.simulation.runtime.StrategyLiveRegistryPublishRow;
import com.app.dc.service.simulation.runtime.StrategyReleaseEventRecord;
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
import java.lang.reflect.Field;

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
        record.put("eventType", "发布实盘记录");
        record.put("scene", "trend");
        record.put("rawEventType", "PROMOTE");
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
        Assert.assertEquals("发布实盘记录", items.get(0).get("eventType"));
        Assert.assertEquals("trend", items.get(0).get("scene"));
        Assert.assertEquals(Boolean.TRUE, items.get(0).get("active"));
    }

    @Test
    public void loadPublishRecordsShouldDescribeEvolutionAndOfflineEvents() {
        FakeWorkbenchService service = new FakeWorkbenchService();

        StrategyReleaseEventRecord evolution = new StrategyReleaseEventRecord();
        evolution.id = "ev-1";
        evolution.eventTime = "2026-06-08 00:10:35";
        evolution.strategyName = "wb15_trend_t104";
        evolution.toVersion = "v2";
        evolution.eventType = "EVOLUTION_TRIGGERED";
        evolution.source = "review_evolution";

        StrategyReleaseEventRecord offline = new StrategyReleaseEventRecord();
        offline.id = "ev-2";
        offline.eventTime = "2026-06-08 00:11:35";
        offline.strategyName = "wb15_trend_t104";
        offline.toVersion = "v1";
        offline.eventType = "OFFLINE";
        offline.source = "manual";

        service.publishEventRows.add(evolution);
        service.publishEventRows.add(offline);

        List<Map<String, Object>> items = service.loadPublishRecords("2026-06-08", "2026-06-08", 20, 0);
        Assert.assertEquals(2, items.size());
        Assert.assertEquals("日末复盘记录", items.get(0).get("eventType"));
        Assert.assertEquals("EVOLUTION_TRIGGERED", items.get(0).get("rawEventType"));
        Assert.assertEquals("下线实盘记录", items.get(1).get("eventType"));
        Assert.assertEquals("OFFLINE", items.get(1).get("rawEventType"));
    }

    @Test
    public void queryBacktestListShouldSeparateEvolutionTriggeredFromPublished() {
        FakeWorkbenchService service = new FakeWorkbenchService();

        StrategyBacktestTaskRow row = new StrategyBacktestTaskRow();
        row.id = "bt-ev-1";
        row.strategyName = "wb15_trend_t104";
        row.strategyVersion = "v2";
        row.status = "SUCCESS";
        row.createTime = "2026-06-08 00:10:35";
        row.updateTime = "2026-06-08 00:12:10";
        row.payload = "{\"backtestParam\":{\"symbol\":\"BTCUSDT\",\"text\":\"15m\",\"beginDate\":\"2026-06-01\",\"endDate\":\"2026-06-07\"}}";
        service.rows.add(row);

        StrategyReleaseEventRecord event = new StrategyReleaseEventRecord();
        event.eventType = "EVOLUTION_TRIGGERED";
        event.eventTime = "2026-06-08 00:10:35";
        event.reason = "active version offlined after review evolution triggered";
        event.source = "review_evolution";
        service.releaseEvents.put("wb15_trend_t104@v2", event);

        Map<String, Object> data = service.queryBacktestList(Collections.singletonMap("date", "2026-06-08"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        Assert.assertEquals(1, items.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> publish = (Map<String, Object>) items.get(0).get("publish");
        Assert.assertEquals("", publish.get("releaseEventType"));
        Assert.assertEquals("EVOLUTION_TRIGGERED", publish.get("evolutionEventType"));
        Assert.assertEquals(Boolean.TRUE, publish.get("evolutionTriggered"));
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

    @Test
    public void queryBacktestListShouldShowRetryingForSuspendedInsufficientKline() {
        FakeWorkbenchService service = new FakeWorkbenchService();

        StrategyBacktestTaskRow row = new StrategyBacktestTaskRow();
        row.id = "bt-retry";
        row.strategyName = "ema_pullback_v1";
        row.strategyVersion = "v1";
        row.status = "SUSPENDED";
        row.suspendReason = "INSUFFICIENT_KLINE";
        row.nextRetryTime = "2026-05-14 00:03:00";
        row.createTime = "2026-05-14 00:00:00";
        row.updateTime = "2026-05-14 00:01:00";
        row.payload = "{\"backtestParam\":{\"symbol\":\"TRXUSDT\",\"text\":\"15m\"},"
                + "\"recoveryPlan\":{\"reason\":\"INSUFFICIENT_KLINE\",\"missingBars\":0}}";
        service.rows.add(row);

        Map<String, Object> data = service.queryBacktestList(Collections.singletonMap("date", "2026-05-14"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        Assert.assertEquals(1, items.size());
        Assert.assertEquals("等待重试", items.get(0).get("status"));
        Assert.assertEquals("SUSPENDED", items.get(0).get("statusCode"));
        Assert.assertEquals(0, ((Number) summary.get("running")).intValue());
        Assert.assertEquals(1, ((Number) summary.get("suspended")).intValue());
    }


    @Test
    public void queryBacktestListShouldExposeElapsedMsFromTaskResult() {
        FakeWorkbenchService service = new FakeWorkbenchService();

        StrategyBacktestTaskRow row = new StrategyBacktestTaskRow();
        row.id = "bt-elapsed";
        row.strategyName = "trend_grid";
        row.strategyVersion = "v1";
        row.status = "SUCCESS";
        row.createTime = "2026-05-16 10:00:00";
        row.updateTime = "2026-05-16 10:32:00";
        row.payload = "{\"backtestParam\":{\"symbol\":\"SOLUSDT\",\"text\":\"15m\"},\"taskResult\":{\"elapsedMs\":5400000,\"resultCount\":8}}";
        service.rows.add(row);

        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("reportPath", "/tmp/bt-elapsed.html");
        report.put("runTime", "2026-05-16 10:32:00");
        report.put("resultCount", 8);
        report.put("exists", Boolean.TRUE);
        service.reports.put("bt-elapsed", report);

        Map<String, Object> data = service.queryBacktestList(Collections.singletonMap("date", "2026-05-16"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        Assert.assertEquals(1, items.size());
        Assert.assertEquals(5400000L, ((Number) items.get(0).get("elapsedMs")).longValue());
    }

    @Test
    public void extractCountValueShouldSupportTotalAlias() {
        FakeWorkbenchService service = new FakeWorkbenchService();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("total", 5);
        Assert.assertEquals(5, service.extractCountValue(Collections.singletonList(row)));
    }

    @Test
    public void extractCountValueShouldSupportClickHouseCountKey() {
        FakeWorkbenchService service = new FakeWorkbenchService();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("count()", 7);
        Assert.assertEquals(7, service.extractCountValue(Collections.singletonList(row)));
    }

    private static class FakeWorkbenchService extends WorkbenchService {
        final List<StrategyBacktestTaskRow> rows = new ArrayList<StrategyBacktestTaskRow>();
        final Map<String, StrategyBacktestSummary> summaries = new LinkedHashMap<String, StrategyBacktestSummary>();
        final Map<String, Map<String, Object>> reports = new LinkedHashMap<String, Map<String, Object>>();
        final List<Map<String, Object>> publishRecords = new ArrayList<Map<String, Object>>();
        final Map<String, StrategyCandidateRow> candidates = new LinkedHashMap<String, StrategyCandidateRow>();
        final Map<String, StrategyReleaseEventRecord> releaseEvents = new LinkedHashMap<String, StrategyReleaseEventRecord>();
        final Map<String, List<StrategyLiveRegistryPublishRow>> liveRegistryRows = new LinkedHashMap<String, List<StrategyLiveRegistryPublishRow>>();
        final List<String> insertedTasks = new ArrayList<String>();
        final List<StrategyReleaseEventRecord> publishEventRows = new ArrayList<StrategyReleaseEventRecord>();

        FakeWorkbenchService() {
            injectBacktestSupportService();
        }

        private void injectBacktestSupportService() {
            try {
                Field field = WorkbenchService.class.getDeclaredField("backtestSupportService");
                field.setAccessible(true);
                field.set(this, new BacktestSupportService());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected List<StrategyBacktestTaskRow> loadBacktestTasksByRange(String dateFrom, String dateTo, String strategyName, String strategyVersion, String status, Integer limit, Integer offset) {
            return rows;
        }

        @Override
        protected int countBacktestTasksByRange(String dateFrom, String dateTo, String strategyName, String strategyVersion, String status) {
            return rows.size();
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
        protected StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion, String symbolScope) {
            return summaries.get(strategyName + "@" + strategyVersion);
        }

        @Override
        protected List<StrategyLiveRegistryPublishRow> loadExactActiveRows(String strategyName, String strategyVersion) {
            List<StrategyLiveRegistryPublishRow> rows = liveRegistryRows.get(strategyName + "@" + strategyVersion);
            return rows == null ? Collections.<StrategyLiveRegistryPublishRow>emptyList() : rows;
        }

        @Override
        protected StrategyReleaseEventRecord loadLatestReleaseEvent(String strategyName, String strategyVersion) {
            return releaseEvents.get(strategyName + "@" + strategyVersion);
        }

        @Override
        protected List<Map<String, Object>> loadPublishRecords(String dateFrom, String dateTo, int limit, int offset) {
            if (!publishRecords.isEmpty()) {
                return publishRecords;
            }
            if (publishEventRows.isEmpty()) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (StrategyReleaseEventRecord row : publishEventRows) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", row.id);
                item.put("eventTime", row.eventTime);
                item.put("strategyName", row.strategyName);
                item.put("fromVersion", row.fromVersion == null ? "" : row.fromVersion);
                item.put("toVersion", row.toVersion);
                item.put("runtimeType", row.runtimeType);
                item.put("eventType", describeReleaseEventTypeForTest(row.eventType, row.source));
                item.put("rawEventType", row.eventType == null ? "" : row.eventType);
                item.put("reason", row.reason);
                item.put("source", row.source);
                item.put("payload", row.payload == null ? "" : row.payload);
                item.put("summary", Collections.emptyMap());
                item.put("symbol", "");
                item.put("text", "");
                item.put("active", Boolean.FALSE);
                item.put("effectiveTime", "");
                items.add(item);
            }
            return items;
        }

        @Override
        protected int countPublishRecords(String dateFrom, String dateTo) {
            return !publishRecords.isEmpty() ? publishRecords.size() : publishEventRows.size();
        }

        @Override
        protected void insertBacktestTask(String id, String candidateId, String generationTaskId, String strategyName,
                                          String strategyVersion, String baselineVersion, String runtimeType, String taskType,
                                          int fitWindowDays, int validateWindowDays, int forwardWindowDays, int priority,
                                          String status, String now, String payloadJson) {
            insertedTasks.add(payloadJson);
        }

        private String describeReleaseEventTypeForTest(String eventType, String source) {
            String normalized = eventType == null ? "" : eventType.trim().toUpperCase();
            if ("EVOLUTION_TRIGGERED".equals(normalized)
                    || "REVIEW_EVOLUTION".equalsIgnoreCase(source == null ? "" : source.trim())) {
                return "日末复盘记录";
            }
            if ("PROMOTE".equals(normalized)
                    || normalized.endsWith("_PROMOTE")
                    || "REPLACE".equals(normalized)
                    || normalized.endsWith("_REPLACE")) {
                return "发布实盘记录";
            }
            if ("OFFLINE".equals(normalized)
                    || normalized.endsWith("_OFFLINE")
                    || "SEED_OFFLINE".equals(normalized)
                    || "OFFLINED".equals(normalized)
                    || "RETIRE".equals(normalized)
                    || normalized.endsWith("_RETIRE")) {
                return "下线实盘记录";
            }
            return eventType == null ? "" : eventType;
        }
    }
}
