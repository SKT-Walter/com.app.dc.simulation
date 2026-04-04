package com.app.dc.service.simulation.runtime;

import com.app.common.db.ClickHouseDBUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class ClickHouseStrategyBacktestTaskDao implements StrategyBacktestTaskDao {

    @Autowired(required = false)
    private ClickHouseDBUtils clickHouseDBUtils;

    @Value("${strategy.backtest.task.table:dc.strategy_backtest_task}")
    private String taskTable;

    @Value("${strategy.candidate.table:dc.strategy_candidate}")
    private String candidateTable;

    @Override
    public List<StrategyBacktestTaskRow> pullPending(int limit) {
        if (!ready()) {
            return Collections.emptyList();
        }
        String baseSql = "select *, "
                + "tuple(update_time, multiIf(status='SUCCESS', 4, status='FAILED', 4, status='RUNNING', 3, status='SUSPENDED', 2, 1)) as versionKey "
                + "from " + safe(taskTable);
        String innerSql = "select "
                + "id as id,"
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
                + "argMax(payload, versionKey) as payload "
                + "from (" + baseSql + ")"
                + " group by id";
        String sql = "select "
                + "id, strategyName, strategyVersion, baselineVersion, runtimeType, taskType,"
                + "fitWindowDays, validateWindowDays, forwardWindowDays, priority, status,"
                + "suspendReason, ifNull(toString(nextRetryTimeRaw), '') as nextRetryTime,"
                + "attemptCount, createTime, updateTime, payload "
                + "from (" + innerSql + ") latest"
                + " where latest.status='PENDING'"
                + " or (latest.status='SUSPENDED' and (latest.nextRetryTimeRaw is null or latest.nextRetryTimeRaw <= now()))"
                + " order by latest.priority asc, latest.createTime asc limit " + Math.max(1, limit);
        try {
            List<StrategyBacktestTaskRow> rows = ClickHouseDBUtils.queryList(sql, new Object[]{}, StrategyBacktestTaskRow.class);
            return rows == null ? Collections.<StrategyBacktestTaskRow>emptyList() : rows;
        } catch (Exception e) {
            log.error("pullPending error", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void markRunning(String id) {
        updateStatus(id, "RUNNING", null, null, null, true);
    }

    @Override
    public void markSuccess(String id, String payload) {
        updateStatus(id, "SUCCESS", payload, "", null, false);
    }

    @Override
    public void markFailed(String id, String errorMsg) {
        updateStatus(id, "FAILED", errorMsg, "", null, false);
    }

    @Override
    public void markSuspended(String id, String reason, String payload, String nextRetryTime) {
        updateStatus(id, "SUSPENDED", payload, reason, nextRetryTime, false);
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
                + "runtime_type as runtimeType,"
                + "artifact_uri as artifactUri,"
                + "entry_class as entryClass,"
                + "description as description,"
                + "payload as payload "
                + "from " + safe(candidateTable)
                + " where strategy_name=? and strategy_version=? order by create_time desc limit 1";
        try {
            List<StrategyCandidateRow> rows = ClickHouseDBUtils.queryList(sql,
                    new Object[]{strategyName, strategyVersion},
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
                              String nextRetryTime, boolean increaseAttempt) {
        if (!ready() || StringUtils.isBlank(id)) {
            return;
        }
        String sql = "insert into " + safe(taskTable)
                + " (id, strategy_name, strategy_version, baseline_version, runtime_type, task_type, "
                + "fit_window_days, validate_window_days, forward_window_days, priority, status, suspend_reason, "
                + "next_retry_time, attempt_count, create_time, update_time, payload) "
                + "select id, strategy_name, strategy_version, baseline_version, runtime_type, task_type, "
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
                + " "
                + "from " + safe(taskTable) + " where id='" + escape(id) + "' order by update_time desc limit 1";
        try {
            ClickHouseDBUtils.update(sql, new Object[]{});
        } catch (Exception e) {
            log.error("updateStatus error, id:{}, status:{}", id, status, e);
        }
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
