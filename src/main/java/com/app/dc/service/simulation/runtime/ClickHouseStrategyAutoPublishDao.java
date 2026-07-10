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
public class ClickHouseStrategyAutoPublishDao implements StrategyAutoPublishDao {

    @Autowired(required = false)
    private ClickHouseDBUtils clickHouseDBUtils;

    @Value("${strategy.live.registry.table:dc.strategy_live_registry}")
    private String registryTable;

    @Value("${strategy.release.event.table:dc.strategy_release_event}")
    private String releaseEventTable;

    @Value("${binanceBacktestResultTable:backtest_result}")
    private String backtestResultTable;

    @Value("${quant.trade.latest.view.table:dc.quant_trade_latest_view}")
    private String quantTradeLatestViewTable;

    @Override
    public StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName) {
        return loadCurrentActive(strategyName, "");
    }

    @Override
    public StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName, String symbolScope) {
        if (!ready() || StringUtils.isBlank(strategyName)) {
            return null;
        }
        String sql = registryQuery()
                + " where lower(strategy_name)=lower(?) and status='ACTIVE'"
                + " and (retire_time is null or retire_time > now())";
        List<Object> params = new java.util.ArrayList<Object>();
        params.add(strategyName);
        if (StringUtils.isNotBlank(symbolScope)) {
            sql += " and lower(symbol_scope)=lower(?)";
            params.add(symbolScope);
        }
        sql += " order by effective_time desc limit 1";
        try {
            List<StrategyLiveRegistryPublishRow> rows = ClickHouseDBUtils.queryList(sql,
                    params.toArray(new Object[0]), StrategyLiveRegistryPublishRow.class);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        } catch (Exception e) {
            log.error("loadCurrentActive error, strategy:{}", strategyName, e);
            return null;
        }
    }

    @Override
    public List<StrategyLiveRegistryPublishRow> listCurrentActiveRows(String strategyName) {
        if (!ready() || StringUtils.isBlank(strategyName)) {
            return Collections.emptyList();
        }
        String sql = registryQuery()
                + " where lower(strategy_name)=lower(?) and status='ACTIVE'"
                + " and (retire_time is null or retire_time > now())"
                + " order by effective_time desc";
        try {
            List<StrategyLiveRegistryPublishRow> rows = ClickHouseDBUtils.queryList(sql,
                    new Object[]{strategyName}, StrategyLiveRegistryPublishRow.class);
            return rows == null ? Collections.<StrategyLiveRegistryPublishRow>emptyList() : rows;
        } catch (Exception e) {
            log.error("listCurrentActiveRows error, strategy:{}", strategyName, e);
            return Collections.emptyList();
        }
    }

    @Override
    public StrategyLiveRegistryPublishRow loadLatestLiveBaseline(String strategyName) {
        return loadLatestLiveBaseline(strategyName, "");
    }

    @Override
    public StrategyLiveRegistryPublishRow loadLatestLiveBaseline(String strategyName, String symbolScope) {
        if (!ready() || StringUtils.isBlank(strategyName)) {
            return null;
        }
        String sql = registryQuery()
                + " where lower(strategy_name)=lower(?)";
        List<Object> params = new java.util.ArrayList<Object>();
        params.add(strategyName);
        if (StringUtils.isNotBlank(symbolScope)) {
            sql += " and lower(symbol_scope)=lower(?)";
            params.add(symbolScope);
        }
        sql += " order by effective_time desc limit 1";
        try {
            List<StrategyLiveRegistryPublishRow> rows = ClickHouseDBUtils.queryList(sql,
                    params.toArray(new Object[0]), StrategyLiveRegistryPublishRow.class);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        } catch (Exception e) {
            log.error("loadLatestLiveBaseline error, strategy:{}", strategyName, e);
            return null;
        }
    }

    @Override
    public StrategyLiveRegistryPublishRow loadExactActive(String strategyName, String strategyVersion) {
        return loadExactActive(strategyName, strategyVersion, "");
    }

    @Override
    public List<StrategyLiveRegistryPublishRow> listExactActiveRows(String strategyName, String strategyVersion) {
        if (!ready() || StringUtils.isBlank(strategyName) || StringUtils.isBlank(strategyVersion)) {
            return Collections.emptyList();
        }
        String sql = registryQuery()
                + " where lower(strategy_name)=lower(?) and lower(strategy_version)=lower(?) and status='ACTIVE'"
                + " and (retire_time is null or retire_time > now())"
                + " order by effective_time desc";
        try {
            List<StrategyLiveRegistryPublishRow> rows = ClickHouseDBUtils.queryList(sql,
                    new Object[]{strategyName, strategyVersion}, StrategyLiveRegistryPublishRow.class);
            return rows == null ? Collections.<StrategyLiveRegistryPublishRow>emptyList() : rows;
        } catch (Exception e) {
            log.error("listExactActiveRows error, strategy:{}@{}", strategyName, strategyVersion, e);
            return Collections.emptyList();
        }
    }

    @Override
    public StrategyLiveRegistryPublishRow loadExactActive(String strategyName, String strategyVersion, String symbolScope) {
        if (!ready() || StringUtils.isBlank(strategyName) || StringUtils.isBlank(strategyVersion)) {
            return null;
        }
        String sql = registryQuery()
                + " where lower(strategy_name)=lower(?) and lower(strategy_version)=lower(?) and status='ACTIVE'"
                + " and (retire_time is null or retire_time > now())";
        List<Object> params = new java.util.ArrayList<Object>();
        params.add(strategyName);
        params.add(strategyVersion);
        if (StringUtils.isNotBlank(symbolScope)) {
            sql += " and lower(symbol_scope)=lower(?)";
            params.add(symbolScope);
        }
        sql += " order by effective_time desc limit 1";
        try {
            List<StrategyLiveRegistryPublishRow> rows = ClickHouseDBUtils.queryList(sql,
                    params.toArray(new Object[0]), StrategyLiveRegistryPublishRow.class);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        } catch (Exception e) {
            log.error("loadExactActive error, strategy:{}@{}", strategyName, strategyVersion, e);
            return null;
        }
    }

    @Override
    public StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion) {
        return loadLatestSummary(strategyName, strategyVersion, "");
    }

    @Override
    public StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion, String symbolScope) {
        if (!ready() || StringUtils.isBlank(strategyName) || StringUtils.isBlank(strategyVersion)) {
            return null;
        }
        String sql = "select "
                + "sid as sid,"
                + "argMax(strategy_name, run_time) as strategyName,"
                + "argMax(strategy_version, run_time) as strategyVersion,"
                + "argMax(symbol, run_time) as symbolScope,"
                + "argMax(runtime_type, run_time) as runtimeType,"
                + "argMax(execution_model_version, run_time) as executionModelVersion,"
                + "argMax(scene, run_time) as scene,"
                + "argMax(window_mode, run_time) as windowMode,"
                + "argMax(optimization_mode, run_time) as optimizationMode,"
                + "argMax(trial_count, run_time) as trialCount,"
                + "argMax(best_rank, run_time) as bestRank,"
                + "argMax(best_param_set, run_time) as bestParamSetJson,"
                + "min(slice_count) as sliceCount,"
                + "sum(fit_pnl) as fitPnl,"
                + "sum(validate_pnl) as validatePnl,"
                + "sum(forward_pnl) as forwardPnl,"
                + "sum(validate_primary_score) as validatePrimaryScore,"
                + "avg(forward_aux_score) as forwardAuxScore,"
                + "sum(fee_adjusted_validate_pnl) as feeAdjustedValidatePnl,"
                + "sum(fee_adjusted_forward_pnl) as feeAdjustedForwardPnl,"
                + "avg(slice_param_drift_score) as sliceParamDriftScore,"
                + "toString(max(run_time)) as runTime,"
                + "sum(total_pnl) as totalPnl,"
                + "avg(forward_score) as forwardScore,"
                + "sum(trade_count) as validateTradeCount,"
                + "max(max_drawdown_pct) as validateMaxDrawdownPct,"
                + "avg(profit_factor) as validateProfitFactor,"
                + "max(fragile_best) as fragileBest,"
                + "min(oos_pass) as oosPass,"
                + "min(overfit_pass) as overfitPass,"
                + "argMax(overfit_reason, run_time) as overfitReason,"
                + "count() as resultCount "
                + "from " + safe(backtestResultTable, "backtest_result")
                + " where lower(strategy_name)=lower(?) and lower(strategy_version)=lower(?)";
        List<Object> params = new java.util.ArrayList<Object>();
        params.add(strategyName);
        params.add(strategyVersion);
        if (StringUtils.isNotBlank(symbolScope)) {
            sql += " and lower(symbol)=lower(?)";
            params.add(symbolScope);
        }
        sql += " group by sid"
                + " order by max(run_time) desc limit 1";
        try {
            List<StrategyBacktestSummary> rows = ClickHouseDBUtils.queryList(sql,
                    params.toArray(new Object[0]), StrategyBacktestSummary.class);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        } catch (Exception e) {
            log.error("loadLatestSummary error, strategy:{}, version:{}", strategyName, strategyVersion, e);
            return null;
        }
    }

    @Override
    public StrategyLiveTradeStatsRow loadTodayTradeStats(String strategyName, String strategyVersion, String symbolScope) {
        if (!ready() || StringUtils.isBlank(strategyName) || StringUtils.isBlank(strategyVersion)) {
            return null;
        }
        String sql = "select "
                + "count() as todayTradeCount,"
                + "round(sum(ifNull(realizedPnl, 0)), 6) as todayPnl "
                + "from " + safe(quantTradeLatestViewTable, "dc.quant_trade_latest_view")
                + " where tradeDate = toDate(?)"
                + " and lower(strategyName)=lower(?)"
                + " and lower(strategyVersion)=lower(?)";
        List<Object> params = new java.util.ArrayList<Object>();
        params.add(java.time.LocalDate.now().toString());
        params.add(strategyName);
        params.add(strategyVersion);
        if (StringUtils.isNotBlank(symbolScope)) {
            sql += " and lower(symbol)=lower(?)";
            params.add(symbolScope);
        }
        try {
            List<StrategyLiveTradeStatsRow> rows = ClickHouseDBUtils.queryList(sql,
                    params.toArray(new Object[0]), StrategyLiveTradeStatsRow.class);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        } catch (Exception e) {
            log.error("loadTodayTradeStats error, strategy:{}@{}, symbol:{}",
                    strategyName, strategyVersion, symbolScope, e);
            return null;
        }
    }

    @Override
    public StrategyReleaseEventRecord loadLatestReleaseEvent(String strategyName, String strategyVersion) {
        if (!ready() || StringUtils.isBlank(strategyName) || StringUtils.isBlank(strategyVersion)) {
            return null;
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
                + "from " + safe(releaseEventTable, "dc.strategy_release_event")
                + " where lower(strategy_name)=lower(?) and lower(to_version)=lower(?)"
                + " order by event_time desc limit 1";
        try {
            List<StrategyReleaseEventRecord> rows = ClickHouseDBUtils.queryList(sql,
                    new Object[]{strategyName, strategyVersion}, StrategyReleaseEventRecord.class);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        } catch (Exception e) {
            log.error("loadLatestReleaseEvent error, strategy:{}@{}", strategyName, strategyVersion, e);
            return null;
        }
    }

    @Override
    public void retireActive(String strategyName, String exceptVersion, String retireTime) {
        retireActive(strategyName, "", exceptVersion, retireTime);
    }

    @Override
    public void retireActive(String strategyName, String symbolScope, String exceptVersion, String retireTime) {
        if (!ready()) {
            throw new IllegalStateException("clickhouse not ready for retireActive");
        }
        if (StringUtils.isBlank(strategyName)) {
            return;
        }
        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLE ").append(safe(registryTable, "dc.strategy_live_registry"))
                .append(" UPDATE status='OFFLINE', retire_time=toDateTime('")
                .append(escape(retireTime))
                .append("') WHERE lower(strategy_name)=lower('")
                .append(escape(strategyName))
                .append("') and status='ACTIVE' and (retire_time is null or retire_time > now())");
        if (StringUtils.isNotBlank(symbolScope)) {
            sql.append(" and lower(symbol_scope)=lower('")
                    .append(escape(symbolScope))
                    .append("')");
        }
        if (StringUtils.isNotBlank(exceptVersion)) {
            sql.append(" and lower(strategy_version)!=lower('")
                    .append(escape(exceptVersion))
                    .append("')");
        }
        try {
            ClickHouseDBUtils.update(sql.toString(), new Object[]{});
        } catch (Exception e) {
            log.error("retireActive error, strategy:{}, exceptVersion:{}", strategyName, exceptVersion, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void insertRegistry(StrategyLiveRegistryPublishRow row) {
        if (!ready()) {
            throw new IllegalStateException("clickhouse not ready for insertRegistry");
        }
        if (row == null) {
            return;
        }
        String sql = "INSERT INTO " + safe(registryTable, "dc.strategy_live_registry")
                + " (id, strategy_name, strategy_version, category, scene, runtime_type, symbol_scope, text_scope,"
                + " artifact_uri, entry_class, parameters_json, status, effective_time, retire_time, source, payload, description)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,toDateTimeOrNull(?),?,?,?)";
        try {
            ClickHouseDBUtils.update(sql, new Object[]{
                    safeValue(row.id),
                    safeValue(row.strategyName),
                    safeValue(row.strategyVersion),
                    safeValue(row.category),
                    safeValue(row.scene),
                    safeValue(row.runtimeType),
                    safeValue(row.symbolScope),
                    safeValue(row.textScope),
                    safeValue(row.artifactUri),
                    safeValue(row.entryClass),
                    safeValue(row.parametersJson),
                    safeValue(row.status),
                    safeValue(row.effectiveTime),
                    emptyToNull(row.retireTime),
                    safeValue(row.source),
                    safeValue(row.payload),
                    safeValue(row.description)
            });
        } catch (Exception e) {
            log.error("insertRegistry error, strategy:{}@{}", row.strategyName, row.strategyVersion, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void insertReleaseEvent(StrategyReleaseEventRecord row) {
        if (!ready()) {
            throw new IllegalStateException("clickhouse not ready for insertReleaseEvent");
        }
        if (row == null) {
            return;
        }
        String sql = "INSERT INTO " + safe(releaseEventTable, "dc.strategy_release_event")
                + " (id, event_time, strategy_name, from_version, to_version, runtime_type, event_type, reason, source, payload)"
                + " VALUES (?,toDateTime(?),?,?,?,?,?,?,?,?)";
        try {
            ClickHouseDBUtils.update(sql, new Object[]{
                    safeValue(row.id),
                    safeValue(row.eventTime),
                    safeValue(row.strategyName),
                    emptyToNull(row.fromVersion),
                    safeValue(row.toVersion),
                    safeValue(row.runtimeType),
                    safeValue(row.eventType),
                    safeValue(row.reason),
                    safeValue(row.source),
                    safeValue(row.payload)
            });
        } catch (Exception e) {
            log.error("insertReleaseEvent error, strategy:{}@{}", row.strategyName, row.toVersion, e);
            throw new RuntimeException(e);
        }
    }

    private boolean ready() {
        return clickHouseDBUtils != null && StringUtils.isNotBlank(clickHouseDBUtils.getDbSourceName());
    }

    private String registryQuery() {
        return "select "
                + "id as id,"
                + "strategy_name as strategyName,"
                + "strategy_version as strategyVersion,"
                + "category as category,"
                + "scene as scene,"
                + "runtime_type as runtimeType,"
                + "symbol_scope as symbolScope,"
                + "text_scope as textScope,"
                + "artifact_uri as artifactUri,"
                + "entry_class as entryClass,"
                + "parameters_json as parametersJson,"
                + "status as status,"
                + "toString(effective_time) as effectiveTime,"
                + "toString(retire_time) as retireTime,"
                + "source as source,"
                + "payload as payload,"
                + "description as description "
                + "from " + safe(registryTable, "dc.strategy_live_registry");
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

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "''");
    }
}
