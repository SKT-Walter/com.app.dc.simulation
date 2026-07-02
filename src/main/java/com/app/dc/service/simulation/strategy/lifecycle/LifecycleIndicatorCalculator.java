package com.app.dc.service.simulation.strategy.lifecycle;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.SMAIndicator;
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
    public List<LifecycleIndicatorSample> calculate(BarSeries series, LifecycleConfig config) {
        List<LifecycleIndicatorSample> samples = new ArrayList<LifecycleIndicatorSample>();
        if (series == null || series.getBarCount() < 2) {
            return samples;
        }
        int adxWindow = resolveAdxWindow(config);
        int stddevWindow = resolveStddevWindow(config);
        int donchianWindow = resolveDonchianWindow(config);
        int crossCountWindow = resolveCrossCountWindow(config);

        ClosePriceIndicator closeIndicator = new ClosePriceIndicator(series);
        MACDIndicator macdIndicator = new MACDIndicator(closeIndicator, 12, 26);
        EMAIndicator signalIndicator = new EMAIndicator(macdIndicator, 9);
        SMAIndicator ma5Indicator = new SMAIndicator(closeIndicator, 5);
        SMAIndicator ma10Indicator = new SMAIndicator(closeIndicator, 10);
        ADXIndicator adxIndicator = new ADXIndicator(series, adxWindow);

        int from = Math.max(0, series.getBarCount() - maxWindow);
        for (int i = from; i < series.getBarCount(); i++) {
            double dif = macdIndicator.getValue(i).doubleValue();
            double dea = signalIndicator.getValue(i).doubleValue();
            double macdBar = dif - dea;
            double closeStddev = calculateCloseStddev(series, i, stddevWindow);
            DonchianRange donchianRange = calculatePreviousDonchianRange(series, i, donchianWindow);
            int recentCrossCount = calculateRecentCrossCount(macdIndicator, signalIndicator, i, crossCountWindow);
            double adx = i + 1 < adxWindow ? Double.NaN : adxIndicator.getValue(i).doubleValue();
            double ma5 = i + 1 < 5 ? Double.NaN : ma5Indicator.getValue(i).doubleValue();
            double ma10 = i + 1 < 10 ? Double.NaN : ma10Indicator.getValue(i).doubleValue();
            double open = series.getBar(i).getOpenPrice().doubleValue();
            double high = series.getBar(i).getHighPrice().doubleValue();
            double low = series.getBar(i).getLowPrice().doubleValue();
            double close = series.getBar(i).getClosePrice().doubleValue();
            samples.add(new LifecycleIndicatorSample(
                    i,
                    series.getBar(i).getEndTime().toString(),
                    dif,
                    dea,
                    macdBar,
                    open,
                    high,
                    low,
                    close,
                    closeStddev,
                    donchianRange.high,
                    donchianRange.low,
                    donchianRange.width,
                    recentCrossCount,
                    adx,
                    ma5,
                    ma10
            ));
        }
        return samples;
    }

    /**
     * 计算截至当前K线的收盘价标准差。
     */
    private double calculateCloseStddev(BarSeries series, int endIndex, int window) {
        if (series == null || window <= 1 || endIndex + 1 < window) {
            return Double.NaN;
        }
        int start = endIndex - window + 1;
        double sum = 0.0;
        for (int i = start; i <= endIndex; i++) {
            sum += series.getBar(i).getClosePrice().doubleValue();
        }
        double mean = sum / window;
        double variance = 0.0;
        for (int i = start; i <= endIndex; i++) {
            double diff = series.getBar(i).getClosePrice().doubleValue() - mean;
            variance += diff * diff;
        }
        return Math.sqrt(variance / window);
    }

    /**
     * 计算当前K线之前Donchian窗口内的最高价和最低价。
     */
    private DonchianRange calculatePreviousDonchianRange(BarSeries series, int currentIndex, int window) {
        if (series == null || window <= 0 || currentIndex < window) {
            return DonchianRange.empty();
        }
        int start = currentIndex - window;
        int end = currentIndex - 1;
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            high = Math.max(high, series.getBar(i).getHighPrice().doubleValue());
            low = Math.min(low, series.getBar(i).getLowPrice().doubleValue());
        }
        return new DonchianRange(high, low);
    }

    /**
     * 计算最近窗口内DIF/DEA交叉次数。
     */
    private int calculateRecentCrossCount(MACDIndicator macdIndicator,
                                          EMAIndicator signalIndicator,
                                          int currentIndex,
                                          int window) {
        if (window <= 0 || currentIndex < 1) {
            return 0;
        }
        int count = 0;
        int start = Math.max(1, currentIndex - window + 1);
        for (int i = start; i <= currentIndex; i++) {
            double prevDif = macdIndicator.getValue(i - 1).doubleValue();
            double prevDea = signalIndicator.getValue(i - 1).doubleValue();
            double currentDif = macdIndicator.getValue(i).doubleValue();
            double currentDea = signalIndicator.getValue(i).doubleValue();
            boolean crossUp = prevDif < prevDea && currentDif >= currentDea;
            boolean crossDown = prevDif > prevDea && currentDif <= currentDea;
            if (crossUp || crossDown) {
                count++;
            }
        }
        return count;
    }

    /**
     * 解析ADX窗口配置。
     */
    private int resolveAdxWindow(LifecycleConfig config) {
        return 14;
    }

    /**
     * 解析收盘价标准差窗口配置。
     */
    private int resolveStddevWindow(LifecycleConfig config) {
        return config == null ? 12 : config.getCompressionStddevWindow();
    }

    /**
     * 解析Donchian窗口配置。
     */
    private int resolveDonchianWindow(LifecycleConfig config) {
        return config == null ? 24 : config.getDonchianWindow();
    }

    /**
     * 解析DIF/DEA交叉次数窗口配置。
     */
    private int resolveCrossCountWindow(LifecycleConfig config) {
        return config == null ? 24 : config.getCrossCountWindow();
    }

    /**
     * Donchian区间计算结果。
     */
    private static class DonchianRange {
        private final double high;
        private final double low;
        private final double width;

        /**
         * 创建Donchian区间结果。
         */
        private DonchianRange(double high, double low) {
            this.high = high;
            this.low = low;
            this.width = high - low;
        }

        /**
         * 创建无效Donchian区间结果。
         */
        private static DonchianRange empty() {
            return new DonchianRange(Double.NaN, Double.NaN);
        }
    }
}
