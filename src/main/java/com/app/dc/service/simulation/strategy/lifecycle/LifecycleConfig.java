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
    private final int earlyNonLaunchCheckHoldBars = 5;
    private final int earlyNonLaunchCheckMaxHoldBars = 7;
    private final int nonLaunchCheckHoldBars = 8;
    private final double nonLaunchMinFavorableProgressPct = 0.20d;
    private final double nonLaunchZeroProgressPct = 0.10d;
    private final double checkpointGivebackMinFavorablePct = 0.20d;
    private final double checkpointGivebackMacdRetentionRatio = 0.80d;
    private final int checkpointGivebackMaxHoldBars = 12;
    private final int ma20TrendLookbackBars = 36;
    private final double ma20TrendThresholdPct = 0.10d;
    private final double ma20EntryDistanceMinPct = 0.20d;
    private final double ma20EntryDistanceMaxPct = 0.35d;
    private final double flatMa20StopLossPct = 0.35d;
    private final double launchSuccessProgressPct = 0.20d;
    private final int launchFailureTriggerCount = 3;
    private final int launchRecoveryConfirmAttempts = 2;
    private final double emergencyStopLossPct = 1.0d;
    private final double weakCounterTrendBreakoutMinRatio = 0.40d;
    private final double weakCounterTrendBreakoutMaxRatio = 1.50d;
    private final double weakCounterTrendConfirmPullbackTolerancePct = 0.03d;
    private final double moderateCounterTrendThresholdPct = 0.50d;
    private final double moderateCounterTrendStopLossPct = 0.50d;
    private final double extremeCounterTrendThresholdPct = 1.0d;
    private final double extremeCounterTrendOverextendedBarRangePct = 0.50d;
    private final double extremeCounterTrendOverextendedBreakoutRatio = 0.80d;
    private final double extremeCounterTrendStrongBreakoutRatio = 0.80d;
    private final double extremeCounterTrendStopLossPct = 0.5d;
    private final double extremeCounterTrendWideStopLossPct = 0.8d;
    private final double earlyProfitRoundTripActivationPct = 0.20d;
    private final double profitGivebackActivationPct = 0.50d;
    private final int earlyProfitRoundTripMaxHoldBars = 8;
    private final int matureProfitGivebackMinHoldBars = 9;
    private final double weakMatureProfitActivationPct = 0.20d;
    private final double weakMatureProfitRetainedPct = 0.08d;
    private final double matureProfitRetainedPct = 0.15d;
    private final double matureProfitMacdRetentionRatio = 1.0d;
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
     * 获取早期趋势未启动检查的持仓K线数量。
     */
    public int getEarlyNonLaunchCheckHoldBars() { return earlyNonLaunchCheckHoldBars; }

    /**
     * 获取早期趋势未启动检查的最大持仓K线数量。
     */
    public int getEarlyNonLaunchCheckMaxHoldBars() { return earlyNonLaunchCheckMaxHoldBars; }

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
     * 获取5M入场价格距离MA20的最小方向性百分比。
     */
    public double getMa20EntryDistanceMinPct() { return ma20EntryDistanceMinPct; }

    /**
     * 获取5M入场价格距离MA20的最大方向性百分比。
     */
    public double getMa20EntryDistanceMaxPct() { return ma20EntryDistanceMaxPct; }

    /**
     * 获取MA20走平普通入场的专属止损百分比。
     */
    public double getFlatMa20StopLossPct() { return flatMa20StopLossPct; }

    /**
     * 获取趋势启动成功要求的最小方向性收盘浮盈百分比。
     */
    public double getLaunchSuccessProgressPct() { return launchSuccessProgressPct; }

    /**
     * 获取启用一次性谨慎入场所需的连续未启动次数。
     */
    public int getLaunchFailureTriggerCount() { return launchFailureTriggerCount; }

    /**
     * 获取跳过逆势入场后需要二次确认的普通入场尝试次数。
     */
    public int getLaunchRecoveryConfirmAttempts() { return launchRecoveryConfirmAttempts; }

    /**
     * 获取所有仓位共用的灾难止损百分比。
     */
    public double getEmergencyStopLossPct() { return emergencyStopLossPct; }

    /**
     * 获取轻度逆势额外确认的正常突破下界。
     */
    public double getWeakCounterTrendBreakoutMinRatio() { return weakCounterTrendBreakoutMinRatio; }

    /**
     * 获取轻度逆势额外确认的正常突破上界。
     */
    public double getWeakCounterTrendBreakoutMaxRatio() { return weakCounterTrendBreakoutMaxRatio; }

    /**
     * 获取轻度逆势第二根确认允许的价格回踩百分比。
     */
    public double getWeakCounterTrendConfirmPullbackTolerancePct() {
        return weakCounterTrendConfirmPullbackTolerancePct;
    }

    /**
     * 获取中度逆势普通入场的MA20趋势阈值。
     */
    public double getModerateCounterTrendThresholdPct() { return moderateCounterTrendThresholdPct; }

    /**
     * 获取中度逆势普通入场的保护止损百分比。
     */
    public double getModerateCounterTrendStopLossPct() { return moderateCounterTrendStopLossPct; }

    /**
     * 获取启用极端逆势止损的MA20趋势阈值。
     */
    public double getExtremeCounterTrendThresholdPct() { return extremeCounterTrendThresholdPct; }

    /**
     * 获取极端逆势确认K线过度延伸的最小波幅百分比。
     */
    public double getExtremeCounterTrendOverextendedBarRangePct() {
        return extremeCounterTrendOverextendedBarRangePct;
    }

    /**
     * 获取极端逆势确认K线过度延伸的最小突破比例。
     */
    public double getExtremeCounterTrendOverextendedBreakoutRatio() {
        return extremeCounterTrendOverextendedBreakoutRatio;
    }

    /**
     * 获取极端逆势紧止损要求的最小确认突破比率。
     */
    public double getExtremeCounterTrendStrongBreakoutRatio() {
        return extremeCounterTrendStrongBreakoutRatio;
    }

    /**
     * 获取极端逆势入场的专属止损百分比。
     */
    public double getExtremeCounterTrendStopLossPct() { return extremeCounterTrendStopLossPct; }

    /**
     * 获取弱突破极端逆势入场的宽止损百分比。
     */
    public double getExtremeCounterTrendWideStopLossPct() { return extremeCounterTrendWideStopLossPct; }

    /**
     * 获取前八根利润往返保护的独立激活收益百分比。
     */
    public double getEarlyProfitRoundTripActivationPct() { return earlyProfitRoundTripActivationPct; }

    /**
     * 获取浮盈回吐保护的激活收益百分比。
     */
    public double getProfitGivebackActivationPct() { return profitGivebackActivationPct; }

    /**
     * 获取早期利润往返保护的最大持仓K线数量。
     */
    public int getEarlyProfitRoundTripMaxHoldBars() { return earlyProfitRoundTripMaxHoldBars; }

    /**
     * 获取成熟利润回吐保护的最小持仓K线数量。
     */
    public int getMatureProfitGivebackMinHoldBars() { return matureProfitGivebackMinHoldBars; }

    /**
     * 获取第九根后小趋势回吐的最低浮盈阈值。
     */
    public double getWeakMatureProfitActivationPct() { return weakMatureProfitActivationPct; }

    /**
     * 获取第九根后小趋势允许保留的方向性收益阈值。
     */
    public double getWeakMatureProfitRetainedPct() { return weakMatureProfitRetainedPct; }

    /**
     * 获取成熟利润回吐保护允许保留的最大收益百分比。
     */
    public double getMatureProfitRetainedPct() { return matureProfitRetainedPct; }

    /**
     * 获取成熟利润回吐保护允许的最大MACD动能保留率。
     */
    public double getMatureProfitMacdRetentionRatio() { return matureProfitMacdRetentionRatio; }

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
