package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * difDeaLifecycle 极简策略在回测中的内存仓位状态。
 */
public class LifecycleState {

    private LifecyclePhase phase = LifecyclePhase.NEUTRAL;
    private LifecycleDirection direction = LifecycleDirection.NONE;
    private String lastBarTime = "";
    private String lastDecision = "";
    private String lastReason = "";
    private String entrySource = "";
    private int entryIndex = -1;
    private double entryPrice = Double.NaN;
    private double entrySignalHigh = Double.NaN;
    private double entrySignalLow = Double.NaN;
    private double entryMacdStrength = Double.NaN;
    private double protectiveStopPrice = Double.NaN;
    private String protectiveStopSource = "";
    private boolean profitExtensionActive;
    private int cooldownUntilIndex = -1;
    private LifecycleDirection pendingEntryDirection = LifecycleDirection.NONE;
    private int pendingEntryIndex = -1;
    private double pendingEntryClose = Double.NaN;
    private double pendingEntryHigh = Double.NaN;
    private double pendingEntryLow = Double.NaN;
    private double pendingEntryDifDeaGap = Double.NaN;
    private double pendingEntryMacdBar = Double.NaN;
    private LifecycleDirection pendingReverseDirection = LifecycleDirection.NONE;
    private int pendingReverseIndex = -1;
    private double pendingReverseClose = Double.NaN;
    private double pendingReverseHigh = Double.NaN;
    private double pendingReverseLow = Double.NaN;
    private double pendingReverseDifDeaGap = Double.NaN;
    private double pendingReverseMacdBar = Double.NaN;
    private String pendingReverseSource = "";

    /**
     * 判断当前是否多头持仓。
     */
    public boolean inLong() { return getPhase() == LifecyclePhase.LONG_ACTIVE; }

    /**
     * 判断当前是否空头持仓。
     */
    public boolean inShort() { return getPhase() == LifecyclePhase.SHORT_ACTIVE; }

    /**
     * 判断当前是否存在待确认入场。
     */
    public boolean hasPendingEntry() {
        return getPendingEntryDirection() == LifecycleDirection.LONG
                || getPendingEntryDirection() == LifecycleDirection.SHORT;
    }

    /**
     * 判断当前是否存在待确认反手信号。
     */
    public boolean hasPendingReverse() {
        return getPendingReverseDirection() == LifecycleDirection.LONG
                || getPendingReverseDirection() == LifecycleDirection.SHORT;
    }

    /**
     * 切换为空仓状态。
     */
    public void toNeutral() {
        phase = LifecyclePhase.NEUTRAL;
        direction = LifecycleDirection.NONE;
        entrySource = "";
        entryIndex = -1;
        entryPrice = Double.NaN;
        clearProtectiveStop();
        clearProfitExtension();
        clearEarlyFailureGuard();
        clearPendingEntry();
    }

    /**
     * 保存确认入场对应的信号K线边界和入场MACD动能。
     */
    public void armEarlyFailureGuard(LifecycleDirection direction, double signalHigh, double signalLow,
                                     double macdBar) {
        entrySignalHigh = signalHigh;
        entrySignalLow = signalLow;
        entryMacdStrength = direction == LifecycleDirection.SHORT ? -macdBar : macdBar;
    }

    /**
     * 清理新仓早期趋势失败观察数据。
     */
    public void clearEarlyFailureGuard() {
        entrySignalHigh = Double.NaN;
        entrySignalLow = Double.NaN;
        entryMacdStrength = Double.NaN;
    }

    /**
     * 记录待确认入场信号K线。
     */
    public void startPendingEntry(LifecycleDirection direction, LifecycleIndicatorSample sample) {
        pendingEntryDirection = direction == null ? LifecycleDirection.NONE : direction;
        pendingEntryIndex = sample == null ? -1 : sample.getIndex();
        pendingEntryClose = sample == null ? Double.NaN : sample.getClose();
        pendingEntryHigh = sample == null ? Double.NaN : sample.getHigh();
        pendingEntryLow = sample == null ? Double.NaN : sample.getLow();
        pendingEntryMacdBar = sample == null ? Double.NaN : sample.getMacdBar();
        if (sample == null) {
            pendingEntryDifDeaGap = Double.NaN;
        } else if (pendingEntryDirection == LifecycleDirection.SHORT) {
            pendingEntryDifDeaGap = sample.getDea() - sample.getDif();
        } else {
            pendingEntryDifDeaGap = sample.getDif() - sample.getDea();
        }
    }

    /**
     * 清理待确认入场信号。
     */
    public void clearPendingEntry() {
        pendingEntryDirection = LifecycleDirection.NONE;
        pendingEntryIndex = -1;
        pendingEntryClose = Double.NaN;
        pendingEntryHigh = Double.NaN;
        pendingEntryLow = Double.NaN;
        pendingEntryDifDeaGap = Double.NaN;
        pendingEntryMacdBar = Double.NaN;
    }

    /**
     * 记录待确认反手信号K线。
     */
    public void startPendingReverse(LifecycleDirection direction, LifecycleIndicatorSample sample, String source) {
        pendingReverseDirection = direction == null ? LifecycleDirection.NONE : direction;
        pendingReverseIndex = sample == null ? -1 : sample.getIndex();
        pendingReverseClose = sample == null ? Double.NaN : sample.getClose();
        pendingReverseHigh = sample == null ? Double.NaN : sample.getHigh();
        pendingReverseLow = sample == null ? Double.NaN : sample.getLow();
        pendingReverseMacdBar = sample == null ? Double.NaN : sample.getMacdBar();
        pendingReverseSource = source == null ? "" : source;
        if (sample == null) {
            pendingReverseDifDeaGap = Double.NaN;
        } else if (pendingReverseDirection == LifecycleDirection.SHORT) {
            pendingReverseDifDeaGap = sample.getDea() - sample.getDif();
        } else {
            pendingReverseDifDeaGap = sample.getDif() - sample.getDea();
        }
    }

    /**
     * 清理待确认反手信号。
     */
    public void clearPendingReverse() {
        pendingReverseDirection = LifecycleDirection.NONE;
        pendingReverseIndex = -1;
        pendingReverseClose = Double.NaN;
        pendingReverseHigh = Double.NaN;
        pendingReverseLow = Double.NaN;
        pendingReverseDifDeaGap = Double.NaN;
        pendingReverseMacdBar = Double.NaN;
        pendingReverseSource = "";
    }

    /**
     * 获取当前阶段。
     */
    public LifecyclePhase getPhase() { return phase == null ? LifecyclePhase.NEUTRAL : phase; }

    /**
     * 设置当前阶段。
     */
    public void setPhase(LifecyclePhase phase) { this.phase = phase == null ? LifecyclePhase.NEUTRAL : phase; }

    /**
     * 获取当前方向。
     */
    public LifecycleDirection getDirection() { return direction == null ? LifecycleDirection.NONE : direction; }

    /**
     * 设置当前方向。
     */
    public void setDirection(LifecycleDirection direction) {
        this.direction = direction == null ? LifecycleDirection.NONE : direction;
    }

    /**
     * 获取最后处理K线时间。
     */
    public String getLastBarTime() { return lastBarTime == null ? "" : lastBarTime; }

    /**
     * 设置最后处理K线时间。
     */
    public void setLastBarTime(String lastBarTime) { this.lastBarTime = lastBarTime == null ? "" : lastBarTime; }

    /**
     * 获取最后决策类型。
     */
    public String getLastDecision() { return lastDecision == null ? "" : lastDecision; }

    /**
     * 设置最后决策类型。
     */
    public void setLastDecision(String lastDecision) {
        this.lastDecision = lastDecision == null ? "" : lastDecision;
    }

    /**
     * 获取最后决策原因。
     */
    public String getLastReason() { return lastReason == null ? "" : lastReason; }

    /**
     * 设置最后决策原因。
     */
    public void setLastReason(String lastReason) { this.lastReason = lastReason == null ? "" : lastReason; }

    /**
     * 获取当前持仓来源。
     */
    public String getEntrySource() { return entrySource == null ? "" : entrySource; }

    /**
     * 设置当前持仓来源。
     */
    public void setEntrySource(String entrySource) { this.entrySource = entrySource == null ? "" : entrySource; }

    /**
     * 获取入场样本序号。
     */
    public int getEntryIndex() { return entryIndex; }

    /**
     * 设置入场样本序号。
     */
    public void setEntryIndex(int entryIndex) { this.entryIndex = entryIndex; }

    /**
     * 获取入场价。
     */
    public double getEntryPrice() { return entryPrice; }

    /**
     * 设置入场价。
     */
    public void setEntryPrice(double entryPrice) { this.entryPrice = entryPrice; }

    /**
     * 获取入场信号K线最高价。
     */
    public double getEntrySignalHigh() { return entrySignalHigh; }

    /**
     * 获取入场信号K线最低价。
     */
    public double getEntrySignalLow() { return entrySignalLow; }

    /**
     * 获取确认入场K线的方向性MACD动能。
     */
    public double getEntryMacdStrength() { return entryMacdStrength; }

    /**
     * 获取当前仓位的保护止损价。
     */
    public double getProtectiveStopPrice() { return protectiveStopPrice; }

    /**
     * 获取当前仓位的保护止损来源。
     */
    public String getProtectiveStopSource() {
        return protectiveStopSource == null ? "" : protectiveStopSource;
    }

    /**
     * 设置当前仓位的保护止损。
     */
    public void setProtectiveStop(double stopPrice, String source) {
        protectiveStopPrice = stopPrice;
        protectiveStopSource = source == null ? "" : source;
    }

    /**
     * 清除当前仓位的保护止损。
     */
    public void clearProtectiveStop() {
        protectiveStopPrice = Double.NaN;
        protectiveStopSource = "";
    }

    /**
     * 获取短持仓反复交叉后的冷却截止样本序号。
     */
    public int getCooldownUntilIndex() { return cooldownUntilIndex; }

    /**
     * 设置短持仓反复交叉后的冷却截止样本序号。
     */
    public void setCooldownUntilIndex(int cooldownUntilIndex) { this.cooldownUntilIndex = cooldownUntilIndex; }

    /**
     * 获取待确认入场方向。
     */
    public LifecycleDirection getPendingEntryDirection() {
        return pendingEntryDirection == null ? LifecycleDirection.NONE : pendingEntryDirection;
    }

    /**
     * 获取待确认入场信号K线序号。
     */
    public int getPendingEntryIndex() { return pendingEntryIndex; }

    /**
     * 获取待确认入场信号K线收盘价。
     */
    public double getPendingEntryClose() { return pendingEntryClose; }

    /**
     * 获取待确认入场信号K线最高价。
     */
    public double getPendingEntryHigh() { return pendingEntryHigh; }

    /**
     * 获取待确认入场信号K线最低价。
     */
    public double getPendingEntryLow() { return pendingEntryLow; }

    /**
     * 获取待确认入场信号K线DIF/DEA张口。
     */
    public double getPendingEntryDifDeaGap() { return pendingEntryDifDeaGap; }

    /**
     * 获取待确认入场信号K线MACD柱。
     */
    public double getPendingEntryMacdBar() { return pendingEntryMacdBar; }

    /**
     * 获取待确认反手方向。
     */
    public LifecycleDirection getPendingReverseDirection() {
        return pendingReverseDirection == null ? LifecycleDirection.NONE : pendingReverseDirection;
    }

    /**
     * 获取待确认反手信号K线序号。
     */
    public int getPendingReverseIndex() { return pendingReverseIndex; }

    /**
     * 获取待确认反手信号K线收盘价。
     */
    public double getPendingReverseClose() { return pendingReverseClose; }

    /**
     * 获取待确认反手信号K线最高价。
     */
    public double getPendingReverseHigh() { return pendingReverseHigh; }

    /**
     * 获取待确认反手信号K线最低价。
     */
    public double getPendingReverseLow() { return pendingReverseLow; }

    /**
     * 获取待确认反手信号K线DIF/DEA张口。
     */
    public double getPendingReverseDifDeaGap() { return pendingReverseDifDeaGap; }

    /**
     * 获取待确认反手信号K线MACD柱。
     */
    public double getPendingReverseMacdBar() { return pendingReverseMacdBar; }

    /**
     * 获取待确认反手来源。
     */
    public String getPendingReverseSource() { return pendingReverseSource == null ? "" : pendingReverseSource; }

    /**
     * 判断当前仓位是否处于成熟盈利延续保护中。
     */
    public boolean isProfitExtensionActive() { return profitExtensionActive; }

    /**
     * 启用成熟盈利延续保护。
     */
    public void startProfitExtension() { profitExtensionActive = true; }

    /**
     * 清理成熟盈利延续保护状态。
     */
    public void clearProfitExtension() { profitExtensionActive = false; }

}
