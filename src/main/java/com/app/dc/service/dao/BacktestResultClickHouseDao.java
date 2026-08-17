package com.app.dc.service.dao;

import com.app.common.db.ClickHouseDBUtils;
import com.app.dc.strategy.core.StrategyRuntimeModels;
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

    public void insertResults(String sid, String reportPath, StrategyRuntimeModels.StrategyRunResponse response) {
        if (!storeEnabled || response == null) {
            return;
        }
        if (!isClickHouseReady()) {
            return;
        }
        List<StrategyRuntimeModels.StrategyRunResult> results =
                response.results == null ? Collections.<StrategyRuntimeModels.StrategyRunResult>emptyList() : response.results;
        if (results.isEmpty()) {
            return;
        }

        String table = safeTableName(tableName);
        String sql = "INSERT INTO " + table
                + " (run_time,sid,strategy_name,symbol,text,begin_date,end_date,"
                + "trade_count,win_count,loss_count,flat_count,win_rate,total_return_pct,max_drawdown_pct,"
                + "initial_capital,final_capital,total_pnl,report_path,payload)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        for (StrategyRuntimeModels.StrategyRunResult result : results) {
            try {
                Object[] args = new Object[]{
                        Timestamp.from(Instant.now()),
                        safe(sid),
                        safe(result.strategyName),
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
                        calcTotalPnl(result.initialCapital, result.finalCapital),
                        safe(reportPath),
                        JsonUtils.Serializer(result)
                };
                ClickHouseDBUtils.update(sql, args);
            } catch (Exception e) {
                log.error("BacktestResultClickHouseDao insert error, strategy:{}, symbol:{}",
                        result.strategyName, result.symbol, e);
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
        if (StringUtils.isBlank(input)) {
            return "backtest_result";
        }
        String trim = input.trim();
        if (!trim.matches("[A-Za-z0-9_.]+")) {
            return "backtest_result";
        }
        return trim;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

