package com.app.dc.po.backtest;

import java.math.BigDecimal;

/**
 * 币安策略回测请求参数。
 */
public class BinanceBacktestParam {

    /**
     * 策略名。
     * 可选值：binanceRange、binanceChannel、binanceTrend、range、channel、trend、all。
     */
    public String strategyName;

    /**
     * 交易对，例如 ETHUSDT。
     */
    public String symbol;

    /**
     * K 线周期，例如 15m、1h、1d。
     */
    public String text;

    /**
     * 开始日期，格式建议 yyyy-MM-dd。
     */
    public String beginDate;

    /**
     * 结束日期，格式建议 yyyy-MM-dd。
     */
    public String endDate;

    /**
     * 初始资金。
     */
    public BigDecimal initialCapital;

    /**
     * 单边手续费百分比，例如 0.04 表示 0.04%。
     */
    public BigDecimal feeRatePct;

    /**
     * 兜底止损百分比。
     * 当策略自身没有给出 stopPrice 时使用。
     */
    public BigDecimal fallbackStopLossPct;

    /**
     * 兜底止盈百分比。
     * 当策略自身没有给出 takerPrice 时使用。
     */
    public BigDecimal fallbackTakeProfitPct;

    /**
     * 最大持仓 bar 数。
     * 传 0 表示不限制。
     */
    public Integer maxHoldBars;
}
