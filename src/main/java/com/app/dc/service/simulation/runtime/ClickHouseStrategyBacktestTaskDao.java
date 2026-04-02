package com.app.dc.service.simulation.runtime;

import com.app.common.db.ClickHouseDBUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
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
        String sql = "select "
                + "id as id,"
                + "strategy_name as strategyName,"
                + "strategy_version as strategyVersion,"
                + "baseline_version as baselineVersion,"
                + "runtime_type as runtimeType,"
                + "task_type as taskType,"
                + "fit_window_days as fitWindowDays,"
                + "validate_window_days as validateWindowDays,"
                + "forward_window_days as forwardWindowDays,"
                + "priority as priority,"
                + "status as status,"
                + "toString(create_time) as createTime,"
                + "toString(update_time) as updateTime,"
                + "payload as payload "
                + "from " + safe(taskTable)
                + " where status='PENDING' order by priority asc, create_time asc limit " + Math.max(1, limit);
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
                + "fit_window_days, validate_window_days, forward_window_days, priority, ?, create_time, ?, ? "
                + "from " + safe(taskTable) + " where id=? order by update_time desc limit 1";
        try {
            ClickHouseDBUtils.update(sql, new Object[]{status, Timestamp.from(Instant.now()), payload == null ? "" : payload, id});
        } catch (Exception e) {
            log.error("updateStatus error, id:{}, status:{}", id, status, e);
        }
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
