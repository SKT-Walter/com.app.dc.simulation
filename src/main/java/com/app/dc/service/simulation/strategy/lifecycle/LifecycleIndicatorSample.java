package com.app.dc.service.simulation.strategy.lifecycle;

/**
 * 生命周期策略使用的单根K线指标样本。
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
    private final double ma10;
    private final double ma20;

    /**
     * 创建完整指标样本。
     */
    public LifecycleIndicatorSample(int index, String endTime, double dif, double dea, double macdBar,
                                    double open, double high, double low, double close, double ma10, double ma20) {
        this.index = index;
        this.endTime = endTime == null ? "" : endTime;
        this.dif = dif;
        this.dea = dea;
        this.macdBar = macdBar;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.ma10 = ma10;
        this.ma20 = ma20;
    }

    /**
     * 获取样本序号。
     */
    public int getIndex() { return index; }

    /**
     * 获取K线结束时间。
     */
    public String getEndTime() { return endTime; }

    /**
     * 获取DIF。
     */
    public double getDif() { return dif; }

    /**
     * 获取DEA。
     */
    public double getDea() { return dea; }

    /**
     * 获取MACD柱。
     */
    public double getMacdBar() { return macdBar; }

    /**
     * 获取开盘价。
     */
    public double getOpen() { return open; }

    /**
     * 获取最高价。
     */
    public double getHigh() { return high; }

    /**
     * 获取最低价。
     */
    public double getLow() { return low; }

    /**
     * 获取收盘价。
     */
    public double getClose() { return close; }

    /**
     * 获取MA10。
     */
    public double getMa10() { return ma10; }

    /**
     * 获取MA20。
     */
    public double getMa20() { return ma20; }
}
