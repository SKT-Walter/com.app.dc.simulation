package com.app.dc.service.simulation.runtime;

import com.app.common.db.ClickHouseDBUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@Slf4j
public class ClickHouseStrategyBacktestTaskDao implements StrategyBacktestTaskDao {

    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired(required = false)
    private ClickHouseDBUtils clickHouseDBUtils;

    @Value("${strategy.backtest.task.table:dc.strategy_backtest_task}")
    private String taskTable;

    @Value("${strategy.candidate.table:dc.strategy_candidate}")
    private String candidateTable;

    @Override
    public List<StrategyBacktestTaskRow> pullPending(int limit) {
        return pullRunnable(limit, null);
    }

    @Override
    public List<StrategyBacktestTaskRow> pullRunnable(int limit, String reclaimRunningBefore) {
        if (!ready()) {
            return Collections.emptyList();
        }
        String baseSql = "select *, "
                + "tuple(update_time, multiIf(status='SUCCESS', 4, status='FAILED', 4, status='RUNNING', 3, status='SUSPENDED', 2, 1)) as versionKey "
                + "from " + safe(taskTable);
        String innerSql = "select "
                + "id as id,"
                + "argMax(candidate_id, versionKey) as candidateId,"
                + "argMax(generation_task_id, versionKey) as generationTaskId,"
                + "argMax(strategy_name, versionKey) as strategyName,"
                + "argMax(strategy_version, versionKey) as strategyVersion,"
                + "argMax(baseline_version, versionKey) as baselineVersion,"
                + "argMax(runtime_type, versionKey) as runtimeType,"
                + "argMax(task_type, versionKey) as taskType,"
                + "toInt32(ifNull(argMax(fit_window_days, versionKey), 0)) as fitWindowDays,"
                + "toInt32(ifNull(argMax(validate_window_days, versionKey), 0)) as validateWindowDays,"
                + "toInt32(ifNull(argMax(forward_window_days, versionKey), 0)) as forwardWindowDays,"
                + "toInt32(ifNull(argMax(priority, versionKey), 0)) as priority,"
                + "argMax(status, versionKey) as status,"
                + "argMax(suspend_reason, versionKey) as suspendReason,"
                + "argMax(next_retry_time, versionKey) as nextRetryTimeRaw,"
                + "toInt32(ifNull(argMax(attempt_count, versionKey), 0)) as attemptCount,"
                + "toString(argMax(create_time, versionKey)) as createTime,"
                + "toString(argMax(update_time, versionKey)) as updateTime,"
                + "argMax(payload, versionKey) as payload,"
                + "argMax(failure_reason, versionKey) as failureReason,"
                + "toInt32(ifNull(argMax(published_live, versionKey), 0)) as publishedLive "
                + "from (" + baseSql + ")"
                + " group by id";
        String sql = "select "
                + "id, candidateId, generationTaskId, strategyName, strategyVersion, baselineVersion, runtimeType, taskType,"
                + "fitWindowDays, validateWindowDays, forwardWindowDays, priority, status,"
                + "suspendReason, ifNull(toString(nextRetryTimeRaw), '') as nextRetryTime,"
                + "attemptCount, createTime, updateTime, payload, failureReason, publishedLive "
                + "from (" + innerSql + ") latest"
                + " where latest.status='PENDING'"
                + " or (latest.status='SUSPENDED' and (latest.nextRetryTimeRaw is null or latest.nextRetryTimeRaw <= now()))";
        if (!StringUtils.isBlank(reclaimRunningBefore)) {
            sql = sql + " or (latest.status='RUNNING' and parseDateTimeBestEffortOrNull(latest.updateTime) < parseDateTimeBestEffortOrNull('"
                    + escape(reclaimRunningBefore) + "'))";
        }
        sql = sql
                + " order by latest.priority asc, latest.createTime asc limit " + Math.max(1, limit);
        try {
            List<StrategyBacktestTaskRow> rows = ClickHouseDBUtils.queryList(sql, new Object[]{}, StrategyBacktestTaskRow.class);
            return rows == null ? Collections.<StrategyBacktestTaskRow>emptyList() : rows;
        } catch (Exception e) {
            log.error("pullRunnable error, reclaimRunningBefore:{}", reclaimRunningBefore, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<StrategyBacktestTaskRow> loadLatest(String taskId,
                                                    String generationTaskId,
                                                    String candidateId,
                                                    String strategyName,
                                                    String strategyVersion,
                                                    String status,
                                                    int limit) {
        if (!ready()) {
            return Collections.emptyList();
        }
        String innerSql = latestTaskSql();
        StringBuilder sql = new StringBuilder();
        sql.append("select ")
                .append("id, candidateId, generationTaskId, strategyName, strategyVersion, baselineVersion, runtimeType, taskType,")
                .append("fitWindowDays, validateWindowDays, forwardWindowDays, priority, status,")
                .append("suspendReason, ifNull(toString(nextRetryTimeRaw), '') as nextRetryTime,")
                .append("attemptCount, createTime, updateTime, payload, failureReason, publishedLive ")
                .append("from (").append(innerSql).append(") latest where 1=1");
        if (StringUtils.isNotBlank(taskId)) {
            sql.append(" and latest.id='").append(escape(taskId.trim())).append("'");
        }
        if (StringUtils.isNotBlank(generationTaskId)) {
            sql.append(" and latest.generationTaskId='").append(escape(generationTaskId.trim())).append("'");
        }
        if (StringUtils.isNotBlank(candidateId)) {
            sql.append(" and latest.candidateId='").append(escape(candidateId.trim())).append("'");
        }
        if (StringUtils.isNotBlank(strategyName)) {
            sql.append(" and latest.strategyName='").append(escape(strategyName.trim())).append("'");
        }
        if (StringUtils.isNotBlank(strategyVersion)) {
            sql.append(" and latest.strategyVersion='").append(escape(strategyVersion.trim())).append("'");
        }
        if (StringUtils.isNotBlank(status)) {
            sql.append(" and latest.status='").append(escape(status.trim())).append("'");
        }
        sql.append(" order by parseDateTimeBestEffortOrNull(latest.updateTime) desc limit ")
                .append(Math.max(1, Math.min(limit, 100)));
        try {
            List<StrategyBacktestTaskRow> rows = ClickHouseDBUtils.queryList(sql.toString(), new Object[]{},
                    StrategyBacktestTaskRow.class);
            return rows == null ? Collections.<StrategyBacktestTaskRow>emptyList() : rows;
        } catch (Exception e) {
            log.error("loadLatest error, taskId:{}, generationTaskId:{}, candidateId:{}, strategy:{}@{}, status:{}",
                    taskId, generationTaskId, candidateId, strategyName, strategyVersion, status, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void markRunning(String id) {
        updateStatus(id, "RUNNING", null, null, null, "", true, null);
    }

    @Override
    public void refreshRunningProgress(String id, String payload) {
        updateStatus(id, "RUNNING", payload, null, null, "", false, null);
    }

    @Override
    public void markSuccess(String id, String payload, boolean publishedLive) {
        updateStatus(id, "SUCCESS", payload, "", null, "", false, publishedLive);
    }

    @Override
    public void markFailed(String id, String errorMsg) {
        updateStatus(id, "FAILED", null, "", null, errorMsg, false, null);
    }

    @Override
    public void markSuspended(String id, String reason, String payload, String nextRetryTime) {
        updateStatus(id, "SUSPENDED", payload, reason, nextRetryTime, "", false, null);
    }

    @Override
    public void markRetryReadyNow(String id, String reason) {
        updateStatus(id, "SUSPENDED", null,
                StringUtils.defaultString(reason),
                CLICKHOUSE_TIME.format(LocalDateTime.now()),
                "",
                false,
                null);
    }

    @Override
    public void refreshRecoveryProgress(String id, String payload, String nextRetryTime) {
        updateStatus(id, "SUSPENDED", payload, INSUFFICIENT_KLINE, nextRetryTime, "", false, null);
    }

    @Override
    public StrategyCandidateRow loadCandidate(String strategyName, String strategyVersion) {
        if (!ready()) {
            return null;
        }
        String sql = "select "
                + "id as id,"
                + "strategy_name as strategyName,"
                + "strategy_version as strategyVersion,"
                + "parent_version as parentVersion,"
                + "category as category,"
                + "scene as scene,"
                + "generation_type as generationType,"
                + "runtime_type as runtimeType,"
                + "artifact_uri as artifactUri,"
                + "entry_class as entryClass,"
                + "description as description,"
                + "parameters_json as parametersJson,"
                + "payload as payload "
                + "from " + safe(candidateTable)
                + " where strategy_name='" + escape(strategyName) + "'"
                + " and strategy_version='" + escape(strategyVersion) + "'"
                + " order by create_time desc limit 1";
        try {
            List<StrategyCandidateRow> rows = ClickHouseDBUtils.queryList(sql,
                    new Object[]{},
                    StrategyCandidateRow.class);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        } catch (Exception e) {
            log.error("loadCandidate error, strategy:{}, version:{}", strategyName, strategyVersion, e);
            return null;
        }
    }

    private void updateStatus(String id, String status, String payload, String suspendReason,
                              String nextRetryTime, String failureReason, boolean increaseAttempt,
                              Boolean publishedLive) {
        if (!ready() || StringUtils.isBlank(id)) {
            return;
        }
        String sql = "insert into " + safe(taskTable)
                + " (id, candidate_id, generation_task_id, strategy_name, strategy_version, baseline_version, runtime_type, task_type, "
                + "fit_window_days, validate_window_days, forward_window_days, priority, status, suspend_reason, "
                + "next_retry_time, attempt_count, create_time, update_time, payload, failure_reason, published_live) "
                + "select id, ifNull(candidate_id, ''), ifNull(generation_task_id, ''), strategy_name, strategy_version, baseline_version, runtime_type, task_type, "
                + "fit_window_days, validate_window_days, forward_window_days, priority, '"
                + escape(status) + "', '"
                + escape(suspendReason == null ? "" : suspendReason) + "', "
                + (StringUtils.isBlank(nextRetryTime)
                ? "NULL"
                : "toDateTimeOrNull('" + escape(nextRetryTime) + "')")
                + ", "
                + (increaseAttempt ? "ifNull(attempt_count, 0) + 1" : "ifNull(attempt_count, 0)")
                + ", create_time, now(), "
                + (payload == null ? "payload" : "'" + escape(payload) + "'")
                + ", '" + escape(failureReason == null ? "" : failureReason) + "'"
                + ", " + (publishedLive == null ? "ifNull(published_live, 0)" : (publishedLive.booleanValue() ? "1" : "0"))
                + " "
                + "from " + safe(taskTable) + " where id='" + escape(id) + "' order by update_time desc limit 1";
        try {
            ClickHouseDBUtils.update(sql, new Object[]{});
        } catch (Exception e) {
            log.error("updateStatus error, id:{}, status:{}, payloadLength:{}, suspendReasonLength:{}, failureReasonLength:{}",
                    id,
                    status,
                    payload == null ? 0 : payload.length(),
                    suspendReason == null ? 0 : suspendReason.length(),
                    failureReason == null ? 0 : failureReason.length(),
                    e);
        }
    }

    private static final String INSUFFICIENT_KLINE = "INSUFFICIENT_KLINE";

    private String latestTaskSql() {
        String baseSql = "select *, "
                + "tuple(update_time, multiIf(status='SUCCESS', 4, status='FAILED', 4, status='RUNNING', 3, status='SUSPENDED', 2, 1)) as versionKey "
                + "from " + safe(taskTable);
        return "select "
                + "id as id,"
                + "argMax(candidate_id, versionKey) as candidateId,"
                + "argMax(generation_task_id, versionKey) as generationTaskId,"
                + "argMax(strategy_name, versionKey) as strategyName,"
                + "argMax(strategy_version, versionKey) as strategyVersion,"
                + "argMax(baseline_version, versionKey) as baselineVersion,"
                + "argMax(runtime_type, versionKey) as runtimeType,"
                + "argMax(task_type, versionKey) as taskType,"
                + "toInt32(ifNull(argMax(fit_window_days, versionKey), 0)) as fitWindowDays,"
                + "toInt32(ifNull(argMax(validate_window_days, versionKey), 0)) as validateWindowDays,"
                + "toInt32(ifNull(argMax(forward_window_days, versionKey), 0)) as forwardWindowDays,"
                + "toInt32(ifNull(argMax(priority, versionKey), 0)) as priority,"
                + "argMax(status, versionKey) as status,"
                + "argMax(suspend_reason, versionKey) as suspendReason,"
                + "argMax(next_retry_time, versionKey) as nextRetryTimeRaw,"
                + "toInt32(ifNull(argMax(attempt_count, versionKey), 0)) as attemptCount,"
                + "toString(argMax(create_time, versionKey)) as createTime,"
                + "toString(argMax(update_time, versionKey)) as updateTime,"
                + "argMax(payload, versionKey) as payload,"
                + "argMax(failure_reason, versionKey) as failureReason,"
                + "toInt32(ifNull(argMax(published_live, versionKey), 0)) as publishedLive "
                + "from (" + baseSql + ")"
                + " group by id";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "''");
    }

    private boolean ready() {
        return clickHouseDBUtils != null && StringUtils.isNotBlank(clickHouseDBUtils.getDbSourceName());
    }

    private String safe(String value) {
        if (StringUtils.isBlank(value)) {
            return "dc.strategy_backtest_task";
        }
        if (!value.trim().matches("[A-Za-z0-9_.]+")) {
            return "dc.strategy_backtest_task";
        }
        return value.trim();
    }
}
