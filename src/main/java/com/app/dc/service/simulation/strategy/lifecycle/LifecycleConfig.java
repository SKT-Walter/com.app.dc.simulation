package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期策略的品种级配置，控制粘合过滤和离场确认窗口。
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

    /**
     * 创建指定品种和周期的生命周期策略配置。
     */
    public LifecycleConfig(String strategyName, String symbol, String text, double difDeaBondThreshold,
                           int bondingWindow, int bondingMinCount, int weaknessWindow) {
        this(strategyName, symbol, text, difDeaBondThreshold, 0.0, bondingWindow, bondingMinCount, weaknessWindow, 0);
    }

    /**
     * 创建带弱化计数阈值的生命周期策略配置。
     */
    public LifecycleConfig(String strategyName, String symbol, String text, double difDeaBondThreshold,
                           int bondingWindow, int bondingMinCount, int weaknessWindow, int weaknessMinCount) {
        this(strategyName, symbol, text, difDeaBondThreshold, 0.0, bondingWindow, bondingMinCount, weaknessWindow,
                weaknessMinCount);
    }

    /**
     * 创建带反向交叉去粘合阈值的生命周期策略配置。
     */
    public LifecycleConfig(String strategyName, String symbol, String text, double difDeaBondThreshold,
                           double reverseCrossBondThreshold, int bondingWindow, int bondingMinCount,
                           int weaknessWindow, int weaknessMinCount) {
        this.strategyName = strategyName;
        this.symbol = symbol;
        this.text = text;
        this.difDeaBondThreshold = difDeaBondThreshold;
        this.reverseCrossBondThreshold = reverseCrossBondThreshold;
        this.bondingWindow = bondingWindow;
        this.bondingMinCount = bondingMinCount;
        this.weaknessWindow = weaknessWindow;
        this.weaknessMinCount = weaknessMinCount;
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
     * 获取反向交叉去粘合阈值，默认比开仓过滤更敏感。
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
     * 获取窗口内判定为粘合所需的最小K线数量。
     */
    public int getBondingMinCount() {
        return bondingMinCount <= 0 ? Math.max(1, getBondingWindow() - 1) : bondingMinCount;
    }

    /**
     * 获取MACD和close连续弱化/强化的确认窗口。
     */
    public int getWeaknessWindow() {
        return weaknessWindow <= 0 ? 3 : weaknessWindow;
    }

    /**
     * 获取窗口内弱化/恢复所需的最小成立次数。
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
     * 判断当前配置是否允许产生开仓信号。
     */
    public boolean validForEntry() {
        return difDeaBondThreshold > 0.0;
    }
}
