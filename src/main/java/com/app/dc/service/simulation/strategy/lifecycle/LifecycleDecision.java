package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期策略的单次决策结果，包含动作类型和触发原因。
 */
public class LifecycleDecision {

    /**
     * 创建无动作决策，并记录拒绝或等待原因。
     */
    public static LifecycleDecision none(String reason) {
        return new LifecycleDecision(LifecycleDecisionType.NONE, reason);
    }

    /**
     * 创建有动作决策，并记录触发原因。
     */
    public static LifecycleDecision of(LifecycleDecisionType type, String reason) {
        return new LifecycleDecision(type, reason);
    }

    private final LifecycleDecisionType type;
    private final String reason;

    /**
     * 初始化生命周期决策对象。
     */
    private LifecycleDecision(LifecycleDecisionType type, String reason) {
        this.type = type == null ? LifecycleDecisionType.NONE : type;
        this.reason = reason == null ? "" : reason;
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
     * 判断本次决策是否需要发出买卖动作。
     */
    public boolean hasAction() {
        return type != LifecycleDecisionType.NONE;
    }
}
