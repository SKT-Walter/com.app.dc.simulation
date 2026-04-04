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
        String sql = "INSERT INTO " + table
                + " (run_time,sid,strategy_name,strategy_version,baseline_version,runtime_type,scene,"
                + "symbol,text,begin_date,end_date,trade_count,win_count,loss_count,flat_count,"
                + "win_rate,total_return_pct,max_drawdown_pct,initial_capital,final_capital,total_pnl,"
                + "forward_score,window_mode,slice_count,fit_pnl,validate_pnl,forward_pnl,overfit_pass,overfit_reason,report_path,payload)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String sliceSql = "INSERT INTO " + sliceTable
                + " (run_time,sid,strategy_name,strategy_version,symbol,text,slice_no,"
                + "fit_begin,fit_end,validate_begin,validate_end,forward_begin,forward_end,"
                + "fit_pnl,validate_pnl,forward_pnl,fit_trade_count,validate_trade_count,forward_trade_count,"
                + "fit_max_drawdown_pct,validate_max_drawdown_pct,forward_max_drawdown_pct,payload)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        for (BacktestModels.BacktestResult result : results) {
            try {
                Object[] args = new Object[]{
                        Timestamp.from(Instant.now()),
                        safe(sid),
                        safe(result.strategyName),
                        safe(result.strategyVersion),
                        safe(result.baselineVersion),
                        safe(result.runtimeType),
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
                        nzDouble(result.maxDrawdownPct),
                        nzDouble(result.initialCapital),
                        nzDouble(result.finalCapital),
                        result.totalPnl == null
                                ? calcTotalPnl(result.initialCapital, result.finalCapital)
                                : nzDouble(result.totalPnl),
                        nzDouble(result.forwardScore),
                        safe(result.windowMode),
                        nzInt(result.sliceCount),
                        nzDouble(result.fitPnl),
                        nzDouble(result.validatePnl),
                        nzDouble(result.forwardPnl),
                        nzInt(result.overfitPass),
                        safe(result.overfitReason),
                        safe(reportPath),
                        JsonUtils.Serializer(result)
                };
                ClickHouseDBUtils.update(sql, args);
                insertSliceResults(sliceSql, sid, result);
            } catch (Exception e) {
                log.error("BacktestResultClickHouseDao insert error, strategy:{}, symbol:{}",
                        result.strategyName, result.symbol, e);
            }
        }
    }

    private void insertSliceResults(String sql, String sid, BacktestModels.BacktestResult result) {
        if (result == null || result.sliceResults == null || result.sliceResults.isEmpty()) {
            return;
        }
        for (BacktestModels.BacktestSliceResult slice : result.sliceResults) {
            try {
                Object[] args = new Object[]{
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
                        safe(slice.payload)
                };
                ClickHouseDBUtils.update(sql, args);
            } catch (Exception e) {
                log.error("BacktestResultClickHouseDao insert slice error, strategy:{}, symbol:{}, slice:{}",
                        result.strategyName, result.symbol, slice.sliceNo, e);
            }
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

