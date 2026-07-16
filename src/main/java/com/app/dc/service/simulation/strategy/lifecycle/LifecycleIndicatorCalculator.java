package com.app.dc.service.simulation.strategy.lifecycle;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于回放K线计算DIF、DEA、MACD和均线样本。
 */
public class LifecycleIndicatorCalculator {

    private final int maxWindow;
    private BarSeries boundSeries;
    private ClosePriceIndicator close;
    private MACDIndicator macd;
    private EMAIndicator signal;
    private SMAIndicator ma10;
    private SMAIndicator ma20;

    /**
     * 创建指标计算器。
     */
    public LifecycleIndicatorCalculator(int maxWindow) {
        this.maxWindow = maxWindow <= 0 ? 50 : maxWindow;
    }

    /**
     * 将 BarSeries 转换为生命周期样本列表。
     */
    public synchronized List<LifecycleIndicatorSample> calculate(BarSeries series) {
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        if (series == null || series.getBarCount() < 2) {
            return samples;
        }
        if (boundSeries != series) {
            bindSeries(series);
        }
        int from = Math.max(0, series.getBarCount() - maxWindow);
        for (int i = from; i < series.getBarCount(); i++) {
            double dif = macd.getValue(i).doubleValue();
            double dea = signal.getValue(i).doubleValue();
            samples.add(new LifecycleIndicatorSample(
                    i,
                    series.getBar(i).getEndTime().toString(),
                    dif,
                    dea,
                    dif - dea,
                    series.getBar(i).getOpenPrice().doubleValue(),
                    series.getBar(i).getHighPrice().doubleValue(),
                    series.getBar(i).getLowPrice().doubleValue(),
                    series.getBar(i).getClosePrice().doubleValue(),
                    ma10.getValue(i).doubleValue(),
                    ma20.getValue(i).doubleValue()
            ));
        }
        return samples;
    }

    /**
     * 为新的回放序列创建一次ta4j指标对象，后续新增K线复用其缓存。
     */
    private void bindSeries(BarSeries series) {
        boundSeries = series;
        close = new ClosePriceIndicator(series);
        macd = new MACDIndicator(close, 12, 26);
        signal = new EMAIndicator(macd, 9);
        ma10 = new SMAIndicator(close, 10);
        ma20 = new SMAIndicator(close, 20);
    }
}
