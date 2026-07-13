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
    private final int pendingReverseMaxBars = 1;
    private final double minBreakoutConfirmRatio = 0.25d;
    private final int profitableReverseFilterHoldBars = 20;
    private final double profitableReverseFilterMinProfitPct = 0.3d;
    private final double profitableReverseDifDeaGapMin = 0.12d;

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

    /**
     * 获取反手入场最多等待确认K线数量。
     */
    public int getPendingReverseMaxBars() { return pendingReverseMaxBars; }

    /**
     * 获取确认K线相对信号K线波幅的最小突破比例。
     */
    public double getMinBreakoutConfirmRatio() { return minBreakoutConfirmRatio; }

    /**
     * 获取盈利长单反手过滤的最小持仓K线数量。
     */
    public int getProfitableReverseFilterHoldBars() { return profitableReverseFilterHoldBars; }

    /**
     * 获取盈利长单反手过滤的最小浮盈百分比。
     */
    public double getProfitableReverseFilterMinProfitPct() { return profitableReverseFilterMinProfitPct; }

    /**
     * 获取盈利长单弱反向交叉的DIF/DEA最小张口。
     */
    public double getProfitableReverseDifDeaGapMin() { return profitableReverseDifDeaGapMin; }
}
