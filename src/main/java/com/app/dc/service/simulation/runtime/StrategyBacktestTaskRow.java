package com.app.dc.service.simulation.runtime;

public class StrategyBacktestTaskRow {
    public String id;
    public String strategyName;
    public String strategyVersion;
    public String baselineVersion;
    public String runtimeType;
    public String taskType;
    public Integer fitWindowDays;
    public Integer validateWindowDays;
    public Integer forwardWindowDays;
    public Integer priority;
    public String status;
    public String suspendReason;
    public String nextRetryTime;
    public Integer attemptCount;
    public String createTime;
    public String updateTime;
    public String payload;
}
