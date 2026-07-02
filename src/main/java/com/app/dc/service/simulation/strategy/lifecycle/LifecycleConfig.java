package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期策略的品种级配置。
 */
public class LifecycleConfig {

    private final String strategyName;
    private final String symbol;
    private final String text;
    private final double difDeaBondThreshold;
    private final double reverseCrossBondThreshold;
    private final int bondingWindow;
    private final int bondingMinCount;
    private final int weaknessWindow;
    private final int weaknessMinCount;
    private final boolean compressionFilterEnabled;
    private final int compressionStddevWindow;
    private final double compressionStddevThreshold;
    private final int donchianWindow;
    private final double donchianWidthThreshold;
    private final int crossCountWindow;
    private final int crossCountThreshold;
    private final int launchMacdExpandBars;
    private final int launchMaWindow;
    private final int launchPriceConfirmBars;
    private final boolean launchEntryAfterMacdExpandEnabled;

    /**
     * 是否启用 pending 蓄势观察超时控制。
     */
    private boolean pendingTimeoutEnabled = true;

    /**
     * pending 蓄势观察最多允许等待的 K 线数量。
     * 例如 5M 级别配置为 6，表示最多观察 30 分钟。
     */
    private int pendingMaxBars = 6;

    /**
     * MACD 放大补开仓时，价格允许超过 pending 边界的最大距离比例。
     * 用于避免突破后追得太远。
     *
     * 例如 0.0025 表示 0.25%。
     */
    private double launchMaxBreakoutDistanceRatio = 0.0025;

    /**
     * MACD 放大补开仓前，pending 至少需要观察的 K 线数量。
     * 用于避免刚进入 pending 后马上追击假突破。
     */
    private int launchMinPendingBars = 3;


    /**
     * 创建指定品种和周期的生命周期策略配置。
     */
    public LifecycleConfig(String strategyName, String symbol, String text, double difDeaBondThreshold,
                           int bondingWindow, int bondingMinCount, int weaknessWindow) {
        this(strategyName, symbol, text, difDeaBondThreshold, 0.0, bondingWindow, bondingMinCount,
                weaknessWindow, 0);
    }

    /**
     * 创建带弱化计数阈值的生命周期策略配置。
     */
    public LifecycleConfig(String strategyName, String symbol, String text, double difDeaBondThreshold,
                           int bondingWindow, int bondingMinCount, int weaknessWindow, int weaknessMinCount) {
        this(strategyName, symbol, text, difDeaBondThreshold, 0.0, bondingWindow, bondingMinCount, weaknessWindow,
                weaknessMinCount, true, 12, 0.0, 24, 0.0, 12, 3, 3, 5, 3, true);
    }

    /**
     * 创建带反向交叉去粘合阈值的生命周期策略配置。
     */
    public LifecycleConfig(String strategyName, String symbol, String text, double difDeaBondThreshold,
                           double reverseCrossBondThreshold, int bondingWindow, int bondingMinCount,
                           int weaknessWindow, int weaknessMinCount) {
        this(strategyName, symbol, text, difDeaBondThreshold, reverseCrossBondThreshold, bondingWindow,
                bondingMinCount, weaknessWindow, weaknessMinCount, true, 12, 0.0, 24, 0.0, 12, 3, 3, 5, 3, true);
    }

    /**
     * 创建完整的生命周期策略配置。
     */
    public LifecycleConfig(String strategyName, String symbol, String text, double difDeaBondThreshold,
                           double reverseCrossBondThreshold, int bondingWindow, int bondingMinCount,
                           int weaknessWindow, int weaknessMinCount, boolean compressionFilterEnabled,
                           int compressionStddevWindow, double compressionStddevThreshold,
                           int launchMacdExpandBars,
                           int launchMaWindow, int launchPriceConfirmBars) {
        this(strategyName, symbol, text, difDeaBondThreshold, reverseCrossBondThreshold, bondingWindow,
                bondingMinCount, weaknessWindow, weaknessMinCount, compressionFilterEnabled,
                compressionStddevWindow, compressionStddevThreshold, 24, 0.0, 12, 3,
                launchMacdExpandBars, launchMaWindow, launchPriceConfirmBars, true);
    }

    /**
     * 创建包含三因子震荡过滤参数的完整生命周期策略配置。
     */
    public LifecycleConfig(String strategyName, String symbol, String text, double difDeaBondThreshold,
                           double reverseCrossBondThreshold, int bondingWindow, int bondingMinCount,
                           int weaknessWindow, int weaknessMinCount, boolean compressionFilterEnabled,
                           int compressionStddevWindow, double compressionStddevThreshold,
                           int donchianWindow, double donchianWidthThreshold,
                           int crossCountWindow, int crossCountThreshold,
                           int launchMacdExpandBars,
                           int launchMaWindow, int launchPriceConfirmBars) {
        this(strategyName, symbol, text, difDeaBondThreshold, reverseCrossBondThreshold, bondingWindow,
                bondingMinCount, weaknessWindow, weaknessMinCount, compressionFilterEnabled,
                compressionStddevWindow, compressionStddevThreshold, donchianWindow, donchianWidthThreshold,
                crossCountWindow, crossCountThreshold, launchMacdExpandBars, launchMaWindow,
                launchPriceConfirmBars, true);
    }

    /**
     * 创建包含补开仓开关的完整生命周期策略配置。
     */
    public LifecycleConfig(String strategyName, String symbol, String text, double difDeaBondThreshold,
                           double reverseCrossBondThreshold, int bondingWindow, int bondingMinCount,
                           int weaknessWindow, int weaknessMinCount, boolean compressionFilterEnabled,
                           int compressionStddevWindow, double compressionStddevThreshold,
                           int donchianWindow, double donchianWidthThreshold,
                           int crossCountWindow, int crossCountThreshold,
                           int launchMacdExpandBars,
                           int launchMaWindow, int launchPriceConfirmBars,
                           boolean launchEntryAfterMacdExpandEnabled) {
        this.strategyName = strategyName;
        this.symbol = symbol;
        this.text = text;
        this.difDeaBondThreshold = difDeaBondThreshold;
        this.reverseCrossBondThreshold = reverseCrossBondThreshold;
        this.bondingWindow = bondingWindow;
        this.bondingMinCount = bondingMinCount;
        this.weaknessWindow = weaknessWindow;
        this.weaknessMinCount = weaknessMinCount;
        this.compressionFilterEnabled = compressionFilterEnabled;
        this.compressionStddevWindow = compressionStddevWindow;
        this.compressionStddevThreshold = compressionStddevThreshold;
        this.donchianWindow = donchianWindow;
        this.donchianWidthThreshold = donchianWidthThreshold;
        this.crossCountWindow = crossCountWindow;
        this.crossCountThreshold = crossCountThreshold;
        this.launchMacdExpandBars = launchMacdExpandBars;
        this.launchMaWindow = launchMaWindow;
        this.launchPriceConfirmBars = launchPriceConfirmBars;
        this.launchEntryAfterMacdExpandEnabled = launchEntryAfterMacdExpandEnabled;
    }

    /**
     * 获取策略名称。
     */
    public String getStrategyName() {
        return strategyName;
    }

    /**
     * 获取配置对应的交易品种。
     */
    public String getSymbol() {
        return symbol;
    }

    /**
     * 获取配置对应的K线周期。
     */
    public String getText() {
        return text;
    }

    /**
     * 获取DIF/DEA粘合判定阈值。
     */
    public double getDifDeaBondThreshold() {
        return difDeaBondThreshold;
    }

    /**
     * 获取反向交叉去粘合阈值。
     */
    public double getReverseCrossBondThreshold() {
        if (reverseCrossBondThreshold > 0.0) {
            return reverseCrossBondThreshold;
        }
        if (difDeaBondThreshold <= 0.0) {
            return 0.0;
        }
        return difDeaBondThreshold * 0.3;
    }

    /**
     * 获取粘合判定观察窗口。
     */
    public int getBondingWindow() {
        return bondingWindow <= 0 ? 5 : bondingWindow;
    }

    /**
     * 获取窗口内判定为粘合所需的最少数量。
     */
    public int getBondingMinCount() {
        return bondingMinCount <= 0 ? Math.max(1, getBondingWindow() - 1) : bondingMinCount;
    }

    /**
     * 获取离场弱化确认窗口。
     */
    public int getWeaknessWindow() {
        return weaknessWindow <= 0 ? 3 : weaknessWindow;
    }

    /**
     * 获取离场弱化成立所需的最少次数。
     */
    public int getWeaknessMinCount() {
        int window = getWeaknessWindow();
        int fallback = Math.max(1, window - 1);
        if (weaknessMinCount <= 0) {
            return fallback;
        }
        return Math.min(window, weaknessMinCount);
    }

    /**
     * 判断是否启用标准差震荡过滤。
     */
    public boolean isCompressionFilterEnabled() {
        return compressionFilterEnabled;
    }

    /**
     * 获取收盘价标准差过滤窗口。
     */
    public int getCompressionStddevWindow() {
        return compressionStddevWindow <= 0 ? 12 : compressionStddevWindow;
    }

    /**
     * 获取收盘价标准差阈值。
     */
    public double getCompressionStddevThreshold() {
        return compressionStddevThreshold;
    }

    /**
     * 获取Donchian区间观察窗口。
     */
    public int getDonchianWindow() {
        return donchianWindow <= 0 ? 24 : donchianWindow;
    }

    /**
     * 获取Donchian区间宽度震荡阈值。
     */
    public double getDonchianWidthThreshold() {
        return donchianWidthThreshold;
    }

    /**
     * 获取DIF/DEA交叉次数观察窗口。
     */
    public int getCrossCountWindow() {
        return crossCountWindow <= 0 ? 12 : crossCountWindow;
    }

    /**
     * 获取空仓低动能过滤和启动突破共用的MACD绝对值阈值。
     */
    public double getLowMacdThreshold() {
        return 0.18d;
    }

    /**
     * 获取判定震荡所需的最少交叉次数。
     */
    public int getCrossCountThreshold() {
        return crossCountThreshold <= 0 ? 3 : crossCountThreshold;
    }

    /**
     * 获取MACD放大确认所需的bar数。
     */
    public int getLaunchMacdExpandBars() {
        return launchMacdExpandBars <= 0 ? 3 : launchMacdExpandBars;
    }

    /**
     * 获取启动补开仓使用的MA窗口。
     */
    public int getLaunchMaWindow() {
        return launchMaWindow <= 0 ? 5 : launchMaWindow;
    }

    /**
     * 获取价格同向确认所需的bar数。
     */
    public int getLaunchPriceConfirmBars() {
        return launchPriceConfirmBars <= 0 ? 3 : launchPriceConfirmBars;
    }

    /**
     * 判断是否允许 pending 观察态触发 MACD 放大后补开仓。
     */
    public boolean isLaunchEntryAfterMacdExpandEnabled() {
        return launchEntryAfterMacdExpandEnabled;
    }

    /**
     * 判断当前配置是否允许产生开仓信号。
     */
    public boolean validForEntry() {
        return difDeaBondThreshold > 0.0;
    }

    /**
     * 返回是否启用 pending 超时控制。
     */
    public boolean isPendingTimeoutEnabled() {
        return pendingTimeoutEnabled;
    }

    /**
     * 设置是否启用 pending 超时控制。
     */
    public void setPendingTimeoutEnabled(boolean pendingTimeoutEnabled) {
        this.pendingTimeoutEnabled = pendingTimeoutEnabled;
    }

    /**
     * 返回 pending 最多观察 K 线数。
     */
    public int getPendingMaxBars() {
        return pendingMaxBars;
    }

    /**
     * 设置 pending 最多观察 K 线数。
     */
    public void setPendingMaxBars(int pendingMaxBars) {
        this.pendingMaxBars = pendingMaxBars;
    }

    /**
     * 返回 MACD 放大补开仓允许的最大追价距离比例。
     */
    public double getLaunchMaxBreakoutDistanceRatio() {
        return launchMaxBreakoutDistanceRatio;
    }

    /**
     * 设置 MACD 放大补开仓允许的最大追价距离比例。
     */
    public void setLaunchMaxBreakoutDistanceRatio(double launchMaxBreakoutDistanceRatio) {
        this.launchMaxBreakoutDistanceRatio = launchMaxBreakoutDistanceRatio;
    }

    /**
     * 返回 MACD 放大补开仓前 pending 至少观察的 K 线数量。
     */
    public int getLaunchMinPendingBars() {
        return launchMinPendingBars;
    }

    /**
     * 设置 MACD 放大补开仓前 pending 至少观察的 K 线数量。
     */
    public void setLaunchMinPendingBars(int launchMinPendingBars) {
        this.launchMinPendingBars = launchMinPendingBars;
    }
}
