package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期策略在回测过程中的内存状态。
 */
public class LifecycleState {

    private final String strategyName;
    private final String symbol;
    private final String text;
    private LifecyclePhase phase = LifecyclePhase.NEUTRAL;
    private LifecycleDirection direction = LifecycleDirection.NONE;
    private String lastBarTime = "";
    private String lastDecision = "";
    private String lastReason = "";

    /**
     * 初始化指定策略、品种和周期的状态。
     */
    public LifecycleState(String strategyName, String symbol, String text) {
        this.strategyName = strategyName;
        this.symbol = symbol;
        this.text = text;
    }

    /**
     * 获取状态所属策略名称。
     */
    public String getStrategyName() {
        return strategyName;
    }

    /**
     * 获取状态所属交易品种。
     */
    public String getSymbol() {
        return symbol;
    }

    /**
     * 获取状态所属K线周期。
     */
    public String getText() {
        return text;
    }

    /**
     * 获取当前生命周期阶段。
     */
    public LifecyclePhase getPhase() {
        return phase == null ? LifecyclePhase.NEUTRAL : phase;
    }

    /**
     * 设置当前生命周期阶段。
     */
    public void setPhase(LifecyclePhase phase) {
        this.phase = phase == null ? LifecyclePhase.NEUTRAL : phase;
    }

    /**
     * 获取当前趋势方向。
     */
    public LifecycleDirection getDirection() {
        return direction == null ? LifecycleDirection.NONE : direction;
    }

    /**
     * 设置当前趋势方向。
     */
    public void setDirection(LifecycleDirection direction) {
        this.direction = direction == null ? LifecycleDirection.NONE : direction;
    }

    /**
     * 获取最后处理过的K线时间。
     */
    public String getLastBarTime() {
        return lastBarTime == null ? "" : lastBarTime;
    }

    /**
     * 设置最后处理过的K线时间。
     */
    public void setLastBarTime(String lastBarTime) {
        this.lastBarTime = lastBarTime == null ? "" : lastBarTime;
    }

    /**
     * 获取最近一次决策类型。
     */
    public String getLastDecision() {
        return lastDecision == null ? "" : lastDecision;
    }

    /**
     * 设置最近一次决策类型。
     */
    public void setLastDecision(String lastDecision) {
        this.lastDecision = lastDecision == null ? "" : lastDecision;
    }

    /**
     * 获取最近一次决策原因。
     */
    public String getLastReason() {
        return lastReason == null ? "" : lastReason;
    }

    /**
     * 设置最近一次决策原因。
     */
    public void setLastReason(String lastReason) {
        this.lastReason = lastReason == null ? "" : lastReason;
    }

    /**
     * 判断当前是否处于多头生命周期。
     */
    public boolean inLong() {
        return getPhase() == LifecyclePhase.LONG_ACTIVE;
    }

    /**
     * 判断当前是否处于空头生命周期。
     */
    public boolean inShort() {
        return getPhase() == LifecyclePhase.SHORT_ACTIVE;
    }
}
