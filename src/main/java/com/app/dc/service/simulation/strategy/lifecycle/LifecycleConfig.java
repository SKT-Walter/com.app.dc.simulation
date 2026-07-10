package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * difDeaLifecycle 极简金叉死叉策略的默认配置。
 */
public class LifecycleConfig {

    private final int warmupBars = 35;
    private final double largeBarRangePct = 0.8d;
    private final int noReverseHoldBars = 8;
    private final int whipsawCooldownBars = 3;
    private final int crossDensityLookbackBars = 12;
    private final int crossDensityBlockCount = 2;
    private final int pendingEntryMaxBars = 1;

    /**
     * 获取预热K线数量。
     */
    public int getWarmupBars() { return warmupBars; }

    /**
     * 获取单根大波幅过滤阈值。
     */
    public double getLargeBarRangePct() { return largeBarRangePct; }

    /**
     * 获取短持仓禁止反手的K线数量。
     */
    public int getNoReverseHoldBars() { return noReverseHoldBars; }

    /**
     * 获取短持仓反复交叉后的冷却K线数量。
     */
    public int getWhipsawCooldownBars() { return whipsawCooldownBars; }

    /**
     * 获取交叉密度过滤的回看K线数量。
     */
    public int getCrossDensityLookbackBars() { return crossDensityLookbackBars; }

    /**
     * 获取交叉密度过滤的阻断次数阈值。
     */
    public int getCrossDensityBlockCount() { return crossDensityBlockCount; }

    /**
     * 获取交叉入场最多等待确认K线数量。
     */
    public int getPendingEntryMaxBars() { return pendingEntryMaxBars; }
}
