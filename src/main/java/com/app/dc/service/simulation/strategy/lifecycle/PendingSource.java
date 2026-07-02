package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 标记当前 pending 观察态是由哪类原因触发的。
 */
public enum PendingSource {

    NONE,
    LOW_MACD_ENTRY,
    OTHER
}
