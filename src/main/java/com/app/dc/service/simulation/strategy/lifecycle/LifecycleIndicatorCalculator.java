package com.app.dc.service.simulation.strategy.lifecycle;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据回放K线序列计算生命周期策略需要的DIF、DEA和MACD柱。
 */
public class LifecycleIndicatorCalculator {

    private final int maxWindow;

    /**
     * 创建指标计算器，并限制参与决策的最大窗口。
     */
    public LifecycleIndicatorCalculator(int maxWindow) {
        this.maxWindow = maxWindow <= 0 ? 50 : maxWindow;
    }

    /**
     * 计算当前序列的指标样本列表。
     */
    public List<LifecycleIndicatorSample> calculate(BarSeries series) {
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        if (series == null || series.getBarCount() < 2) {
            return samples;
        }

        ClosePriceIndicator closeIndicator = new ClosePriceIndicator(series);
        MACDIndicator macdIndicator = new MACDIndicator(closeIndicator, 12, 26);
        EMAIndicator signalIndicator = new EMAIndicator(macdIndicator, 9);

        int from = Math.max(0, series.getBarCount() - maxWindow);
        for (int i = from; i < series.getBarCount(); i++) {
            double dif = macdIndicator.getValue(i).doubleValue();
            double dea = signalIndicator.getValue(i).doubleValue();
            double macdBar = dif - dea;
            samples.add(new LifecycleIndicatorSample(
                    i,
                    series.getBar(i).getEndTime().toString(),
                    dif,
                    dea,
                    macdBar,
                    series.getBar(i).getClosePrice().doubleValue()
            ));
        }
        return samples;
    }
}
