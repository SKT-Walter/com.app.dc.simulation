package com.app.dc.po.backtest;

import java.math.BigDecimal;

public class BacktestParam {

    public String strategyName;

    public String symbol;

    public String symbols;

    public String text;

    public String beginDate;

    public String endDate;

    public BigDecimal initialCapital;

    public BigDecimal feeRatePct;

    public BigDecimal fallbackStopLossPct;

    public BigDecimal fallbackTakeProfitPct;

    public Integer maxHoldBars;

    public Boolean ignoreSentimentGuard = true;
}
