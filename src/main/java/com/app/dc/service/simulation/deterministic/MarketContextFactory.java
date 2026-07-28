package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds deterministic technical features without reading bars after the current replay index. */
@Service
public class MarketContextFactory {

    public StrategyEvaluationContext create(String symbol, String timeframe, BarSeries series,
                                            TTbookOhlc ohlc, BacktestRegime regime) {
        return create(symbol, timeframe, series, ohlc, regime, StructuralTrendSnapshot.warmup());
    }

    public StrategyEvaluationContext create(String symbol, String timeframe, BarSeries series,
                                            TTbookOhlc ohlc, BacktestRegime regime,
                                            StructuralTrendSnapshot structuralTrend) {
        int end = series.getEndIndex();
        Bar bar = series.getBar(end);
        double open = bar.getOpenPrice().doubleValue();
        double high = bar.getHighPrice().doubleValue();
        double low = bar.getLowPrice().doubleValue();
        double close = bar.getClosePrice().doubleValue();
        double previousClose = end > series.getBeginIndex() ? close(series, end - 1) : close;
        double atr = feature(regime, "atr", atr(series, end, 14));
        double emaFast = ema(series, end, 12);
        double emaSlow = ema(series, end, 26);
        double ema10 = ema(series, end, 10);
        double ema20 = ema(series, end, 20);
        double ema60 = ema(series, end, 60);
        double pastSlow = ema(series, Math.max(series.getBeginIndex(), end - 4), 26);
        double slope = feature(regime, "emaSlowSlope", pastSlow == 0 ? 0 : (emaSlow - pastSlow) / pastSlow);
        double bandwidth = feature(regime, "bollingerBandwidth", bandwidth(series, end, 20));
        double previousBandwidth = bandwidth(series, Math.max(series.getBeginIndex(), end - 4), 20);
        double[] meanStd = meanStd(series, end, 20);
        double zScore = meanStd[1] == 0 ? 0 : (close - meanStd[0]) / meanStd[1];
        double recentHigh = highest(series, end, 20);
        double recentLow = lowest(series, end, 20);
        double previousHigh = end > series.getBeginIndex() ? highest(series, end - 1, 20) : recentHigh;
        double previousLow = end > series.getBeginIndex() ? lowest(series, end - 1, 20) : recentLow;
        double previousBarHigh = end > series.getBeginIndex()
                ? series.getBar(end - 1).getHighPrice().doubleValue() : high;
        double previousBarLow = end > series.getBeginIndex()
                ? series.getBar(end - 1).getLowPrice().doubleValue() : low;
        double vwap20 = vwap(series, end, 20);
        double previousVwap20 = end > series.getBeginIndex() ? vwap(series, end - 1, 20) : vwap20;
        double range = Math.max(1e-9, high - low);
        double closeLocation = clamp((close - low) / range);
        double bodyAtr = atr <= 0 ? 0 : Math.abs(close - open) / atr;
        double structure = structure(series, end, regime == null ? "NONE" : regime.trend);
        double recovery = meanStd[0] == 0 ? 0
                : clamp(1 - Math.abs(close - meanStd[0]) / Math.max(atr * 2, 1e-9));
        double macdNow = ema(series, end, 12) - ema(series, end, 26);
        double macdPrevious = end > series.getBeginIndex()
                ? ema(series, end - 1, 12) - ema(series, end - 1, 26) : macdNow;
        TechnicalSnapshot technical = new TechnicalSnapshot(
                open, high, low, close, previousClose, atr,
                feature(regime, "atrPercentile", .5), feature(regime, "adx", 0),
                emaFast, emaSlow, ema10, ema20, ema60, slope,
                feature(regime, "volumeRatio", volumeRatio(series, end, 20)),
                bandwidth, previousBandwidth, zScore, rsi(series, end, 14),
                vwap20, previousVwap20, recentHigh, recentLow, previousHigh, previousLow,
                previousBarHigh, previousBarLow,
                closeLocation, bodyAtr, structure, recovery,
                clamp(.5 + (macdNow - macdPrevious) / Math.max(atr, 1e-9)));
        return new StrategyEvaluationContext(symbol, timeframe, end, series, ohlc, regime, technical,
                structuralTrend);
    }

    private double feature(BacktestRegime regime, String name, double fallback) {
        if (regime == null || regime.features == null || !regime.features.containsKey(name)) return fallback;
        Double value = regime.features.get(name);
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private double close(BarSeries s, int i) { return s.getBar(i).getClosePrice().doubleValue(); }

    private double ema(BarSeries s, int end, int period) {
        int safeEnd = Math.max(s.getBeginIndex(), Math.min(end, s.getEndIndex()));
        int start = Math.max(s.getBeginIndex(), safeEnd - period * 4);
        double value = close(s, start), alpha = 2d / (period + 1d);
        for (int i = start + 1; i <= safeEnd; i++) value = close(s, i) * alpha + value * (1 - alpha);
        return value;
    }

    private double atr(BarSeries s, int end, int period) {
        int start = Math.max(s.getBeginIndex() + 1, end - period + 1);
        double sum = 0; int count = 0;
        for (int i = start; i <= end; i++) {
            Bar b = s.getBar(i);
            double h = b.getHighPrice().doubleValue(), l = b.getLowPrice().doubleValue(), pc = close(s, i - 1);
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc))); count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private double bandwidth(BarSeries s, int end, int period) {
        double[] values = meanStd(s, end, period);
        return values[0] == 0 ? 0 : 4 * values[1] / values[0];
    }

    private double[] meanStd(BarSeries s, int end, int period) {
        int start = Math.max(s.getBeginIndex(), end - period + 1), count = end - start + 1;
        if (count <= 0) return new double[]{0, 0};
        double mean = 0;
        for (int i = start; i <= end; i++) mean += close(s, i);
        mean /= count;
        double variance = 0;
        for (int i = start; i <= end; i++) { double d = close(s, i) - mean; variance += d * d; }
        return new double[]{mean, Math.sqrt(variance / count)};
    }

    private double highest(BarSeries s, int end, int period) {
        int start = Math.max(s.getBeginIndex(), end - period + 1); double value = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= end; i++) value = Math.max(value, s.getBar(i).getHighPrice().doubleValue());
        return Double.isFinite(value) ? value : 0;
    }

    private double lowest(BarSeries s, int end, int period) {
        int start = Math.max(s.getBeginIndex(), end - period + 1); double value = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) value = Math.min(value, s.getBar(i).getLowPrice().doubleValue());
        return Double.isFinite(value) ? value : 0;
    }

    private double volumeRatio(BarSeries s, int end, int period) {
        int start = Math.max(s.getBeginIndex(), end - period + 1), count = end - start + 1; double avg = 0;
        for (int i = start; i <= end; i++) avg += s.getBar(i).getVolume().doubleValue();
        avg = count == 0 ? 0 : avg / count;
        return avg == 0 ? 0 : s.getBar(end).getVolume().doubleValue() / avg;
    }

    private double vwap(BarSeries s, int end, int period) {
        int start = Math.max(s.getBeginIndex(), end - period + 1); double pv = 0, volume = 0;
        for (int i = start; i <= end; i++) {
            Bar b = s.getBar(i); double v = b.getVolume().doubleValue();
            double typical = (b.getHighPrice().doubleValue() + b.getLowPrice().doubleValue()
                    + b.getClosePrice().doubleValue()) / 3d;
            pv += typical * v; volume += v;
        }
        return volume == 0 ? close(s, end) : pv / volume;
    }

    private double rsi(BarSeries s, int end, int period) {
        int start = Math.max(s.getBeginIndex() + 1, end - period + 1); double gain = 0, loss = 0; int count = 0;
        for (int i = start; i <= end; i++) {
            double d = close(s, i) - close(s, i - 1);
            if (d > 0) gain += d; else loss -= d; count++;
        }
        if (count == 0 || loss == 0) return gain > 0 ? 100 : 50;
        return 100 - 100 / (1 + gain / loss);
    }

    private double structure(BarSeries s, int end, String trend) {
        if ("NONE".equals(trend) || end - s.getBeginIndex() < 5) return .5;
        int matches = 0, total = 0;
        for (int i = end - 4; i <= end; i++) {
            if (i <= s.getBeginIndex()) continue;
            Bar now = s.getBar(i), previous = s.getBar(i - 1);
            boolean match = "UP".equals(trend)
                    ? now.getHighPrice().isGreaterThan(previous.getHighPrice())
                    && now.getLowPrice().isGreaterThan(previous.getLowPrice())
                    : now.getHighPrice().isLessThan(previous.getHighPrice())
                    && now.getLowPrice().isLessThan(previous.getLowPrice());
            if (match) matches++; total++;
        }
        return total == 0 ? .5 : (double) matches / total;
    }

    public static double normalize(double value, double min, double max) {
        if (!Double.isFinite(value) || max <= min) return 0;
        return clamp((value - min) / (max - min));
    }

    public static double clamp(double value) {
        if (!Double.isFinite(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }
}
