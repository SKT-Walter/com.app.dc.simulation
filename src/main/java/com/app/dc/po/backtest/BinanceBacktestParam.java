package com.app.dc.po.backtest;

import java.math.BigDecimal;

/**
 * Binance backtest request params.
 */
public class BinanceBacktestParam {

    /** strategy name: binanceRange/binanceChannel/binanceTrend/range/channel/trend/all */
    public String strategyName;

    /** symbol, e.g. ETHUSDT */
    public String symbol;

    /** timeframe, e.g. 15m/1h/1d */
    public String text;

    /** begin date, format yyyy-MM-dd */
    public String beginDate;

    /** end date, format yyyy-MM-dd */
    public String endDate;

    /** initial capital */
    public BigDecimal initialCapital;

    /** fee rate percent per side, e.g. 0.04 = 0.04% */
    public BigDecimal feeRatePct;

    /** fallback stop-loss percent when strategy has no stop price */
    public BigDecimal fallbackStopLossPct;

    /** fallback take-profit percent when strategy has no taker price */
    public BigDecimal fallbackTakeProfitPct;

    /** max holding bars, 0 means unlimited */
    public Integer maxHoldBars;

    /**
     * ignore sentiment guard in backtest.
     * true: ignore sentiment block (default), only stage guard works.
     * false: enable sentiment block.
     */
    public Boolean ignoreSentimentGuard = true;
}
