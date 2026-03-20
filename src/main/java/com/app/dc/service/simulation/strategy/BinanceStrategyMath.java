package com.app.dc.service.simulation.strategy;

import com.app.dc.po.OCType;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class BinanceStrategyMath {

    private BinanceStrategyMath() {
    }

    public static Signal createBaseSignal(String symbol, String text, TTbookOhlc currentOhlc) {
        Signal signal = new Signal();
        signal.symbol = symbol;
        signal.text = text == null ? null : text.toLowerCase();
        signal.algoName = "AI";
        signal.ocType = OCType.OPEN;
        signal.price = currentOhlc.close;
        return signal;
    }

    public static double highestHigh(BarSeries series, int endIndex, int lookback) {
        int start = Math.max(0, endIndex - lookback + 1);
        double max = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= endIndex; i++) {
            max = Math.max(max, series.getBar(i).getHighPrice().doubleValue());
        }
        return max;
    }

    public static double lowestLow(BarSeries series, int endIndex, int lookback) {
        int start = Math.max(0, endIndex - lookback + 1);
        double min = Double.POSITIVE_INFINITY;
        for (int i = start; i <= endIndex; i++) {
            min = Math.min(min, series.getBar(i).getLowPrice().doubleValue());
        }
        return min;
    }

    public static double sma(BarSeries series, int endIndex, int period) {
        int start = Math.max(0, endIndex - period + 1);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i <= endIndex; i++) {
            sum += series.getBar(i).getClosePrice().doubleValue();
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    public static double close(BarSeries series, int endIndex) {
        return series.getBar(endIndex).getClosePrice().doubleValue();
    }

    public static double bandWidthPct(double high, double low) {
        double mid = (high + low) / 2.0;
        if (mid <= 0) {
            return 0.0;
        }
        return (high - low) / mid;
    }

    public static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
