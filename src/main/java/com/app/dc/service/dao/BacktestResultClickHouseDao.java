package com.app.dc.service.dao;

import com.app.common.db.ClickHouseDBUtils;
import com.app.dc.service.simulation.BacktestModels;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class BacktestResultClickHouseDao {

    @Autowired(required = false)
    private ClickHouseDBUtils clickHouseDBUtils;

    @Value("${binanceBacktestStoreEnabled:true}")
    private boolean storeEnabled;

    @Value("${binanceBacktestResultTable:backtest_result}")
    private String tableName;

    @Value("${binanceBacktestSliceResultTable:backtest_slice_result}")
    private String sliceTableName;

    @Value("${binanceBacktestOptimizationTrialTable:backtest_optimization_trial}")
    private String trialTableName;

    public void insertResults(String sid, String reportPath, BacktestModels.BacktestResponse response) {
        if (!storeEnabled || response == null) {
            return;
        }
        if (!isClickHouseReady()) {
            return;
        }
        List<BacktestModels.BacktestResult> results =
                response.results == null ? Collections.<BacktestModels.BacktestResult>emptyList() : response.results;
        if (results.isEmpty()) {
            return;
        }

        String table = safeTableName(tableName);
        String sliceTable = safeTableName(sliceTableName, "backtest_slice_result");
        String trialTable = safeTableName(trialTableName, "backtest_optimization_trial");
        String sql = "INSERT INTO " + table
                + " (run_time,sid,strategy_name,strategy_version,baseline_version,runtime_type,execution_model_version,scene,"
                + "symbol,text,begin_date,end_date,trade_count,win_count,loss_count,flat_count,"
                + "win_rate,total_return_pct,profit_factor,max_drawdown_pct,initial_capital,final_capital,total_pnl,"
                + "forward_score,validate_primary_score,forward_aux_score,fee_adjusted_validate_pnl,fee_adjusted_forward_pnl,slice_param_drift_score,oos_pass,"
                + "window_mode,slice_count,fit_pnl,validate_pnl,forward_pnl,overfit_pass,overfit_reason,"
                + "optimization_mode,trial_count,best_param_set,best_rank,symbol_count,fit_window_days,validate_window_days,"
                + "forward_window_days,min_slice_count,optimization_objective,min_forward_contribution,elapsed_ms,fragile_best,"
                + "stable_param_range,neighbor_avg_pnl,neighbor_worst_pnl,report_path,payload)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String sliceSql = "INSERT INTO " + sliceTable
                + " (run_time,sid,strategy_name,strategy_version,symbol,text,slice_no,"
                + "fit_begin,fit_end,validate_begin,validate_end,forward_begin,forward_end,"
                + "fit_pnl,validate_pnl,forward_pnl,fit_trade_count,validate_trade_count,forward_trade_count,"
                + "fit_max_drawdown_pct,validate_max_drawdown_pct,forward_max_drawdown_pct,best_param_set,fit_score,validate_score,forward_score,selection_objective,fragile_best,payload)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String trialSql = "INSERT INTO " + trialTable
                + " (run_time,sid,strategy_name,strategy_version,symbol_scope,text_scope,trial_no,phase,param_set,"
                + "fit_pnl,validate_pnl,forward_pnl,total_pnl,forward_score,max_drawdown_pct,overfit_pass,overfit_reason,rank,"
                + "elapsed_ms,symbol_count,slice_count,fit_window_days,validate_window_days,forward_window_days,min_slice_count,"
                + "optimization_objective,min_forward_contribution,fragile_best,stable_param_range,neighbor_avg_pnl,neighbor_worst_pnl,payload)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        Connection con = null;
        try {
            con = ClickHouseDBUtils.getDatabaseConnection().getConnection();
            insertBacktestResults(con, sql, sliceSql, sid, reportPath, results);
            insertOptimizationTrials(con, trialSql, sid, response);
        } catch (Exception e) {
            log.error("BacktestResultClickHouseDao insert session error, sid:{}, resultCount:{}, trialCount:{}",
                    sid,
                    results.size(),
                    response.trials == null ? 0 : response.trials.size(),
                    e);
        } finally {
            if (con != null) {
                try {
                    ClickHouseDBUtils.getDatabaseConnection().freeConnection(con);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void insertBacktestResults(Connection con,
                                       String resultSql,
                                       String sliceSql,
                                       String sid,
                                       String reportPath,
                                       List<BacktestModels.BacktestResult> results) throws Exception {
        PreparedStatement resultPs = null;
        PreparedStatement slicePs = null;
        try {
            resultPs = con.prepareStatement(resultSql);
            slicePs = con.prepareStatement(sliceSql);
            for (BacktestModels.BacktestResult result : results) {
                try {
                    executePrepared(resultPs, buildResultArgs(sid, reportPath, result));
                    insertSliceResults(slicePs, sid, result);
                } catch (Exception e) {
                    log.error("BacktestResultClickHouseDao insert error, strategy:{}, symbol:{}",
                            result.strategyName, result.symbol, e);
                }
            }
        } finally {
            closeQuietly(slicePs);
            closeQuietly(resultPs);
        }
    }

    private void insertSliceResults(PreparedStatement slicePs, String sid, BacktestModels.BacktestResult result) {
        if (result == null || result.sliceResults == null || result.sliceResults.isEmpty()) {
            return;
        }
        for (BacktestModels.BacktestSliceResult slice : result.sliceResults) {
            try {
                executePrepared(slicePs, buildSliceArgs(sid, slice));
            } catch (Exception e) {
                log.error("BacktestResultClickHouseDao insert slice error, strategy:{}, symbol:{}, slice:{}",
                        result.strategyName, result.symbol, slice.sliceNo, e);
            }
        }
    }

    private void insertOptimizationTrials(Connection con,
                                          String sql,
                                          String sid,
                                          BacktestModels.BacktestResponse response) throws Exception {
        if (response == null || response.trials == null || response.trials.isEmpty()) {
            return;
        }
        PreparedStatement trialPs = null;
        try {
            trialPs = con.prepareStatement(sql);
            for (BacktestModels.OptimizationTrial trial : response.trials) {
                try {
                    executePrepared(trialPs, buildTrialArgs(sid, trial));
                } catch (Exception e) {
                    log.error("BacktestResultClickHouseDao insert optimization trial error, strategy:{}, trial:{}",
                            trial.strategyName, trial.trialNo, e);
                }
            }
        } finally {
            closeQuietly(trialPs);
        }
    }

    private Object[] buildResultArgs(String sid, String reportPath, BacktestModels.BacktestResult result) {
        return new Object[]{
                Timestamp.from(Instant.now()),
                safe(sid),
                safe(result.strategyName),
                safe(result.strategyVersion),
                safe(result.baselineVersion),
                safe(result.runtimeType),
                safe(result.executionModelVersion),
                safe(result.scene),
                safe(result.symbol),
                safe(result.text),
                safe(result.beginDate),
                safe(result.endDate),
                nzInt(result.tradeCount),
                nzInt(result.winCount),
                nzInt(result.lossCount),
                nzInt(result.flatCount),
                nzDouble(result.winRate),
                nzDouble(result.totalReturnPct),
                nzDouble(result.profitFactor),
                nzDouble(result.maxDrawdownPct),
                nzDouble(result.initialCapital),
                nzDouble(result.finalCapital),
                result.totalPnl == null
                        ? calcTotalPnl(result.initialCapital, result.finalCapital)
                        : nzDouble(result.totalPnl),
                nzDouble(result.forwardScore),
                nzDouble(result.validatePrimaryScore),
                nzDouble(result.forwardAuxScore),
                nzDouble(result.feeAdjustedValidatePnl),
                nzDouble(result.feeAdjustedForwardPnl),
                nzDouble(result.sliceParamDriftScore),
                nzInt(result.oosPass),
                safe(result.windowMode),
                nzInt(result.sliceCount),
                nzDouble(result.fitPnl),
                nzDouble(result.validatePnl),
                nzDouble(result.forwardPnl),
                nzInt(result.overfitPass),
                safe(result.overfitReason),
                safe(result.optimizationMode),
                nzInt(result.trialCount),
                safe(result.bestParamSetJson),
                nzInt(result.bestRank),
                nzInt(result.symbolCount),
                nzInt(result.fitWindowDays),
                nzInt(result.validateWindowDays),
                nzInt(result.forwardWindowDays),
                nzInt(result.minSliceCount),
                safe(result.optimizationObjective),
                nzDouble(result.minForwardContribution),
                nzInt(result.elapsedMs),
                nzInt(result.fragileBest),
                safe(result.stableParamRangeJson),
                nzDouble(result.neighborAvgPnl),
                nzDouble(result.neighborWorstPnl),
                safe(reportPath),
                JsonUtils.Serializer(result)
        };
    }

    private Object[] buildSliceArgs(String sid, BacktestModels.BacktestSliceResult slice) {
        return new Object[]{
                Timestamp.from(Instant.now()),
                safe(sid),
                safe(slice.strategyName),
                safe(slice.strategyVersion),
                safe(slice.symbol),
                safe(slice.text),
                nzInt(slice.sliceNo),
                safe(slice.fitBegin),
                safe(slice.fitEnd),
                safe(slice.validateBegin),
                safe(slice.validateEnd),
                safe(slice.forwardBegin),
                safe(slice.forwardEnd),
                nzDouble(slice.fitPnl),
                nzDouble(slice.validatePnl),
                nzDouble(slice.forwardPnl),
                nzInt(slice.fitTradeCount),
                nzInt(slice.validateTradeCount),
                nzInt(slice.forwardTradeCount),
                nzDouble(slice.fitMaxDrawdownPct),
                nzDouble(slice.validateMaxDrawdownPct),
                nzDouble(slice.forwardMaxDrawdownPct),
                safe(slice.bestParamSetJson),
                nzDouble(slice.fitScore),
                nzDouble(slice.validateScore),
                nzDouble(slice.forwardScore),
                safe(slice.selectionObjective),
                nzInt(slice.fragileBest),
                safe(slice.payload)
        };
    }

    private Object[] buildTrialArgs(String sid, BacktestModels.OptimizationTrial trial) {
        return new Object[]{
                Timestamp.from(Instant.now()),
                safe(sid),
                safe(trial.strategyName),
                safe(trial.strategyVersion),
                safe(trial.symbolScope),
                safe(trial.textScope),
                nzInt(trial.trialNo),
                safe(trial.phase),
                safe(trial.paramSetJson),
                nzDouble(trial.fitPnl),
                nzDouble(trial.validatePnl),
                nzDouble(trial.forwardPnl),
                nzDouble(trial.totalPnl),
                nzDouble(trial.forwardScore),
                nzDouble(trial.maxDrawdownPct),
                nzInt(trial.overfitPass),
                safe(trial.overfitReason),
                nzInt(trial.rank),
                nzInt(trial.elapsedMs),
                nzInt(trial.symbolCount),
                nzInt(trial.sliceCount),
                nzInt(trial.fitWindowDays),
                nzInt(trial.validateWindowDays),
                nzInt(trial.forwardWindowDays),
                nzInt(trial.minSliceCount),
                safe(trial.optimizationObjective),
                nzDouble(trial.minForwardContribution),
                nzInt(trial.fragileBest),
                safe(trial.stableParamRangeJson),
                nzDouble(trial.neighborAvgPnl),
                nzDouble(trial.neighborWorstPnl),
                JsonUtils.Serializer(trial)
        };
    }

    private void executePrepared(PreparedStatement ps, Object[] args) throws Exception {
        ps.clearParameters();
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        ps.executeUpdate();
    }

    private void closeQuietly(PreparedStatement ps) {
        if (ps == null) {
            return;
        }
        try {
            ps.close();
        } catch (Exception ignore) {
        }
    }

    private Double calcTotalPnl(BigDecimal initialCapital, BigDecimal finalCapital) {
        if (initialCapital == null || finalCapital == null) {
            return 0D;
        }
        return finalCapital.subtract(initialCapital).doubleValue();
    }

    private Integer nzInt(Integer value) {
        return value == null ? 0 : value;
    }

    private Double nzDouble(BigDecimal value) {
        return value == null ? 0D : value.doubleValue();
    }

    private boolean isClickHouseReady() {
        if (clickHouseDBUtils == null) {
            return false;
        }
        return StringUtils.isNotBlank(clickHouseDBUtils.getDbSourceName());
    }

    private String safeTableName(String input) {
        return safeTableName(input, "backtest_result");
    }

    private String safeTableName(String input, String fallback) {
        if (StringUtils.isBlank(input)) {
            return fallback;
        }
        String trim = input.trim();
        if (!trim.matches("[A-Za-z0-9_.]+")) {
            return fallback;
        }
        return trim;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

