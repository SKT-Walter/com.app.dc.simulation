package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.ta4j.core.BarSeries;

/**
 * range 场景回测基础能力。
 */
public abstract class AbstractSceneRangeBacktestStrategy implements BinanceBacktestStrategy {

    protected Signal baseSignal(String symbol, String text, TTbookOhlc currentOhlc) {
        return BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
    }

    protected double open(BarSeries series, int index) {
        return series.getBar(index).getOpenPrice().doubleValue();
    }

    protected double high(BarSeries series, int index) {
        return series.getBar(index).getHighPrice().doubleValue();
    }

    protected double low(BarSeries series, int index) {
        return series.getBar(index).getLowPrice().doubleValue();
    }

    protected double close(BarSeries series, int index) {
        return series.getBar(index).getClosePrice().doubleValue();
    }

    protected double body(BarSeries series, int index) {
        return Math.abs(close(series, index) - open(series, index));
    }

    protected double barRange(BarSeries series, int index) {
        return Math.max(high(series, index) - low(series, index), 1e-8);
    }

    protected double highestHigh(BarSeries series, int end, int lookback) {
        return BinanceStrategyMath.highestHigh(series, end, lookback);
    }

    protected double lowestLow(BarSeries series, int end, int lookback) {
        return BinanceStrategyMath.lowestLow(series, end, lookback);
    }

    protected double atr(BarSeries series, int end, int period) {
        return BinanceStrategyMath.atr(series, end, period);
    }

    protected double sma(BarSeries series, int end, int period) {
        if (end - period + 1 < 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = end - period + 1; i <= end; i++) {
            sum += close(series, i);
        }
        return sum / period;
    }

    protected double ema(BarSeries series, int end, int period) {
        if (period <= 0 || end < period - 1) {
            return Double.NaN;
        }
        double factor = 2.0 / (period + 1.0);
        double value = sma(series, period - 1, period);
        for (int i = period; i <= end; i++) {
            value = close(series, i) * factor + value * (1.0 - factor);
        }
        return value;
    }

    protected boolean isBullBar(BarSeries series, int index) {
        return close(series, index) > open(series, index);
    }

    protected boolean isBearBar(BarSeries series, int index) {
        return close(series, index) < open(series, index);
    }

    protected boolean closeInUpperHalf(BarSeries series, int index) {
        double pos = (close(series, index) - low(series, index)) / barRange(series, index);
        return pos >= 0.5;
    }

    protected boolean closeInLowerHalf(BarSeries series, int index) {
        double pos = (close(series, index) - low(series, index)) / barRange(series, index);
        return pos <= 0.5;
    }
}
