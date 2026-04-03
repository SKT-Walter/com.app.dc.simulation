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
        String versionExpr = "tuple(update_time, multiIf(status='SUCCESS', 3, status='FAILED', 3, status='RUNNING', 2, 1))";
        String sql = "select "
                + "id as id,"
                + "argMax(strategy_name, " + versionExpr + ") as strategyName,"
                + "argMax(strategy_version, " + versionExpr + ") as strategyVersion,"
                + "argMax(baseline_version, " + versionExpr + ") as baselineVersion,"
                + "argMax(runtime_type, " + versionExpr + ") as runtimeType,"
                + "argMax(task_type, " + versionExpr + ") as taskType,"
                + "argMax(fit_window_days, " + versionExpr + ") as fitWindowDays,"
                + "argMax(validate_window_days, " + versionExpr + ") as validateWindowDays,"
                + "argMax(forward_window_days, " + versionExpr + ") as forwardWindowDays,"
                + "argMax(priority, " + versionExpr + ") as priority,"
                + "argMax(status, " + versionExpr + ") as status,"
                + "toString(argMax(create_time, " + versionExpr + ")) as createTime,"
                + "toString(argMax(update_time, " + versionExpr + ")) as updateTime,"
                + "argMax(payload, " + versionExpr + ") as payload "
                + "from " + safe(taskTable)
                + " group by id having argMax(status, " + versionExpr + ")='PENDING'"
                + " order by priority asc, createTime asc limit " + Math.max(1, limit);
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
        updateStatus(id, "RUNNING", "");
    }

    @Override
    public void markSuccess(String id, String payload) {
        updateStatus(id, "SUCCESS", payload);
    }

    @Override
    public void markFailed(String id, String errorMsg) {
        updateStatus(id, "FAILED", errorMsg);
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

    private void updateStatus(String id, String status, String payload) {
        if (!ready() || StringUtils.isBlank(id)) {
            return;
        }
        String sql = "insert into " + safe(taskTable)
                + " (id, strategy_name, strategy_version, baseline_version, runtime_type, task_type, "
                + "fit_window_days, validate_window_days, forward_window_days, priority, status, create_time, update_time, payload) "
                + "select id, strategy_name, strategy_version, baseline_version, runtime_type, task_type, "
                + "fit_window_days, validate_window_days, forward_window_days, priority, '"
                + escape(status) + "', create_time, now(), '" + escape(payload == null ? "" : payload) + "' "
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
