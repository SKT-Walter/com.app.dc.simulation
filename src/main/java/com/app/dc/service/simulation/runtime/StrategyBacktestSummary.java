package com.app.dc.service.simulation.runtime;

public class StrategyBacktestSummary {
    public String sid;
    public String strategyName;
    public String strategyVersion;
    public String runtimeType;
    public String scene;
    public String runTime;
    public String windowMode;
    public Integer sliceCount;
    public String optimizationMode;
    public Integer trialCount;
    public Integer bestRank;
    public String bestParamSetJson;
    public Double fitPnl;
    public Double validatePnl;
    public Double forwardPnl;
    public Double totalPnl;
    public Double forwardScore;
    public Double minForwardContribution;
    public Integer overfitPass;
    public String overfitReason;
    public Integer resultCount;
}
