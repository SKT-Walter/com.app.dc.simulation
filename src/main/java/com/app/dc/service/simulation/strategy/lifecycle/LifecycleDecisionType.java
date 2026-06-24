package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期决策输出类型，用于映射回测买卖信号。
 */
public enum LifecycleDecisionType {
    NONE,
    ENTER_LONG,
    LEAVE_LONG,
    ENTER_SHORT,
    LEAVE_SHORT
}
