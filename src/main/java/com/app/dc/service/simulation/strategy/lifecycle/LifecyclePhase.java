package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期策略当前所处阶段。
 */
public enum LifecyclePhase {
    NEUTRAL,
    PENDING_LONG_LAUNCH,
    PENDING_SHORT_LAUNCH,
    LONG_ACTIVE,
    SHORT_ACTIVE
}
