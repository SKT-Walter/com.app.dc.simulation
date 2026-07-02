package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 单根K线对应的生命周期指标快照。
 */
public class LifecycleIndicatorSample {

    private final int index;
    private final String endTime;
    private final double dif;
    private final double dea;
    private final double macdBar;
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final double closeStddev;
    private final double donchianHigh;
    private final double donchianLow;
    private final double donchianWidth;
    private final int recentCrossCount;
    private final double adx;
    private final double ma5;
    private final double ma10;

    /**
     * 创建兼容旧测试的DIF/DEA/MACD/close指标样本。
     */
    public LifecycleIndicatorSample(int index, String endTime, double dif, double dea, double macdBar,
                                    double close, double closeStddev, double adx) {
        this(index, endTime, dif, dea, macdBar, close, close, close, close, closeStddev,
                Double.NaN, Double.NaN, Double.NaN, 0, adx, Double.NaN, Double.NaN);
    }

    /**
     * 创建包含震荡识别指标的完整K线样本。
     */
    public LifecycleIndicatorSample(int index, String endTime, double dif, double dea, double macdBar,
                                    double high, double low, double close, double closeStddev,
                                    double donchianHigh, double donchianLow, double donchianWidth,
                                    int recentCrossCount, double adx) {
        this(index, endTime, dif, dea, macdBar, close, high, low, close, closeStddev,
                donchianHigh, donchianLow, donchianWidth, recentCrossCount, adx, Double.NaN, Double.NaN);
    }

    /**
     * 创建包含开高低收和震荡识别字段的完整指标样本。
     */
    public LifecycleIndicatorSample(int index, String endTime, double dif, double dea, double macdBar,
                                    double open, double high, double low, double close, double closeStddev,
                                    double donchianHigh, double donchianLow, double donchianWidth,
                                    int recentCrossCount, double adx) {
        this(index, endTime, dif, dea, macdBar, open, high, low, close, closeStddev,
                donchianHigh, donchianLow, donchianWidth, recentCrossCount, adx, Double.NaN, Double.NaN);
    }

    /**
     * 创建包含均线字段的完整指标样本。
     */
    public LifecycleIndicatorSample(int index, String endTime, double dif, double dea, double macdBar,
                                    double open, double high, double low, double close, double closeStddev,
                                    double donchianHigh, double donchianLow, double donchianWidth,
                                    int recentCrossCount, double adx, double ma5, double ma10) {
        this.index = index;
        this.endTime = endTime == null ? "" : endTime;
        this.dif = dif;
        this.dea = dea;
        this.macdBar = macdBar;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.closeStddev = closeStddev;
        this.donchianHigh = donchianHigh;
        this.donchianLow = donchianLow;
        this.donchianWidth = donchianWidth;
        this.recentCrossCount = recentCrossCount;
        this.adx = adx;
        this.ma5 = ma5;
        this.ma10 = ma10;
    }

    /**
     * 获取样本在计算窗口内的序号。
     */
    public int getIndex() {
        return index;
    }

    /**
     * 获取样本K线结束时间。
     */
    public String getEndTime() {
        return endTime;
    }

    /**
     * 获取DIF值。
     */
    public double getDif() {
        return dif;
    }

    /**
     * 获取DEA值。
     */
    public double getDea() {
        return dea;
    }

    /**
     * 获取MACD柱值。
     */
    public double getMacdBar() {
        return macdBar;
    }

    /**
     * 获取开盘价。
     */
    public double getOpen() {
        return open;
    }

    /**
     * 获取最高价。
     */
    public double getHigh() {
        return high;
    }

    /**
     * 获取最低价。
     */
    public double getLow() {
        return low;
    }

    /**
     * 获取收盘价。
     */
    public double getClose() {
        return close;
    }

    /**
     * 获取收盘价标准差。
     */
    public double getCloseStddev() {
        return closeStddev;
    }

    /**
     * 获取当前K线之前Donchian窗口内的最高价。
     */
    public double getDonchianHigh() {
        return donchianHigh;
    }

    /**
     * 获取当前K线之前Donchian窗口内的最低价。
     */
    public double getDonchianLow() {
        return donchianLow;
    }

    /**
     * 获取当前K线之前Donchian窗口宽度。
     */
    public double getDonchianWidth() {
        return donchianWidth;
    }

    /**
     * 获取最近窗口内DIF/DEA交叉次数。
     */
    public int getRecentCrossCount() {
        return recentCrossCount;
    }

    /**
     * 获取当前K线的ADX值。
     */
    public double getAdx() {
        return adx;
    }

    /**
     * 获取当前K线对应的MA5数值。
     */
    public double getMa5() {
        return ma5;
    }

    /**
     * 获取当前K线对应的MA10数值。
     */
    public double getMa10() {
        return ma10;
    }
}
