package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期策略的单次决策结果。
 */
public class LifecycleDecision {

    private final LifecycleDecisionType type;
    private final String reason;
    private final boolean reverseEntryAllowed;
    private final String entrySource;

    /**
     * 创建无动作决策。
     */
    public static LifecycleDecision none(String reason) {
        return new LifecycleDecision(LifecycleDecisionType.NONE, reason, false, "");
    }

    /**
     * 创建默认允许反手的动作决策。
     */
    public static LifecycleDecision of(LifecycleDecisionType type, String reason) {
        return new LifecycleDecision(type, reason, true, reason);
    }

    /**
     * 创建可显式控制反手的动作决策。
     */
    public static LifecycleDecision of(LifecycleDecisionType type, String reason, boolean reverseEntryAllowed) {
        return new LifecycleDecision(type, reason, reverseEntryAllowed, reason);
    }

    /**
     * 创建带入场来源的动作决策。
     */
    public static LifecycleDecision entry(LifecycleDecisionType type, String reason, String entrySource) {
        return new LifecycleDecision(type, reason, true, entrySource);
    }

    /**
     * 初始化决策对象。
     */
    private LifecycleDecision(LifecycleDecisionType type, String reason, boolean reverseEntryAllowed,
                              String entrySource) {
        this.type = type == null ? LifecycleDecisionType.NONE : type;
        this.reason = reason == null ? "" : reason;
        this.reverseEntryAllowed = reverseEntryAllowed;
        this.entrySource = entrySource == null ? "" : entrySource;
    }

    /**
     * 获取决策类型。
     */
    public LifecycleDecisionType getType() {
        return type;
    }

    /**
     * 获取决策原因。
     */
    public String getReason() {
        return reason;
    }

    /**
     * 判断平仓后是否允许立即反手。
     */
    public boolean isReverseEntryAllowed() {
        return reverseEntryAllowed;
    }

    /**
     * 获取开仓来源。
     */
    public String getEntrySource() {
        return entrySource;
    }

    /**
     * 判断是否需要输出交易信号。
     */
    public boolean hasAction() {
        return type == LifecycleDecisionType.ENTER_LONG
                || type == LifecycleDecisionType.ENTER_SHORT
                || type == LifecycleDecisionType.LEAVE_LONG
                || type == LifecycleDecisionType.LEAVE_SHORT;
    }
}
