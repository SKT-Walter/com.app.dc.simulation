package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期决策输出类型。
 */
public enum LifecycleDecisionType {
    NONE,
    PENDING_LONG_LAUNCH,
    PENDING_SHORT_LAUNCH,
    ENTER_LONG,
    LEAVE_LONG,
    ENTER_SHORT,
    LEAVE_SHORT
}
