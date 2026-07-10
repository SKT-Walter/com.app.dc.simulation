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

    /**
     * 创建指标计算器。
     */
    public LifecycleIndicatorCalculator(int maxWindow) {
        this.maxWindow = maxWindow <= 0 ? 50 : maxWindow;
    }

    /**
     * 将 BarSeries 转换为生命周期样本列表。
     */
    public List<LifecycleIndicatorSample> calculate(BarSeries series) {
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        if (series == null || series.getBarCount() < 2) {
            return samples;
        }
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(close, 12, 26);
        EMAIndicator signal = new EMAIndicator(macd, 9);
        SMAIndicator ma10 = new SMAIndicator(close, 10);
        SMAIndicator ma20 = new SMAIndicator(close, 20);
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
}
