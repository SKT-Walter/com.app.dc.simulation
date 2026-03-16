package com.app.dc.service.simulation;

import com.app.dc.po.Side;

import java.math.BigDecimal;
import java.util.List;

/**
 * 币安回测模型定义。
 */
public final class BinanceBacktestModels {

    private BinanceBacktestModels() {
    }

    /**
     * 回测响应对象。
     */
    public static class BacktestResponse {
        /** 策略名或 all。 */
        public String strategyName;
        /** 交易对。 */
        public String symbol;
        /** 周期。 */
        public String text;
        /** 开始日期。 */
        public String beginDate;
        /** 结束日期。 */
        public String endDate;
        /** 回测结果集合。 */
        public List<BacktestResult> results;
    }

    /**
     * 单策略回测结果。
     */
    public static class BacktestResult {
        /** 策略名。 */
        public String strategyName;
        /** 交易对。 */
        public String symbol;
        /** 周期。 */
        public String text;
        /** 开始日期。 */
        public String beginDate;
        /** 结束日期。 */
        public String endDate;
        /** 初始资金。 */
        public BigDecimal initialCapital;
        /** 最终资金。 */
        public BigDecimal finalCapital;
        /** 手续费百分比。 */
        public BigDecimal feeRatePct;
        /** 兜底止损百分比。 */
        public BigDecimal fallbackStopLossPct;
        /** 兜底止盈百分比。 */
        public BigDecimal fallbackTakeProfitPct;
        /** 最大持仓 bar 数。 */
        public Integer maxHoldBars;
        /** 总 bar 数。 */
        public Integer totalBars = 0;
        /** 交易总数。 */
        public Integer tradeCount = 0;
        /** 盈利笔数。 */
        public Integer winCount = 0;
        /** 亏损笔数。 */
        public Integer lossCount = 0;
        /** 持平笔数。 */
        public Integer flatCount = 0;
        /** 胜率。 */
        public BigDecimal winRate = BigDecimal.ZERO;
        /** 总收益率。 */
        public BigDecimal totalReturnPct = BigDecimal.ZERO;
        /** 平均单笔收益率。 */
        public BigDecimal avgReturnPct = BigDecimal.ZERO;
        /** 盈亏比。 */
        public BigDecimal profitFactor = BigDecimal.ZERO;
        /** 最大回撤。 */
        public BigDecimal maxDrawdownPct = BigDecimal.ZERO;
        /** 平均持仓 bar 数。 */
        public BigDecimal avgHoldBars = BigDecimal.ZERO;
        /** 交易明细。 */
        public List<TradeRecord> tradeList;
    }

    /**
     * 单笔交易明细。
     */
    public static class TradeRecord {
        /** 方向。 */
        public String side;
        /** 开仓时间。 */
        public String entryTime;
        /** 平仓时间。 */
        public String exitTime;
        /** 开仓价。 */
        public BigDecimal entryPrice;
        /** 平仓价。 */
        public BigDecimal exitPrice;
        /** 止损价。 */
        public BigDecimal stopPrice;
        /** 止盈价。 */
        public BigDecimal takePrice;
        /** 持仓 bar 数。 */
        public Integer holdBars;
        /** 平仓原因。 */
        public String exitReason;
        /** 单笔收益率。 */
        public BigDecimal returnPct;
        /** 单笔盈亏金额。 */
        public BigDecimal pnl;
    }

    /**
     * 持仓对象，仅在回测过程中使用。
     */
    public static class Position {
        /** 持仓方向。 */
        public Side side;
        /** 开仓价。 */
        public double entryPrice;
        /** 开仓时间。 */
        public String entryTime;
        /** 开仓 bar 索引。 */
        public int entryIndex;
        /** 止损价。 */
        public Double stopPrice;
        /** 止盈价。 */
        public Double takePrice;
        /** 最大持仓 bar 数。 */
        public int maxHoldBars;
        /** 当前已持仓 bar 数。 */
        public int currentHoldBars;
    }

    /**
     * 回测过程中的权益统计。
     */
    public static class EquityContext {
        /** 当前权益。 */
        public double equity;
        /** 峰值权益。 */
        public double peakEquity;
        /** 正收益累计。 */
        public double totalPositiveReturnPct;
        /** 负收益累计绝对值。 */
        public double totalNegativeReturnPct;
        /** 总持仓 bar 数。 */
        public long totalHoldBars;
    }
}
