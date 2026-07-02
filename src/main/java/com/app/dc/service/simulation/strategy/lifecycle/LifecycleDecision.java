package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期策略的单次决策结果。
 */
public class LifecycleDecision {

    private final LifecycleDecisionType type;
    private final String reason;
    private final boolean reverseEntryAllowed;

    /**
     * 创建无动作决策。
     */
    public static LifecycleDecision none(String reason) {
        return new LifecycleDecision(LifecycleDecisionType.NONE, reason, false);
    }

    /**
     * 创建指定动作类型的决策。
     */
    public static LifecycleDecision of(LifecycleDecisionType type, String reason) {
        return new LifecycleDecision(type, reason, true);
    }

    /**
     * 创建带反手开仓许可的决策。
     */
    public static LifecycleDecision of(LifecycleDecisionType type, String reason, boolean reverseEntryAllowed) {
        return new LifecycleDecision(type, reason, reverseEntryAllowed);
    }

    /**
     * 初始化决策对象。
     */
    private LifecycleDecision(LifecycleDecisionType type, String reason, boolean reverseEntryAllowed) {
        this.type = type == null ? LifecycleDecisionType.NONE : type;
        this.reason = reason == null ? "" : reason;
        this.reverseEntryAllowed = reverseEntryAllowed;
    }

    /**
     * 获取决策动作类型。
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
     * 判断反向平仓后是否允许立即按新方向开仓。
     */
    public boolean isReverseEntryAllowed() {
        return reverseEntryAllowed;
    }

    /**
     * 判断本次决策是否需要发出买卖动作。
     */
    public boolean hasAction() {
        return type == LifecycleDecisionType.ENTER_LONG
                || type == LifecycleDecisionType.LEAVE_LONG
                || type == LifecycleDecisionType.ENTER_SHORT
                || type == LifecycleDecisionType.LEAVE_SHORT;
    }
}
