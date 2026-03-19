package com.app.dc.po.backtest;

import java.math.BigDecimal;

/**
 * 币安回测请求参数。
 */
public class BinanceBacktestParam {

    /** 策略名：binanceRange/binanceChannel/binanceTrend/range/channel/trend/all */
    public String strategyName;

    /** 交易对，例如 ETHUSDT */
    public String symbol;

    /** 周期，例如 15m/1h/1d */
    public String text;

    /** 开始日期，格式 yyyy-MM-dd */
    public String beginDate;

    /** 结束日期，格式 yyyy-MM-dd */
    public String endDate;

    /** 初始资金 */
    public BigDecimal initialCapital;

    /** 单边手续费百分比，例如 0.04 表示 0.04% */
    public BigDecimal feeRatePct;

    /** 策略未给止损价时使用的兜底止损百分比 */
    public BigDecimal fallbackStopLossPct;

    /** 策略未给止盈价时使用的兜底止盈百分比 */
    public BigDecimal fallbackTakeProfitPct;

    /** 最大持仓 K 线数，0 表示不限制 */
    public Integer maxHoldBars;

    /**
     * 回测时是否忽略舆情过滤。
     * true：忽略舆情过滤（默认），仅保留阶段过滤。
     * false：启用舆情过滤。
     */
    public Boolean ignoreSentimentGuard = true;
}
