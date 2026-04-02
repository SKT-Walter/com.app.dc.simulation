package com.app.dc.service.simulation;

import com.app.dc.po.Side;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class BacktestModels {

    private BacktestModels() {
    }

    public static class BacktestResponse {
        public String strategyName;
        public String strategyVersion;
        public String baselineVersion;
        public String runtimeType;
        public String scene;
        public String symbol;
        public List<String> symbols;
        public String text;
        public String beginDate;
        public String endDate;
        public List<BacktestResult> results;
    }

    public static class BacktestResult {
        public String strategyName;
        public String strategyVersion;
        public String baselineVersion;
        public String runtimeType;
        public String scene;
        public String strategyPayload;
        public BigDecimal forwardScore = BigDecimal.ZERO;
        public String symbol;
        public String text;
        public String beginDate;
        public String endDate;
        public BigDecimal initialCapital;
        public BigDecimal finalCapital;
        public BigDecimal feeRatePct;
        public BigDecimal fallbackStopLossPct;
        public BigDecimal fallbackTakeProfitPct;
        public Integer maxHoldBars;
        public Integer totalBars = 0;
        public Integer tradeCount = 0;
        public Integer winCount = 0;
        public Integer lossCount = 0;
        public Integer flatCount = 0;
        public Integer stopExitCount = 0;
        public Integer takeExitCount = 0;
        public Integer stopExitWinCount = 0;
        public Integer stopExitLossCount = 0;
        public Integer takeExitWinCount = 0;
        public Integer takeExitLossCount = 0;
        public BigDecimal winRate = BigDecimal.ZERO;
        public BigDecimal totalReturnPct = BigDecimal.ZERO;
        public BigDecimal avgReturnPct = BigDecimal.ZERO;
        public BigDecimal profitFactor = BigDecimal.ZERO;
        public BigDecimal maxDrawdownPct = BigDecimal.ZERO;
        public BigDecimal avgHoldBars = BigDecimal.ZERO;
        public List<TradeRecord> tradeList;
        public Map<String, Integer> rejectReasonCounts;
    }

    public static class TradeRecord {
        public String side;
        public String entryTime;
        public String exitTime;
        public BigDecimal entryPrice;
        public BigDecimal exitPrice;
        public BigDecimal stopPrice;
        public BigDecimal takePrice;
        public Integer holdBars;
        public String exitReason;
        public BigDecimal returnPct;
        public BigDecimal pnl;
    }

    public static class Position {
        public Side side;
        public double entryPrice;
        public String entryTime;
        public int entryIndex;
        public Double stopPrice;
        public Double takePrice;
        public int maxHoldBars;
        public int currentHoldBars;
    }

    public static class EquityContext {
        public double equity;
        public double peakEquity;
        public double totalPositiveReturnPct;
        public double totalNegativeReturnPct;
        public long totalHoldBars;
    }
}
