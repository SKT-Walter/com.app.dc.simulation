package com.app.dc.service.simulation;

import com.app.dc.po.OCType;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 回测策略信号评估服务。
 */
@Service
public class BinanceBacktestStrategyService {

    private static final int RANGE_LOOKBACK = 20;
    private static final double RANGE_MAX_WIDTH_PCT = 0.05;
    private static final double EDGE_ZONE_PCT = 0.15;
    private static final double STOP_BUFFER_PCT_OF_RANGE = 0.10;
    private static final double TAKE_BUFFER_PCT_OF_ZONE = 0.20;

    private static final int CHANNEL_LOOKBACK = 20;

    private static final int FAST = 20;
    private static final int MID = 60;
    private static final int SLOW = 120;
    private static final double TREND_PULLBACK_PCT = 0.006;

    /**
     * 评估某根 K 线结束后的策略信号。
     */
    public Signal evaluateSignal(String strategyName, String symbol, String text, BarSeries replaySeries,
                                 TTbookOhlc currentOhlc) {
        switch (strategyName) {
            case "binanceRange":
                return evaluateRangeSignal(symbol, text, replaySeries, currentOhlc);
            case "binanceChannel":
                return evaluateChannelSignal(symbol, text, replaySeries, currentOhlc);
            case "binanceTrend":
                return evaluateTrendSignal(symbol, text, replaySeries, currentOhlc);
            default:
                throw new IllegalArgumentException("unsupported strategyName: " + strategyName);
        }
    }

    /**
     * 评估震荡策略信号。
     */
    public Signal evaluateRangeSignal(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = createBaseSignal(symbol, text, currentOhlc);
        int endIndex = series.getEndIndex();
        if (endIndex < RANGE_LOOKBACK + 2) {
            return signal;
        }

        double high = highestHigh(series, endIndex, RANGE_LOOKBACK);
        double low = lowestLow(series, endIndex, RANGE_LOOKBACK);
        double close = close(series, endIndex);
        double widthPct = bandWidthPct(high, low);

        double smaNow = sma(series, endIndex, RANGE_LOOKBACK);
        double smaPrev = sma(series, endIndex - 1, RANGE_LOOKBACK);
        double smaSlopePct = smaPrev == 0 ? 0.0 : Math.abs((smaNow - smaPrev) / smaPrev);
        if (widthPct > RANGE_MAX_WIDTH_PCT || smaSlopePct > 0.003) {
            return signal;
        }

        double zone = (high - low) * EDGE_ZONE_PCT;
        double range = Math.max(0.0, high - low);
        double stopBuffer = range * STOP_BUFFER_PCT_OF_RANGE;
        double takeBuffer = zone * TAKE_BUFFER_PCT_OF_ZONE;

        if (close <= low + zone) {
            signal.side = Side.BUY;
            signal.stopPrice = scale(low - stopBuffer);
            signal.takerPrice = scale(high - takeBuffer);
        } else if (close >= high - zone) {
            signal.side = Side.SELL;
            signal.stopPrice = scale(high + stopBuffer);
            signal.takerPrice = scale(low + takeBuffer);
        }
        return signal;
    }

    /**
     * 评估通道突破策略信号。
     */
    public Signal evaluateChannelSignal(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = createBaseSignal(symbol, text, currentOhlc);
        int endIndex = series.getEndIndex();
        if (endIndex < CHANNEL_LOOKBACK + 1) {
            return signal;
        }

        double close = close(series, endIndex);
        double upper = highestHigh(series, endIndex - 1, CHANNEL_LOOKBACK);
        double lower = lowestLow(series, endIndex - 1, CHANNEL_LOOKBACK);
        if (close > upper) {
            signal.side = Side.BUY;
        } else if (close < lower) {
            signal.side = Side.SELL;
        }
        return signal;
    }

    /**
     * 评估趋势策略信号。
     */
    public Signal evaluateTrendSignal(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = createBaseSignal(symbol, text, currentOhlc);
        int endIndex = series.getEndIndex();
        if (endIndex < SLOW + 3) {
            return signal;
        }

        double close = close(series, endIndex);
        double closePrev = close(series, endIndex - 1);
        double maFast = sma(series, endIndex, FAST);
        double maMid = sma(series, endIndex, MID);
        double maSlow = sma(series, endIndex, SLOW);

        double pullbackPct = maFast == 0 ? 0.0 : Math.abs((close - maFast) / maFast);
        boolean nearFastMa = pullbackPct <= TREND_PULLBACK_PCT;
        boolean upTrend = maFast > maMid && maMid > maSlow && close > closePrev;
        boolean downTrend = maFast < maMid && maMid < maSlow && close < closePrev;

        if (upTrend && nearFastMa) {
            signal.side = Side.BUY;
        } else if (downTrend && nearFastMa) {
            signal.side = Side.SELL;
        }
        return signal;
    }

    /**
     * 创建基础信号对象，填充公共字段。
     */
    public Signal createBaseSignal(String symbol, String text, TTbookOhlc currentOhlc) {
        Signal signal = new Signal();
        signal.symbol = symbol;
        signal.text = text == null ? null : text.toLowerCase();
        signal.algoName = "AI";
        signal.ocType = OCType.OPEN;
        signal.price = currentOhlc.close;
        return signal;
    }

    /**
     * 计算区间最高价。
     */
    public double highestHigh(BarSeries series, int endIndex, int lookback) {
        int start = Math.max(0, endIndex - lookback + 1);
        double max = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= endIndex; i++) {
            max = Math.max(max, series.getBar(i).getHighPrice().doubleValue());
        }
        return max;
    }

    /**
     * 计算区间最低价。
     */
    public double lowestLow(BarSeries series, int endIndex, int lookback) {
        int start = Math.max(0, endIndex - lookback + 1);
        double min = Double.POSITIVE_INFINITY;
        for (int i = start; i <= endIndex; i++) {
            min = Math.min(min, series.getBar(i).getLowPrice().doubleValue());
        }
        return min;
    }

    /**
     * 计算简单移动平均值。
     */
    public double sma(BarSeries series, int endIndex, int period) {
        int start = Math.max(0, endIndex - period + 1);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i <= endIndex; i++) {
            sum += series.getBar(i).getClosePrice().doubleValue();
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    /**
     * 获取指定位置收盘价。
     */
    public double close(BarSeries series, int endIndex) {
        return series.getBar(endIndex).getClosePrice().doubleValue();
    }

    /**
     * 计算区间宽度百分比。
     */
    public double bandWidthPct(double high, double low) {
        double mid = (high + low) / 2.0;
        if (mid <= 0) {
            return 0.0;
        }
        return (high - low) / mid;
    }

    /**
     * 统一保留小数位。
     */
    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
