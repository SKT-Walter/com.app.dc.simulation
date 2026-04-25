package com.app.dc.service.simulation.runtime;

import com.app.dc.po.backtest.BacktestParam;

import java.util.Map;

public class StrategyBacktestTaskPayloadEnvelope {
    public BacktestParam backtestParam;
    public Map<String, Object> suspendDetail;
    public Map<String, Object> recoveryPlan;
}
