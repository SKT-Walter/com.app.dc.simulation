package com.app.dc.po.backtest;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class BacktestParam {

    public String strategyName;

    public String strategyVersion;

    public String baselineVersion;

    public String runtimeType;

    public String scene;

    public String strategyPayload;

    /**
     * Optional task-scoped optimizer objective. When blank, the candidate's
     * optimization profile remains authoritative.
     */
    public String optimizationObjective;

    public Map<String, Object> strategyParams = new LinkedHashMap<String, Object>();

    public String symbol;

    public String symbols;

    public String text;

    public String beginDate;

    public String endDate;

    public BigDecimal initialCapital;

    public BigDecimal feeRatePct;

    public BigDecimal entryMakerFeeRatePct;

    public BigDecimal exitTakerFeeRatePct;

    public BigDecimal fallbackStopLossPct;

    public BigDecimal fallbackTakeProfitPct;

    public Integer maxHoldBars;

    public Boolean ignoreSentimentGuard = true;

    public Boolean allowMissingStageAnalysis = true;
}
