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
     * 当前 pending 观察已经持续的 K 线数量。
     */
    private int pendingBars;
    /**
     * 当前 pending 观察期间出现过的最高 close。
     */
    private double pendingHighClose = Double.NaN;
    /**
     * 当前 pending 观察期间出现过的最低 close。
     */
    private double pendingLowClose = Double.NaN;
    /**
     * 当前 pending 区间是否已经完成建区并冻结。
     */
    private boolean pendingRangeReady;
    /**
     * 当前 pending 建区窗口内实体上沿的最高值。
     */
    private double pendingUpperBodyBound = Double.NaN;
    /**
     * 当前 pending 建区窗口内实体下沿的最低值。
     */
    private double pendingLowerBodyBound = Double.NaN;
    /**
     * 标记当前 pending 观察态的触发来源。
     */
    private PendingSource pendingSource = PendingSource.NONE;

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
     * 获取状态所属 K 线周期。
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
     * 获取最后处理过的 K 线时间。
     */
    public String getLastBarTime() {
        return lastBarTime == null ? "" : lastBarTime;
    }

    /**
     * 设置最后处理过的 K 线时间。
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

    /**
     * 判断当前是否处于多头补开仓观察态。
     */
    public boolean inPendingLong() {
        return getPhase() == LifecyclePhase.PENDING_LONG_LAUNCH;
    }

    /**
     * 判断当前是否处于空头补开仓观察态。
     */
    public boolean inPendingShort() {
        return getPhase() == LifecyclePhase.PENDING_SHORT_LAUNCH;
    }

    /**
     * 判断当前是否处于任一补开仓观察态。
     */
    public boolean inPendingLaunch() {
        return inPendingLong() || inPendingShort();
    }

    /**
     * 返回当前 pending 已观察的 K 线数量。
     */
    public int getPendingBars() {
        return pendingBars;
    }

    /**
     * 设置当前 pending 已观察的 K 线数量。
     */
    public void setPendingBars(int pendingBars) {
        this.pendingBars = pendingBars;
    }

    /**
     * 返回当前 pending 观察期间的最高 close。
     */
    public double getPendingHighClose() {
        return pendingHighClose;
    }

    /**
     * 设置当前 pending 观察期间的最高 close。
     */
    public void setPendingHighClose(double pendingHighClose) {
        this.pendingHighClose = pendingHighClose;
        if (Double.isNaN(this.pendingUpperBodyBound)) {
            this.pendingUpperBodyBound = pendingHighClose;
        }
        if (inPendingLaunch()) {
            this.pendingRangeReady = true;
        }
    }

    /**
     * 返回当前 pending 观察期间的最低 close。
     */
    public double getPendingLowClose() {
        return pendingLowClose;
    }

    /**
     * 设置当前 pending 观察期间的最低 close。
     */
    public void setPendingLowClose(double pendingLowClose) {
        this.pendingLowClose = pendingLowClose;
        if (Double.isNaN(this.pendingLowerBodyBound)) {
            this.pendingLowerBodyBound = pendingLowClose;
        }
        if (inPendingLaunch()) {
            this.pendingRangeReady = true;
        }
    }

    /**
     * 返回当前 pending 区间是否已经建好并冻结。
     */
    public boolean isPendingRangeReady() {
        return pendingRangeReady;
    }

    /**
     * 设置当前 pending 区间是否已经建好并冻结。
     */
    public void setPendingRangeReady(boolean pendingRangeReady) {
        this.pendingRangeReady = pendingRangeReady;
    }

    /**
     * 返回当前 pending 建区窗口内的实体上沿边界。
     */
    public double getPendingUpperBodyBound() {
        return pendingUpperBodyBound;
    }

    /**
     * 设置当前 pending 建区窗口内的实体上沿边界。
     */
    public void setPendingUpperBodyBound(double pendingUpperBodyBound) {
        this.pendingUpperBodyBound = pendingUpperBodyBound;
    }

    /**
     * 返回当前 pending 建区窗口内的实体下沿边界。
     */
    public double getPendingLowerBodyBound() {
        return pendingLowerBodyBound;
    }

    /**
     * 设置当前 pending 建区窗口内的实体下沿边界。
     */
    public void setPendingLowerBodyBound(double pendingLowerBodyBound) {
        this.pendingLowerBodyBound = pendingLowerBodyBound;
    }

    /**
     * 返回当前 pending 观察态的触发来源。
     */
    public PendingSource getPendingSource() {
        return pendingSource == null ? PendingSource.NONE : pendingSource;
    }

    /**
     * 设置当前 pending 观察态的触发来源。
     */
    public void setPendingSource(PendingSource pendingSource) {
        this.pendingSource = pendingSource == null ? PendingSource.NONE : pendingSource;
    }

    /**
     * 使用最新 close 更新 pending 观察区间。
     */
    public void updatePendingCloseRange(double close) {
        if (Double.isNaN(close)) {
            return;
        }
        if (Double.isNaN(pendingHighClose) || close > pendingHighClose) {
            pendingHighClose = close;
        }
        if (Double.isNaN(pendingLowClose) || close < pendingLowClose) {
            pendingLowClose = close;
        }
    }

    /**
     * 使用当前 K 线的实体高低更新 pending 建区边界。
     */
    public void updatePendingBodyRange(LifecycleIndicatorSample sample) {
        if (sample == null) {
            return;
        }
        double bodyTop = Math.max(sample.getOpen(), sample.getClose());
        double bodyBottom = Math.min(sample.getOpen(), sample.getClose());
        if (Double.isNaN(pendingUpperBodyBound) || bodyTop > pendingUpperBodyBound) {
            pendingUpperBodyBound = bodyTop;
        }
        if (Double.isNaN(pendingLowerBodyBound) || bodyBottom < pendingLowerBodyBound) {
            pendingLowerBodyBound = bodyBottom;
        }
    }

    /**
     * 用进入 pending 的当根 K 线启动建区。
     */
    public void startPendingRange(LifecycleIndicatorSample sample, int buildBars) {
        clearPending();
        pendingBars = 1;
        updatePendingCloseRange(sample == null ? Double.NaN : sample.getClose());
        updatePendingBodyRange(sample);
        pendingRangeReady = buildBars <= 1;
    }

    /**
     * 用后续 K 线延续 pending 观察和建区。
     */
    public void continuePendingRange(LifecycleIndicatorSample sample, int buildBars) {
        pendingBars++;
        updatePendingCloseRange(sample == null ? Double.NaN : sample.getClose());
        if (!pendingRangeReady) {
            updatePendingBodyRange(sample);
            if (pendingBars >= buildBars) {
                pendingRangeReady = true;
            }
        }
    }

    /**
     * 清理 pending 观察状态。
     */
    public void clearPending() {
        pendingBars = 0;
        pendingHighClose = Double.NaN;
        pendingLowClose = Double.NaN;
        pendingRangeReady = false;
        pendingUpperBodyBound = Double.NaN;
        pendingLowerBodyBound = Double.NaN;
        pendingSource = PendingSource.NONE;
    }
}
