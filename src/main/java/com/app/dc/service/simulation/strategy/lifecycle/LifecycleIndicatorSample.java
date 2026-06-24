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
    private final double close;

    /**
     * 创建一条DIF/DEA/MACD/close指标样本。
     */
    public LifecycleIndicatorSample(int index, String endTime, double dif, double dea, double macdBar, double close) {
        this.index = index;
        this.endTime = endTime == null ? "" : endTime;
        this.dif = dif;
        this.dea = dea;
        this.macdBar = macdBar;
        this.close = close;
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
     * 获取收盘价。
     */
    public double getClose() {
        return close;
    }
}
