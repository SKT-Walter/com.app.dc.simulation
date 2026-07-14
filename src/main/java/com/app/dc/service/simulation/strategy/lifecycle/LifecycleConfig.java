package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * difDeaLifecycle 极简金叉死叉策略的默认配置。
 */
public class LifecycleConfig {

    private final int warmupBars = 56;
    private final double largeBarRangePct = 0.8d;
    private final int noReverseHoldBars = 8;
    private final int whipsawCooldownBars = 3;
    private final int crossDensityLookbackBars = 12;
    private final int crossDensityBlockCount = 2;
    private final int pendingEntryMaxBars = 1;
    private final int pendingReverseMaxBars = 1;
    private final double minBreakoutConfirmRatio = 0.25d;
    private final double overextendedConfirmBarRangePct = 0.60d;
    private final double overextendedConfirmBreakoutRatio = 1.50d;
    private final int earlyFailureMaxHoldBars = 8;
    private final double earlyFailureMacdRetentionRatio = 0.60d;
    private final int nonLaunchCheckHoldBars = 8;
    private final double nonLaunchMinFavorableProgressPct = 0.20d;
    private final double nonLaunchZeroProgressPct = 0.10d;
    private final double checkpointGivebackMinFavorablePct = 0.20d;
    private final double checkpointGivebackMacdRetentionRatio = 0.80d;
    private final int checkpointGivebackMaxHoldBars = 12;
    private final int ma20TrendLookbackBars = 36;
    private final double ma20TrendThresholdPct = 0.10d;
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
     * 获取确认K线过度延伸过滤的最小波幅百分比。
     */
    public double getOverextendedConfirmBarRangePct() { return overextendedConfirmBarRangePct; }

    /**
     * 获取确认K线过度延伸过滤的最小相对突破比例。
     */
    public double getOverextendedConfirmBreakoutRatio() { return overextendedConfirmBreakoutRatio; }

    /**
     * 获取新仓早期趋势失败观察的最大持仓K线数量。
     */
    public int getEarlyFailureMaxHoldBars() { return earlyFailureMaxHoldBars; }

    /**
     * 获取早期趋势失败允许保留的MACD动能比例。
     */
    public double getEarlyFailureMacdRetentionRatio() { return earlyFailureMacdRetentionRatio; }

    /**
     * 获取趋势未启动检查的持仓K线数量。
     */
    public int getNonLaunchCheckHoldBars() { return nonLaunchCheckHoldBars; }

    /**
     * 获取趋势视为有效启动所需的最小方向浮盈百分比。
     */
    public double getNonLaunchMinFavorableProgressPct() { return nonLaunchMinFavorableProgressPct; }

    /**
     * 获取第八根零推进退出允许的最大方向浮盈百分比。
     */
    public double getNonLaunchZeroProgressPct() { return nonLaunchZeroProgressPct; }

    /**
     * 获取第八根趋势回吐检查要求的最小方向浮盈百分比。
     */
    public double getCheckpointGivebackMinFavorablePct() { return checkpointGivebackMinFavorablePct; }

    /**
     * 获取第八根趋势回吐检查允许的最大MACD动能保留比例。
     */
    public double getCheckpointGivebackMacdRetentionRatio() { return checkpointGivebackMacdRetentionRatio; }

    /**
     * 获取趋势回吐检查窗口的最大持仓K线数量。
     */
    public int getCheckpointGivebackMaxHoldBars() { return checkpointGivebackMaxHoldBars; }

    /**
     * 获取MA20趋势判断的回看K线数量。
     */
    public int getMa20TrendLookbackBars() { return ma20TrendLookbackBars; }

    /**
     * 获取MA20明确趋势的最小归一化斜率百分比。
     */
    public double getMa20TrendThresholdPct() { return ma20TrendThresholdPct; }

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
