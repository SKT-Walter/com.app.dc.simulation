package com.app.dc.service.workbench;

import com.app.common.db.ClickHouseDBUtils;
import com.app.common.utils.IdUtil;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestSupportService;
import com.app.dc.service.simulation.runtime.StrategyAutoPublishDao;
import com.app.dc.service.simulation.runtime.StrategyBacktestSummary;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskDao;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskRow;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskPayloadEnvelope;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import com.app.dc.service.simulation.runtime.StrategyLiveRegistryPublishRow;
import com.app.dc.service.simulation.runtime.StrategyReleaseEventRecord;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class WorkbenchService {

    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private StrategyBacktestTaskDao strategyBacktestTaskDao;

    @Autowired
    private StrategyAutoPublishDao strategyAutoPublishDao;

    @Autowired
    private BacktestSupportService backtestSupportService;

    @Autowired(required = false)
    private ClickHouseDBUtils clickHouseDBUtils;

    @Value("${strategy.backtest.task.table:dc.strategy_backtest_task}")
    private String strategyBacktestTaskTable;

    @Value("${strategy.release.event.table:dc.strategy_release_event}")
    private String strategyReleaseEventTable;

    @Value("${binanceBacktestResultTable:backtest_result}")
    private String backtestResultTable;

    public Map<String, Object> queryBacktestList(Map<String, Object> request) {
        String today = LocalDate.now().toString();
        String rawDate = text(request, "date", today);
        String rawDateFrom = text(request, "dateFrom", "");
        String rawDateTo = text(request, "dateTo", "");
        boolean history = StringUtils.isNotBlank(rawDateFrom) || StringUtils.isNotBlank(rawDateTo);
        String date = text(request, "date", today);
        String dateFrom = history
                ? text(request, "dateFrom", LocalDate.now().minusDays(30).toString())
                : rawDate;
        String dateTo = history
                ? text(request, "dateTo", LocalDate.now().minusDays(1).toString())
                : rawDate;
        if (LocalDate.parse(dateFrom).isAfter(LocalDate.parse(dateTo))) {
            String swap = dateFrom;
            dateFrom = dateTo;
            dateTo = swap;
        }
        String status = text(request, "status", "");
        String strategyName = text(request, "strategyName", "");
        String strategyVersion = text(request, "strategyVersion", "");
        String symbol = text(request, "symbol", "");
        int page = boundedInt(request, "page", 1, 1, 100000);
        int pageSize = boundedInt(request, request != null && request.containsKey("pageSize") ? "pageSize" : "limit", history ? 20 : 50, 1, 200);
        int offset = Math.max(0, (page - 1) * pageSize);

        List<StrategyBacktestTaskRow> rows = loadBacktestTasksByRange(dateFrom, dateTo, strategyName, strategyVersion, status, pageSize, offset);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (StrategyBacktestTaskRow row : rows) {
            Map<String, Object> item = toTaskView(row);
            if (!matchesSymbol(item, symbol)) {
                continue;
            }
            StrategyCandidateRow candidate = loadCandidate(row.strategyName, row.strategyVersion);
            StrategyBacktestSummary summary = loadLatestSummary(row.strategyName, row.strategyVersion);
            Map<String, Object> report = loadLatestReportMeta(row.id, row.strategyName, row.strategyVersion);
            Map<String, Object> publish = buildPublishState(row.strategyName, row.strategyVersion);
            item.put("strategyDescription", candidate == null ? "" : blankTo(candidate.description, ""));
            item.put("summary", summaryView(summary));
            item.put("report", report);
            item.put("publish", publish);
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("date", date);
        data.put("status", status);
        data.put("strategyName", strategyName);
        data.put("strategyVersion", strategyVersion);
        data.put("symbol", symbol);
        data.put("dateFrom", dateFrom);
        data.put("dateTo", dateTo);
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("history", history);
        data.put("serverTime", CLICKHOUSE_TIME.format(LocalDateTime.now()));
        data.put("items", items);
        data.put("total", countBacktestTasksByRange(dateFrom, dateTo, strategyName, strategyVersion, status));
        data.put("summary", buildTaskSummary(items));
        return data;
    }

    public Map<String, Object> queryBacktestDetail(Map<String, Object> request) {
        String taskId = text(request, "backtestTaskId", text(request, "taskId", ""));
        String strategyName = text(request, "strategyName", "");
        String strategyVersion = text(request, "strategyVersion", "");
        List<StrategyBacktestTaskRow> rows = strategyBacktestTaskDao.loadLatest(taskId, "", "", strategyName, strategyVersion, "", 1);
        StrategyBacktestTaskRow row = rows == null || rows.isEmpty() ? null : rows.get(0);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("backtestTaskId", taskId);
        data.put("exists", row != null);
        if (row == null) {
            data.put("task", null);
            data.put("candidate", null);
            data.put("summary", null);
            data.put("report", null);
            return data;
        }

        StrategyCandidateRow candidate = loadCandidate(row.strategyName, row.strategyVersion);
        StrategyBacktestSummary summary = loadLatestSummary(row.strategyName, row.strategyVersion);
        Map<String, Object> report = loadLatestReportMeta(row.id, row.strategyName, row.strategyVersion);
        Map<String, Object> publish = buildPublishState(row.strategyName, row.strategyVersion);

        data.put("task", toTaskView(row));
        data.put("candidate", candidateView(candidate));
        data.put("summary", summaryView(summary));
        data.put("report", report);
        data.put("publish", publish);
        return data;
    }

    public Map<String, Object> queryBacktestReport(Map<String, Object> request) {
        String taskId = text(request, "backtestTaskId", text(request, "taskId", ""));
        String strategyName = text(request, "strategyName", "");
        String strategyVersion = text(request, "strategyVersion", "");
        Map<String, Object> report = loadLatestReportMeta(taskId, strategyName, strategyVersion);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("backtestTaskId", taskId);
        data.put("strategyName", strategyName);
        data.put("strategyVersion", strategyVersion);
        data.putAll(report);
        String htmlPath = blankTo(report.get("reportPath") == null ? "" : String.valueOf(report.get("reportPath")), "");
        data.put("htmlExists", fileExists(htmlPath));
        data.put("htmlContent", readUtf8File(htmlPath));
        return data;
    }

    public Map<String, Object> queryPublishRecordList(Map<String, Object> request) {
        String today = LocalDate.now().toString();
        String rawDate = text(request, "date", today);
        String rawDateFrom = text(request, "dateFrom", "");
        String rawDateTo = text(request, "dateTo", "");
        boolean history = StringUtils.isNotBlank(rawDateFrom) || StringUtils.isNotBlank(rawDateTo);
        String date = text(request, "date", today);
        String dateFrom = history
                ? text(request, "dateFrom", LocalDate.now().minusDays(30).toString())
                : rawDate;
        String dateTo = history
                ? text(request, "dateTo", LocalDate.now().minusDays(1).toString())
                : rawDate;
        if (LocalDate.parse(dateFrom).isAfter(LocalDate.parse(dateTo))) {
            String swap = dateFrom;
            dateFrom = dateTo;
            dateTo = swap;
        }
        int page = boundedInt(request, "page", 1, 1, 100000);
        int pageSize = boundedInt(request, request != null && request.containsKey("pageSize") ? "pageSize" : "limit", history ? 20 : 50, 1, 200);
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> items = loadPublishRecords(dateFrom, dateTo, pageSize, offset);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("date", date);
        data.put("dateFrom", dateFrom);
        data.put("dateTo", dateTo);
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("history", history);
        data.put("items", items);
        data.put("total", countPublishRecords(dateFrom, dateTo));
        return data;
    }

    public Map<String, Object> createBacktest(Map<String, Object> request) {
        String strategyName = requiredText(request, "strategyName");
        String strategyVersion = requiredText(request, "strategyVersion");
        StrategyCandidateRow candidate = loadCandidate(strategyName, strategyVersion);
        if (candidate == null) {
            throw new IllegalArgumentException("candidate not found: " + strategyName + "@" + strategyVersion);
        }

        BacktestParam param = new BacktestParam();
        param.strategyName = candidate.strategyName;
        param.strategyVersion = candidate.strategyVersion;
        param.baselineVersion = blankTo(candidate.parentVersion, text(request, "baselineVersion", ""));
        param.runtimeType = blankTo(candidate.runtimeType, "CLASSPATH");
        param.scene = candidate.scene;
        param.strategyPayload = candidate.payload;
        param.symbol = text(request, "symbol", "");
        param.symbols = StringUtils.isNotBlank(param.symbol) ? param.symbol : text(request, "symbols", "");
        param.text = requiredText(request, "text");
        param.beginDate = requiredText(request, "beginDate");
        param.endDate = requiredText(request, "endDate");
        param.ignoreSentimentGuard = true;
        param.allowMissingStageAnalysis = true;
        param.text = backtestSupportService.normalizeText(param.text);
        backtestSupportService.validateBacktestRange(param.text, param.beginDate, param.endDate);

        StrategyBacktestTaskPayloadEnvelope envelope = new StrategyBacktestTaskPayloadEnvelope();
        envelope.backtestParam = param;
        String payloadJson = JsonUtils.Serializer(envelope);

        String taskId = "bt_" + IdUtil.getId();
        String now = CLICKHOUSE_TIME.format(LocalDateTime.now());
        insertBacktestTask(
                taskId,
                blankTo(candidate.id, ""),
                "",
                candidate.strategyName,
                candidate.strategyVersion,
                blankTo(param.baselineVersion, ""),
                blankTo(candidate.runtimeType, "CLASSPATH"),
                "FULL",
                boundedInt(request, "fitWindowDays", 120, 1, 3650),
                boundedInt(request, "validateWindowDays", 30, 1, 3650),
                boundedInt(request, "forwardWindowDays", 14, 1, 3650),
                boundedInt(request, "priority", 5, 1, 1000),
                "PENDING",
                now,
                payloadJson
        );

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("backtestTaskId", taskId);
        data.put("strategyName", candidate.strategyName);
        data.put("strategyVersion", candidate.strategyVersion);
        data.put("symbol", param.symbol);
        data.put("symbols", param.symbols);
        data.put("text", param.text);
        data.put("beginDate", param.beginDate);
        data.put("endDate", param.endDate);
        data.put("status", "PENDING");
        data.put("created", Boolean.TRUE);
        return data;
    }

    protected List<StrategyBacktestTaskRow> loadBacktestTasksByRange(String dateFrom,
                                                                     String dateTo,
                                                                     String strategyName,
                                                                     String strategyVersion,
                                                                     String status,
                                                                     int limit,
                                                                     int offset) {
        if (!ready()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder();
        sql.append("select ")
                .append("id, candidateId, generationTaskId, strategyName, strategyVersion, baselineVersion, runtimeType, taskType,")
                .append("fitWindowDays, validateWindowDays, forwardWindowDays, priority, status,")
                .append("suspendReason, ifNull(toString(nextRetryTimeRaw), '') as nextRetryTime,")
                .append("attemptCount, createTime, updateTime, payload, failureReason ")
                .append("from (").append(latestTaskSql()).append(") latest ")
                .append("where toDate(parseDateTimeBestEffortOrNull(latest.createTime)) >= toDate('").append(escape(dateFrom)).append("')")
                .append(" and toDate(parseDateTimeBestEffortOrNull(latest.createTime)) <= toDate('").append(escape(dateTo)).append("')");
        if (StringUtils.isNotBlank(strategyName)) {
            sql.append(" and lower(latest.strategyName)=lower('").append(escape(strategyName.trim())).append("')");
        }
        if (StringUtils.isNotBlank(strategyVersion)) {
            sql.append(" and lower(latest.strategyVersion)=lower('").append(escape(strategyVersion.trim())).append("')");
        }
        if (StringUtils.isNotBlank(status)) {
            sql.append(" and latest.status='").append(escape(status.trim())).append("'");
        }
        sql.append(" order by parseDateTimeBestEffortOrNull(latest.updateTime) desc limit ")
                .append(Math.max(1, Math.min(limit, 200)))
                .append(" offset ")
                .append(Math.max(0, offset));
        try {
            List<StrategyBacktestTaskRow> rows = ClickHouseDBUtils.queryList(sql.toString(), new Object[]{},
                    StrategyBacktestTaskRow.class);
            return rows == null ? Collections.<StrategyBacktestTaskRow>emptyList() : rows;
        } catch (Exception e) {
            log.error("loadBacktestTasksByRange error, from:{}, to:{}, strategy:{}@{}, status:{}, offset:{}",
                    dateFrom, dateTo, strategyName, strategyVersion, status, offset, e);
            return Collections.emptyList();
        }
    }

    protected int countBacktestTasksByRange(String dateFrom,
                                            String dateTo,
                                            String strategyName,
                                            String strategyVersion,
                                            String status) {
        if (!ready()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder();
        sql.append("select count() as total from (")
                .append("select id from (").append(latestTaskSql()).append(") latest ")
                .append("where toDate(parseDateTimeBestEffortOrNull(latest.createTime)) >= toDate('").append(escape(dateFrom)).append("')")
                .append(" and toDate(parseDateTimeBestEffortOrNull(latest.createTime)) <= toDate('").append(escape(dateTo)).append("')");
        if (StringUtils.isNotBlank(strategyName)) {
            sql.append(" and lower(latest.strategyName)=lower('").append(escape(strategyName.trim())).append("')");
        }
        if (StringUtils.isNotBlank(strategyVersion)) {
            sql.append(" and lower(latest.strategyVersion)=lower('").append(escape(strategyVersion.trim())).append("')");
        }
        if (StringUtils.isNotBlank(status)) {
            sql.append(" and latest.status='").append(escape(status.trim())).append("'");
        }
        sql.append(") counted");
        try {
            @SuppressWarnings("rawtypes")
            List rows = ClickHouseDBUtils.queryList(sql.toString(), new Object[]{}, LinkedHashMap.class);
            if (rows == null || rows.isEmpty()) {
                return 0;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) rows.get(0);
            Object value = first.get("total");
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            log.error("countBacktestTasksByRange error, from:{}, to:{}, strategy:{}@{}, status:{}",
                    dateFrom, dateTo, strategyName, strategyVersion, status, e);
            return 0;
        }
    }

    protected StrategyCandidateRow loadCandidate(String strategyName, String strategyVersion) {
        return strategyBacktestTaskDao.loadCandidate(strategyName, strategyVersion);
    }

    protected StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion) {
        return strategyAutoPublishDao.loadLatestSummary(strategyName, strategyVersion);
    }

    protected StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion, String symbolScope) {
        return strategyAutoPublishDao.loadLatestSummary(strategyName, strategyVersion, symbolScope);
    }

    protected Map<String, Object> loadLatestReportMeta(String taskId, String strategyName, String strategyVersion) {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("reportPath", "");
        report.put("reportJsonPath", "");
        report.put("reportMarkdownPath", "");
        report.put("runTime", "");
        report.put("resultCount", 0);
        report.put("exists", Boolean.FALSE);
        if (!ready()) {
            return report;
        }
        StringBuilder sql = new StringBuilder();
        sql.append("select ")
                .append("sid as sid,")
                .append("argMax(strategy_name, run_time) as strategyName,")
                .append("argMax(strategy_version, run_time) as strategyVersion,")
                .append("argMax(report_path, run_time) as reportPath,")
                .append("toString(max(run_time)) as runTime,")
                .append("count() as resultCount,")
                .append("sum(total_pnl) as totalPnl,")
                .append("sum(validate_pnl) as validatePnl,")
                .append("sum(forward_pnl) as forwardPnl,")
                .append("sum(validate_primary_score) as validatePrimaryScore,")
                .append("avg(forward_aux_score) as forwardAuxScore,")
                .append("sum(fee_adjusted_validate_pnl) as feeAdjustedValidatePnl,")
                .append("avg(slice_param_drift_score) as sliceParamDriftScore,")
                .append("sum(trade_count) as validateTradeCount,")
                .append("max(max_drawdown_pct) as validateMaxDrawdownPct,")
                .append("avg(profit_factor) as validateProfitFactor,")
                .append("min(oos_pass) as oosPass ")
                .append("from ").append(safe(backtestResultTable, "backtest_result")).append(" where 1=1");
        if (StringUtils.isNotBlank(taskId)) {
            sql.append(" and sid='").append(escape(taskId.trim())).append("'");
        }
        if (StringUtils.isNotBlank(strategyName)) {
            sql.append(" and lower(strategy_name)=lower('").append(escape(strategyName.trim())).append("')");
        }
        if (StringUtils.isNotBlank(strategyVersion)) {
            sql.append(" and lower(strategy_version)=lower('").append(escape(strategyVersion.trim())).append("')");
        }
        sql.append(" group by sid order by max(run_time) desc limit 1");
        try {
            List<ReportMetaRow> rows = ClickHouseDBUtils.queryList(sql.toString(), new Object[]{}, ReportMetaRow.class);
            if (rows == null || rows.isEmpty()) {
                return report;
            }
            ReportMetaRow row = rows.get(0);
            String path = blankTo(row.reportPath, "");
            report.put("reportPath", path);
            report.put("reportJsonPath", swapExt(path, ".json"));
            report.put("reportMarkdownPath", swapExt(path, ".md"));
            report.put("runTime", blankTo(row.runTime, ""));
            report.put("resultCount", row.resultCount == null ? 0 : row.resultCount.intValue());
            report.put("totalPnl", row.totalPnl == null ? 0D : row.totalPnl.doubleValue());
            report.put("validatePnl", row.validatePnl == null ? 0D : row.validatePnl.doubleValue());
            report.put("forwardPnl", row.forwardPnl == null ? 0D : row.forwardPnl.doubleValue());
            report.put("validatePrimaryScore", row.validatePrimaryScore == null ? 0D : row.validatePrimaryScore.doubleValue());
            report.put("forwardAuxScore", row.forwardAuxScore == null ? 0D : row.forwardAuxScore.doubleValue());
            report.put("feeAdjustedValidatePnl", row.feeAdjustedValidatePnl == null ? 0D : row.feeAdjustedValidatePnl.doubleValue());
            report.put("sliceParamDriftScore", row.sliceParamDriftScore == null ? 0D : row.sliceParamDriftScore.doubleValue());
            report.put("validateTradeCount", row.validateTradeCount == null ? 0 : row.validateTradeCount.intValue());
            report.put("validateMaxDrawdownPct", row.validateMaxDrawdownPct == null ? 0D : row.validateMaxDrawdownPct.doubleValue());
            report.put("validateProfitFactor", row.validateProfitFactor == null ? 0D : row.validateProfitFactor.doubleValue());
            report.put("oosPass", row.oosPass == null ? 0 : row.oosPass.intValue());
            report.put("exists", StringUtils.isNotBlank(path));
            return report;
        } catch (Exception e) {
            log.error("loadLatestReportMeta error, task:{}, strategy:{}@{}", taskId, strategyName, strategyVersion, e);
            return report;
        }
    }

    protected List<Map<String, Object>> loadPublishRecords(String dateFrom, String dateTo, int limit, int offset) {
        if (!ready()) {
            return Collections.emptyList();
        }
        String sql = "select "
                + "id as id,"
                + "toString(event_time) as eventTime,"
                + "strategy_name as strategyName,"
                + "from_version as fromVersion,"
                + "to_version as toVersion,"
                + "runtime_type as runtimeType,"
                + "event_type as eventType,"
                + "reason as reason,"
                + "source as source,"
                + "payload as payload "
                + "from " + safe(strategyReleaseEventTable, "dc.strategy_release_event")
                + " where toDate(event_time) >= toDate('" + escape(dateFrom) + "')"
                + " and toDate(event_time) <= toDate('" + escape(dateTo) + "')"
                + " order by event_time desc limit " + Math.max(1, Math.min(limit, 200))
                + " offset " + Math.max(0, offset);
        try {
            List<StrategyReleaseEventRecord> rows = ClickHouseDBUtils.queryList(sql, new Object[]{},
                    StrategyReleaseEventRecord.class);
            if (rows == null || rows.isEmpty()) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (StrategyReleaseEventRecord row : rows) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", row.id);
                item.put("eventTime", row.eventTime);
                item.put("strategyName", row.strategyName);
                item.put("fromVersion", blankTo(row.fromVersion, ""));
                item.put("toVersion", row.toVersion);
                item.put("runtimeType", row.runtimeType);
                item.put("eventType", row.eventType);
                item.put("reason", row.reason);
                item.put("source", row.source);
                item.put("payload", blankTo(row.payload, ""));
                Map<String, Object> releasePayload = parseJsonObject(row.payload);
                String releaseSymbolScope = firstNonBlank(releasePayload.get("symbolScope"));
                StrategyCandidateRow candidate = loadCandidate(row.strategyName, row.toVersion);
                item.put("strategyDescription", candidate == null ? "" : blankTo(candidate.description, ""));
                StrategyBacktestSummary summary = StringUtils.isNotBlank(releaseSymbolScope)
                        ? loadLatestSummary(row.strategyName, row.toVersion, releaseSymbolScope)
                        : loadLatestSummary(row.strategyName, row.toVersion);
                item.put("summary", summaryView(summary));
                Map<String, Object> publishScope = candidate == null
                        ? Collections.<String, Object>emptyMap()
                        : buildPayloadSummaryFromRawMap(parseJsonObject(candidate.payload));
                item.put("symbol", firstNonBlank(
                        releaseSymbolScope,
                        publishScope.get("symbol"),
                        publishScope.get("symbols")));
                StrategyLiveRegistryPublishRow active = StringUtils.isNotBlank(releaseSymbolScope)
                        ? strategyAutoPublishDao.loadExactActive(row.strategyName, row.toVersion, releaseSymbolScope)
                        : strategyAutoPublishDao.loadExactActive(row.strategyName, row.toVersion);
                item.put("text", firstNonBlank(
                        active == null ? "" : active.textScope,
                        publishScope.get("text")));
                item.put("active", active != null);
                item.put("effectiveTime", active == null ? "" : blankTo(active.effectiveTime, ""));
                items.add(item);
                }
                return items;
        } catch (Exception e) {
            log.error("loadPublishRecords error, from:{}, to:{}, offset:{}", dateFrom, dateTo, offset, e);
            return Collections.emptyList();
        }
    }

    protected int countPublishRecords(String dateFrom, String dateTo) {
        if (!ready()) {
            return 0;
        }
        String sql = "select count() as total "
                + "from " + safe(strategyReleaseEventTable, "dc.strategy_release_event")
                + " where toDate(event_time) >= toDate('" + escape(dateFrom) + "')"
                + " and toDate(event_time) <= toDate('" + escape(dateTo) + "')";
        try {
            @SuppressWarnings("rawtypes")
            List rows = ClickHouseDBUtils.queryList(sql, new Object[]{}, LinkedHashMap.class);
            if (rows == null || rows.isEmpty()) {
                return 0;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) rows.get(0);
            Object value = first.get("total");
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            log.error("countPublishRecords error, from:{}, to:{}", dateFrom, dateTo, e);
            return 0;
        }
    }

    protected void insertBacktestTask(String id,
                                      String candidateId,
                                      String generationTaskId,
                                      String strategyName,
                                      String strategyVersion,
                                      String baselineVersion,
                                      String runtimeType,
                                      String taskType,
                                      int fitWindowDays,
                                      int validateWindowDays,
                                      int forwardWindowDays,
                                      int priority,
                                      String status,
                                      String now,
                                      String payloadJson) {
        if (!ready()) {
            throw new IllegalStateException("clickhouse not ready for insertBacktestTask");
        }
        String sql = "INSERT INTO " + safe(strategyBacktestTaskTable, "dc.strategy_backtest_task")
                + " (id, candidate_id, generation_task_id, strategy_name, strategy_version, baseline_version, runtime_type, task_type, "
                + "fit_window_days, validate_window_days, forward_window_days, priority, status, create_time, update_time, payload, failure_reason) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,toDateTime(?),toDateTime(?),?,?)";
        try {
            ClickHouseDBUtils.update(sql, new Object[]{
                    blankTo(id, ""),
                    blankTo(candidateId, ""),
                    blankTo(generationTaskId, ""),
                    blankTo(strategyName, ""),
                    blankTo(strategyVersion, ""),
                    blankTo(baselineVersion, ""),
                    blankTo(runtimeType, ""),
                    blankTo(taskType, "FULL"),
                    fitWindowDays,
                    validateWindowDays,
                    forwardWindowDays,
                    priority,
                    blankTo(status, "PENDING"),
                    now,
                    now,
                    blankTo(payloadJson, "{}"),
                    ""
            });
        } catch (Exception e) {
            log.error("insertBacktestTask error, strategy:{}@{}", strategyName, strategyVersion, e);
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> toTaskView(StrategyBacktestTaskRow row) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if (row == null) {
            return view;
        }
        StrategyBacktestTaskPayloadEnvelope envelope = parsePayloadEnvelope(row.payload);
        Map<String, Object> payloadSummary = parsePayloadSummary(envelope, row.payload);
        String statusCode = blankTo(row.status, "");
        view.put("backtestTaskId", row.id);
        view.put("taskId", row.id);
        view.put("candidateId", blankTo(row.candidateId, ""));
        view.put("generationTaskId", blankTo(row.generationTaskId, ""));
        view.put("strategyName", blankTo(row.strategyName, ""));
        view.put("strategyVersion", blankTo(row.strategyVersion, ""));
        view.put("baselineVersion", blankTo(row.baselineVersion, ""));
        view.put("runtimeType", blankTo(row.runtimeType, ""));
        view.put("taskType", blankTo(row.taskType, ""));
        view.put("statusCode", statusCode);
        view.put("status", displayStatus(statusCode, row.suspendReason, row.nextRetryTime, envelope));
        view.put("statusDetail", displayStatusDetail(statusCode, row.suspendReason, row.failureReason, row.nextRetryTime, envelope));
        view.put("priority", row.priority == null ? 0 : row.priority.intValue());
        view.put("fitWindowDays", row.fitWindowDays == null ? 0 : row.fitWindowDays.intValue());
        view.put("validateWindowDays", row.validateWindowDays == null ? 0 : row.validateWindowDays.intValue());
        view.put("forwardWindowDays", row.forwardWindowDays == null ? 0 : row.forwardWindowDays.intValue());
        view.put("attemptCount", row.attemptCount == null ? 0 : row.attemptCount.intValue());
        view.put("suspendReason", blankTo(row.suspendReason, ""));
        view.put("failureReason", blankTo(row.failureReason, ""));
        view.put("nextRetryTime", blankTo(row.nextRetryTime, ""));
        view.put("createTime", blankTo(row.createTime, ""));
        view.put("updateTime", blankTo(row.updateTime, ""));
        view.put("elapsedMs", resolveElapsedMs(row.payload));
        view.put("payloadSummary", payloadSummary);
        view.put("rawPayload", blankTo(row.payload, ""));
        return view;
    }

    private StrategyBacktestTaskPayloadEnvelope parsePayloadEnvelope(String payload) {
        if (StringUtils.isBlank(payload)) {
            return null;
        }
        try {
            return JsonUtils.Deserialize(payload, StrategyBacktestTaskPayloadEnvelope.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Map<String, Object> parsePayloadSummary(StrategyBacktestTaskPayloadEnvelope envelope, String payload) {
        if (envelope != null && envelope.backtestParam != null) {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("symbol", blankTo(envelope.backtestParam.symbol, ""));
            summary.put("symbols", blankTo(envelope.backtestParam.symbols, ""));
            summary.put("text", blankTo(envelope.backtestParam.text, ""));
            summary.put("beginDate", blankTo(envelope.backtestParam.beginDate, ""));
            summary.put("endDate", blankTo(envelope.backtestParam.endDate, ""));
            if (envelope.suspendDetail != null && !envelope.suspendDetail.isEmpty()) {
                summary.put("suspendDetail", envelope.suspendDetail);
            }
            if (envelope.recoveryPlan != null && !envelope.recoveryPlan.isEmpty()) {
                summary.put("recoveryPlan", envelope.recoveryPlan);
            }
            if (envelope.runningProgress != null && !envelope.runningProgress.isEmpty()) {
                summary.put("runningProgress", envelope.runningProgress);
            }
            return summary;
        }
        if (StringUtils.isBlank(payload)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JsonUtils.Deserialize(payload, Map.class);
            return buildPayloadSummaryFromRawMap(map);
        } catch (Exception ignore) {
            return new LinkedHashMap<String, Object>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPayloadSummaryFromRawMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> nestedPayload = mapValue(map.get("payload"));
        Map<String, Object> nestedBacktestParam = mapValue(map.get("backtestParam"));
        Map<String, Object> nestedRequestPayload = mapValue(map.get("requestPayload"));
        Map<String, Object> nestedPayloadBacktestParam = mapValue(nestedPayload.get("backtestParam"));
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("symbol", firstNonBlank(
                map.get("symbol"),
                nestedBacktestParam.get("symbol"),
                nestedPayload.get("symbol"),
                nestedPayloadBacktestParam.get("symbol"),
                nestedRequestPayload.get("symbol")));
        summary.put("symbols", firstNonBlank(
                map.get("symbols"),
                nestedBacktestParam.get("symbols"),
                nestedPayload.get("symbols"),
                nestedPayloadBacktestParam.get("symbols"),
                nestedRequestPayload.get("symbols")));
        summary.put("text", firstNonBlank(
                map.get("text"),
                nestedBacktestParam.get("text"),
                nestedPayload.get("text"),
                nestedPayloadBacktestParam.get("text"),
                nestedRequestPayload.get("text")));
        summary.put("beginDate", firstNonBlank(
                map.get("beginDate"),
                nestedBacktestParam.get("beginDate"),
                nestedPayload.get("beginDate"),
                nestedPayloadBacktestParam.get("beginDate"),
                nestedRequestPayload.get("beginDate")));
        summary.put("endDate", firstNonBlank(
                map.get("endDate"),
                nestedBacktestParam.get("endDate"),
                nestedPayload.get("endDate"),
                nestedPayloadBacktestParam.get("endDate"),
                nestedRequestPayload.get("endDate")));
        if (summary.get("symbol") == null) {
            summary.put("symbol", "");
        }
        if (summary.get("symbols") == null) {
            summary.put("symbols", "");
        }
        if (summary.get("text") == null) {
            summary.put("text", "");
        }
        if (summary.get("beginDate") == null) {
            summary.put("beginDate", "");
        }
        if (summary.get("endDate") == null) {
            summary.put("endDate", "");
        }
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String text) {
        if (StringUtils.isBlank(text)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object value = JsonUtils.Deserialize(text, Map.class);
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
        } catch (Exception ignore) {
        }
        return new LinkedHashMap<String, Object>();
    }

    private Long resolveElapsedMs(String payload) {
        return resolveElapsedMsFromPayload(payload);
    }

    @SuppressWarnings("unchecked")
    private Long resolveElapsedMsFromPayload(String payload) {
        if (StringUtils.isBlank(payload)) {
            return null;
        }
        try {
            Map<String, Object> map = JsonUtils.Deserialize(payload, Map.class);
            Long direct = longValue(map.get("elapsedMs"));
            if (direct != null) {
                return direct;
            }
            Map<String, Object> taskResult = mapValue(map.get("taskResult"));
            return longValue(taskResult.get("elapsedMs"));
        } catch (Exception ignore) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = blankTo(value == null ? null : String.valueOf(value), "");
            if (StringUtils.isNotBlank(text)) {
                return text;
            }
        }
        return "";
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            String text = String.valueOf(value).trim();
            if (StringUtils.isBlank(text)) {
                return null;
            }
            if (text.contains(".")) {
                return Double.valueOf(text).longValue();
            }
            return Long.valueOf(text);
        } catch (Exception ignore) {
            return null;
        }
    }

    private int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            String text = String.valueOf(value).trim();
            if (StringUtils.isBlank(text)) {
                return fallback;
            }
            if (text.contains(".")) {
                return Double.valueOf(text).intValue();
            }
            return Integer.parseInt(text);
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private String displayStatus(String statusCode,
                                 String suspendReason,
                                 String nextRetryTime,
                                 StrategyBacktestTaskPayloadEnvelope envelope) {
        String code = blankTo(statusCode, "").trim().toUpperCase();
        if ("SUCCESS".equals(code)) {
            return "已完成";
        }
        if ("FAILED".equals(code)) {
            return "失败";
        }
        if ("PENDING".equals(code)) {
            return "排队中";
        }
        if ("RUNNING".equals(code)) {
            if (isWaitingRetry(suspendReason, nextRetryTime, envelope)) {
                return "等待重试";
            }
            return "运行中";
        }
        if ("SUSPENDED".equals(code)) {
            if (isWaitingRetry(suspendReason, nextRetryTime, envelope)) {
                return "等待重试";
            }
            return "已挂起";
        }
        return blankTo(statusCode, "");
    }

    private Map<String, Object> buildTaskSummary(List<Map<String, Object>> items) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        int total = 0;
        int success = 0;
        int running = 0;
        int suspended = 0;
        int failed = 0;
        for (Map<String, Object> item : items) {
            total++;
            String code = blankTo(item.get("statusCode") == null ? "" : String.valueOf(item.get("statusCode")), "").toUpperCase();
            String displayStatus = blankTo(item.get("status") == null ? "" : String.valueOf(item.get("status")), "");
            if (code.contains("SUCCESS")) {
                success++;
            }
            if (isSuspendedDisplayStatus(displayStatus) || code.contains("SUSPEND")) {
                suspended++;
            } else if (code.contains("RUN")) {
                running++;
            }
            if (code.contains("FAIL")) {
                failed++;
            }
        }
        summary.put("total", total);
        summary.put("success", success);
        summary.put("running", running);
        summary.put("suspended", suspended);
        summary.put("failed", failed);
        return summary;
    }

    private boolean isSuspendedDisplayStatus(String displayStatus) {
        String text = blankTo(displayStatus, "");
        return "等待重试".equals(text)
                || "已挂起".equals(text)
                || "绛夊緟閲嶈瘯".equals(text)
                || "宸叉寕璧�".equals(text)
                || "宸叉寕璧?".equals(text);
    }
    private String displayStatusDetail(String statusCode,
                                       String suspendReason,
                                       String failureReason,
                                       String nextRetryTime,
                                       StrategyBacktestTaskPayloadEnvelope envelope) {
        String code = blankTo(statusCode, "").trim().toUpperCase();
        if ("FAILED".equals(code)) {
            return blankTo(failureReason, "回测任务执行失败");
        }
        if ("SUCCESS".equals(code)) {
            return "回测任务已经完成，可直接查看报告";
        }
        if ("PENDING".equals(code)) {
            return "任务已经提交，正在等待调度执行";
        }
        if (isInsufficientKline(suspendReason, envelope)) {
            String stage = recoveryPlanText(envelope, "autofillStage");
            int requiredBars = recoveryPlanInt(envelope, "requiredBars");
            int actualBars = recoveryPlanInt(envelope, "actualBars");
            int missingBars = recoveryPlanInt(envelope, "missingBars");
            if (missingBars <= 0) {
                return "样本窗口不足，当前数据已完整，已跳过重复补数";
            }
            String progress = buildAutofillProgress(requiredBars, actualBars, missingBars);
            if ("WAITING_THROTTLE".equalsIgnoreCase(stage)) {
                return "样本窗口不足，补数排队中；" + progress + "；等待限频窗口释放后自动继续";
            }
            if ("IMPORTING".equalsIgnoreCase(stage)) {
                return "样本窗口不足，正在补数；" + progress + "；系统正在向交易所拉取缺失 K 线";
            }
            if ("VALIDATING".equalsIgnoreCase(stage)) {
                return "样本窗口不足，补数已完成，正在校验；" + progress;
            }
            if ("PARTIAL".equalsIgnoreCase(stage)) {
                return "样本窗口不足，补数未完成；" + progress + "；系统会继续等待后续自动重试";
            }
            if ("IMPORT_FAILED".equalsIgnoreCase(stage)) {
                String autofillError = recoveryPlanText(envelope, "autofillError");
                return StringUtils.isBlank(autofillError)
                        ? "样本窗口不足，补数执行失败，等待系统下次自动重试"
                        : "样本窗口不足，补数执行失败：" + autofillError;
            }
            if ("READY_FOR_RETRY".equalsIgnoreCase(stage)) {
                return "样本窗口不足，补数已完成，任务即将自动重试";
            }
            if (StringUtils.isNotBlank(nextRetryTime)) {
                return "样本窗口不足，系统会在补数后自动重试；" + progress;
            }
            return "样本窗口不足，等待补充更多历史数据；" + progress;
        }
        if ("RUNNING".equals(code)) {
            String runningDetail = buildRunningProgressDetail(envelope);
            return StringUtils.isBlank(runningDetail) ? "\u56de\u6d4b\u4efb\u52a1\u6b63\u5728\u6267\u884c" : runningDetail;
        }
        if ("SUSPENDED".equals(code)) {
            return StringUtils.isNotBlank(suspendReason) ? suspendReason : "任务已挂起";
        }
        return "";
    }

    private boolean isWaitingRetry(String suspendReason,
                                   String nextRetryTime,
                                   StrategyBacktestTaskPayloadEnvelope envelope) {
        return isInsufficientKline(suspendReason, envelope) && StringUtils.isNotBlank(nextRetryTime);
    }

    private boolean isInsufficientKline(String suspendReason, StrategyBacktestTaskPayloadEnvelope envelope) {
        if ("INSUFFICIENT_KLINE".equalsIgnoreCase(blankTo(suspendReason, ""))) {
            return true;
        }
        String reason = recoveryPlanText(envelope, "reason");
        return "INSUFFICIENT_KLINE".equalsIgnoreCase(reason);
    }

    private int recoveryPlanInt(StrategyBacktestTaskPayloadEnvelope envelope, String key) {
        if (envelope == null || envelope.recoveryPlan == null || !envelope.recoveryPlan.containsKey(key)) {
            return 0;
        }
        Object value = envelope.recoveryPlan.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignore) {
            return 0;
        }
    }

    private String recoveryPlanText(StrategyBacktestTaskPayloadEnvelope envelope, String key) {
        if (envelope == null || envelope.recoveryPlan == null || !envelope.recoveryPlan.containsKey(key)) {
            return "";
        }
        Object value = envelope.recoveryPlan.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String buildRunningProgressDetail(StrategyBacktestTaskPayloadEnvelope envelope) {
        if (envelope == null || envelope.runningProgress == null || envelope.runningProgress.isEmpty()) {
            return "";
        }
        Map<String, Object> progress = envelope.runningProgress;
        String phase = blankTo(String.valueOf(progress.get("phase")), "");
        int coarseCandidateCount = intValue(progress.get("coarseCandidateCount"), 0);
        int fineCandidateCount = intValue(progress.get("fineCandidateCount"), 0);
        int plannedTrialCount = intValue(progress.get("plannedTrialCount"), 0);
        int completedTrialCount = intValue(progress.get("completedTrialCount"), 0);
        int currentTrialNo = intValue(progress.get("currentTrialNo"), 0);
        int trialBudget = intValue(progress.get("trialBudget"), 0);
        StringBuilder sb = new StringBuilder("\u56de\u6d4b\u6b63\u5728\u6267\u884c");
        if (StringUtils.isNotBlank(phase)) {
            sb.append("\uff0c\u9636\u6bb5\uff1a").append("FINE".equalsIgnoreCase(phase) ? "\u7ec6\u7b5b" : "\u7c97\u7b5b");
        }
        if (coarseCandidateCount > 0 || fineCandidateCount > 0) {
            sb.append("\uff0c\u53c2\u6570\u7ec4\u5408\uff1a").append(coarseCandidateCount);
            if (fineCandidateCount > 0) {
                sb.append(" + ").append(fineCandidateCount);
            }
        }
        if (plannedTrialCount > 0) {
            sb.append("\uff0c\u8fdb\u5ea6\uff1a").append(Math.max(0, completedTrialCount)).append(" / ").append(plannedTrialCount);
        }
        if (currentTrialNo > 0) {
            sb.append("\uff0c\u5f53\u524d\u8bd5\u7b97\u5e8f\u53f7\uff1a").append(currentTrialNo);
        }
        if (trialBudget > 0) {
            sb.append("\uff0c\u8bd5\u7b97\u9884\u7b97\uff1a").append(trialBudget);
        }
        return sb.toString();
    }

    private String buildAutofillProgress(int requiredBars, int actualBars, int missingBars) {
        if (requiredBars <= 0) {
            return "已补 " + Math.max(0, actualBars) + " 根";
        }
        return "已补 " + Math.max(0, actualBars) + " / 目标 " + requiredBars + " 根，还差 " + Math.max(0, missingBars) + " 根";
    }

    private Map<String, Object> candidateView(StrategyCandidateRow row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", blankTo(row.id, ""));
        view.put("strategyName", blankTo(row.strategyName, ""));
        view.put("strategyVersion", blankTo(row.strategyVersion, ""));
        view.put("parentVersion", blankTo(row.parentVersion, ""));
        view.put("category", blankTo(row.category, ""));
        view.put("scene", blankTo(row.scene, ""));
        view.put("generationType", blankTo(row.generationType, ""));
        view.put("runtimeType", blankTo(row.runtimeType, ""));
        view.put("artifactUri", blankTo(row.artifactUri, ""));
        view.put("entryClass", blankTo(row.entryClass, ""));
        view.put("description", blankTo(row.description, ""));
        view.put("parametersJson", blankTo(row.parametersJson, ""));
        view.put("payload", blankTo(row.payload, ""));
        view.put("pipelineRunId", blankTo(row.pipelineRunId(), ""));
        return view;
    }

    private Map<String, Object> summaryView(StrategyBacktestSummary summary) {
        if (summary == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("sid", blankTo(summary.sid, ""));
        view.put("strategyName", blankTo(summary.strategyName, ""));
        view.put("strategyVersion", blankTo(summary.strategyVersion, ""));
        view.put("runtimeType", blankTo(summary.runtimeType, ""));
        view.put("scene", blankTo(summary.scene, ""));
        view.put("runTime", blankTo(summary.runTime, ""));
        view.put("windowMode", blankTo(summary.windowMode, ""));
        view.put("sliceCount", summary.sliceCount == null ? 0 : summary.sliceCount.intValue());
        view.put("optimizationMode", blankTo(summary.optimizationMode, ""));
        view.put("trialCount", summary.trialCount == null ? 0 : summary.trialCount.intValue());
        view.put("bestRank", summary.bestRank == null ? 0 : summary.bestRank.intValue());
        view.put("bestParamSetJson", blankTo(summary.bestParamSetJson, "{}"));
        view.put("fitPnl", summary.fitPnl == null ? 0D : summary.fitPnl.doubleValue());
        view.put("validatePnl", summary.validatePnl == null ? 0D : summary.validatePnl.doubleValue());
        view.put("forwardPnl", summary.forwardPnl == null ? 0D : summary.forwardPnl.doubleValue());
        view.put("totalPnl", summary.totalPnl == null ? 0D : summary.totalPnl.doubleValue());
        view.put("forwardScore", summary.forwardScore == null ? 0D : summary.forwardScore.doubleValue());
        view.put("validatePrimaryScore", summary.validatePrimaryScore == null ? 0D : summary.validatePrimaryScore.doubleValue());
        view.put("forwardAuxScore", summary.forwardAuxScore == null ? 0D : summary.forwardAuxScore.doubleValue());
        view.put("feeAdjustedValidatePnl", summary.feeAdjustedValidatePnl == null ? 0D : summary.feeAdjustedValidatePnl.doubleValue());
        view.put("sliceParamDriftScore", summary.sliceParamDriftScore == null ? 0D : summary.sliceParamDriftScore.doubleValue());
        view.put("validateTradeCount", summary.validateTradeCount == null ? 0 : summary.validateTradeCount.intValue());
        view.put("validateMaxDrawdownPct", summary.validateMaxDrawdownPct == null ? 0D : summary.validateMaxDrawdownPct.doubleValue());
        view.put("validateProfitFactor", summary.validateProfitFactor == null ? 0D : summary.validateProfitFactor.doubleValue());
        view.put("fragileBest", summary.fragileBest == null ? 0 : summary.fragileBest.intValue());
        view.put("oosPass", summary.oosPass == null ? 0 : summary.oosPass.intValue());
        view.put("minForwardContribution", summary.minForwardContribution == null ? 0D : summary.minForwardContribution.doubleValue());
        view.put("overfitPass", summary.overfitPass == null ? 0 : summary.overfitPass.intValue());
        view.put("overfitReason", blankTo(summary.overfitReason, ""));
        view.put("resultCount", summary.resultCount == null ? 0 : summary.resultCount.intValue());
        return view;
    }

    private Map<String, Object> buildPublishState(String strategyName, String strategyVersion) {
        Map<String, Object> publish = new LinkedHashMap<String, Object>();
        List<StrategyLiveRegistryPublishRow> activeRows = loadExactActiveRows(strategyName, strategyVersion);
        StrategyLiveRegistryPublishRow active = activeRows == null || activeRows.isEmpty() ? null : activeRows.get(0);
        StrategyReleaseEventRecord event = loadLatestReleaseEvent(strategyName, strategyVersion);
        String latestEventType = event == null ? "" : blankTo(event.eventType, "");
        String latestEventTime = event == null ? "" : blankTo(event.eventTime, "");
        String latestEventReason = event == null ? "" : blankTo(event.reason, "");
        String latestEventSource = event == null ? "" : blankTo(event.source, "");
        boolean evolutionTriggered = isEvolutionTriggeredEvent(latestEventType);
        boolean publishedEvent = isPublishedEventType(latestEventType);
        publish.put("active", active != null);
        publish.put("activeCount", activeRows == null ? 0 : activeRows.size());
        publish.put("activeSymbols", joinActiveSymbols(activeRows));
        publish.put("effectiveTime", active == null ? "" : blankTo(active.effectiveTime, ""));
        publish.put("currentLiveVersion", active == null ? "" : blankTo(active.strategyVersion, ""));
        publish.put("currentLiveStatus", active == null ? "" : blankTo(active.status, ""));
        publish.put("latestEventType", latestEventType);
        publish.put("latestEventTime", latestEventTime);
        publish.put("latestEventReason", latestEventReason);
        publish.put("latestEventSource", latestEventSource);
        publish.put("releaseEventType", publishedEvent ? latestEventType : "");
        publish.put("releaseEventTime", publishedEvent ? latestEventTime : "");
        publish.put("releaseEventReason", publishedEvent ? latestEventReason : "");
        publish.put("releaseEventSource", publishedEvent ? latestEventSource : "");
        publish.put("evolutionTriggered", evolutionTriggered);
        publish.put("evolutionEventType", evolutionTriggered ? latestEventType : "");
        publish.put("evolutionEventTime", evolutionTriggered ? latestEventTime : "");
        publish.put("evolutionEventReason", evolutionTriggered ? latestEventReason : "");
        publish.put("evolutionEventSource", evolutionTriggered ? latestEventSource : "");
        return publish;
    }

    protected List<StrategyLiveRegistryPublishRow> loadExactActiveRows(String strategyName, String strategyVersion) {
        return strategyAutoPublishDao.listExactActiveRows(strategyName, strategyVersion);
    }

    protected StrategyReleaseEventRecord loadLatestReleaseEvent(String strategyName, String strategyVersion) {
        return strategyAutoPublishDao.loadLatestReleaseEvent(strategyName, strategyVersion);
    }

    private boolean isPublishedEventType(String eventType) {
        String normalized = blankTo(eventType, "").trim().toUpperCase();
        return "PROMOTE".equals(normalized)
                || normalized.endsWith("_PROMOTE")
                || "REPLACE".equals(normalized)
                || normalized.endsWith("_REPLACE");
    }

    private boolean isEvolutionTriggeredEvent(String eventType) {
        return "EVOLUTION_TRIGGERED".equalsIgnoreCase(blankTo(eventType, "").trim());
    }

    private String joinActiveSymbols(List<StrategyLiveRegistryPublishRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<String>();
        for (StrategyLiveRegistryPublishRow row : rows) {
            if (row == null || StringUtils.isBlank(row.symbolScope)) {
                continue;
            }
            values.add(row.symbolScope.trim());
        }
        return String.join(",", values);
    }

    private boolean matchesSymbol(Map<String, Object> item, String symbol) {
        if (StringUtils.isBlank(symbol)) {
            return true;
        }
        Object payloadSummary = item.get("payloadSummary");
        if (!(payloadSummary instanceof Map)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) payloadSummary;
        String single = blankTo(summary.get("symbol") == null ? "" : String.valueOf(summary.get("symbol")), "");
        String many = blankTo(summary.get("symbols") == null ? "" : String.valueOf(summary.get("symbols")), "");
        return symbol.equalsIgnoreCase(single)
                || containsCsv(many, symbol);
    }

    private boolean containsCsv(String csv, String target) {
        if (StringUtils.isBlank(csv) || StringUtils.isBlank(target)) {
            return false;
        }
        String[] parts = csv.split(",");
        for (String part : parts) {
            if (target.equalsIgnoreCase(part == null ? "" : part.trim())) {
                return true;
            }
        }
        return false;
    }

    private String latestTaskSql() {
        String baseSql = "select *, "
                + "tuple(update_time, multiIf(status='SUCCESS', 4, status='FAILED', 4, status='RUNNING', 3, status='SUSPENDED', 2, 1)) as versionKey "
                + "from " + safe(strategyBacktestTaskTable, "dc.strategy_backtest_task");
        return "select "
                + "id as id,"
                + "argMax(candidate_id, versionKey) as candidateId,"
                + "argMax(generation_task_id, versionKey) as generationTaskId,"
                + "argMax(strategy_name, versionKey) as strategyName,"
                + "argMax(strategy_version, versionKey) as strategyVersion,"
                + "argMax(baseline_version, versionKey) as baselineVersion,"
                + "argMax(runtime_type, versionKey) as runtimeType,"
                + "argMax(task_type, versionKey) as taskType,"
                + "argMax(fit_window_days, versionKey) as fitWindowDays,"
                + "argMax(validate_window_days, versionKey) as validateWindowDays,"
                + "argMax(forward_window_days, versionKey) as forwardWindowDays,"
                + "argMax(priority, versionKey) as priority,"
                + "argMax(status, versionKey) as status,"
                + "argMax(suspend_reason, versionKey) as suspendReason,"
                + "argMax(next_retry_time, versionKey) as nextRetryTimeRaw,"
                + "argMax(attempt_count, versionKey) as attemptCount,"
                + "toString(argMax(create_time, versionKey)) as createTime,"
                + "toString(argMax(update_time, versionKey)) as updateTime,"
                + "argMax(payload, versionKey) as payload,"
                + "argMax(failure_reason, versionKey) as failureReason "
                + "from (" + baseSql + ") group by id";
    }

    private boolean ready() {
        return clickHouseDBUtils != null && StringUtils.isNotBlank(clickHouseDBUtils.getDbSourceName());
    }

    private String requiredText(Map<String, Object> request, String key) {
        String value = text(request, key, "");
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private String text(Map<String, Object> request, String key, String defaultValue) {
        Object value = request == null ? null : request.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString().trim();
        return StringUtils.isBlank(text) ? defaultValue : text;
    }

    private int boundedInt(Map<String, Object> request, String key, int defaultValue, int min, int max) {
        Object value = request == null ? null : request.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.toString().trim());
            if (parsed < min) {
                return min;
            }
            if (parsed > max) {
                return max;
            }
            return parsed;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String safe(String value, String defaultValue) {
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        String trim = value.trim();
        if (!trim.matches("[A-Za-z0-9_.]+")) {
            return defaultValue;
        }
        return trim;
    }

    private String blankTo(String value, String defaultValue) {
        return StringUtils.isBlank(value) ? defaultValue : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "''");
    }

    private String swapExt(String path, String ext) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        int idx = path.lastIndexOf('.');
        if (idx < 0) {
            return path + ext;
        }
        return path.substring(0, idx) + ext;
    }

    private boolean fileExists(String path) {
        return StringUtils.isNotBlank(path) && Files.exists(Paths.get(path));
    }

    private String readUtf8File(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        try {
            Path file = Paths.get(path);
            if (!Files.exists(file)) {
                return "";
            }
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("readUtf8File error, path:{}", path, e);
            return "";
        }
    }

    public static class ReportMetaRow {
        public String sid;
        public String strategyName;
        public String strategyVersion;
        public String reportPath;
        public String runTime;
        public Integer resultCount;
        public Double totalPnl;
        public Double validatePnl;
        public Double forwardPnl;
        public Double validatePrimaryScore;
        public Double forwardAuxScore;
        public Double feeAdjustedValidatePnl;
        public Double sliceParamDriftScore;
        public Integer validateTradeCount;
        public Double validateMaxDrawdownPct;
        public Double validateProfitFactor;
        public Integer oosPass;
    }
}
