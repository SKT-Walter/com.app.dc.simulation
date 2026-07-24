package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 趋势回撤恢复回测策略。
 *
 * 逻辑与 indsvr 的 TrendPullbackRecoverySignal 对齐：
 * 1. 用 EMA20/EMA60 和 EMA20 斜率识别趋势。
 * 2. 要求价格先出现 ATR 级别回撤，但不能破坏主趋势结构。
 * 3. 再要求恢复确认达到一定打分后才开仓。
 * 4. 止损看 swing low/high 外加 ATR buffer，止盈看前高/前低。
 */
@Service("trendPullbackRecovery")
public class TrendPullbackRecoveryBacktestStrategy implements BinanceBacktestStrategy {

    private static final int FAST_EMA_PERIOD = 10;
    private static final int TREND_EMA_PERIOD = 20;
    private static final int SLOW_EMA_PERIOD = 60;
    private static final int ATR_PERIOD = 14;
    private static final int RSI_PERIOD = 14;
    private static final int MACD_FAST_PERIOD = 12;
    private static final int MACD_SLOW_PERIOD = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;
    private static final int SLOPE_LOOKBACK = 4;
    private static final int PULLBACK_LOOKBACK = 12;
    private static final int SWING_LOOKBACK = 24;
    private static final double MIN_PULLBACK_ATR = 0.8;
    private static final double MAX_PULLBACK_ATR = 2.6;
    private static final double STOP_ATR_BUFFER = 0.35;
    private static final double MIN_TARGET_ATR = 1.0;
    private static final double LONG_RSI_RECOVER = 48.0;
    private static final double SHORT_RSI_RECOVER = 52.0;
    private static final boolean NEED_MACD_IMPROVE = true;

    @Override
    public String getName() {
        return "trendPullbackRecovery";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();

        int minBars = Math.max(
                SLOW_EMA_PERIOD + SLOPE_LOOKBACK + 10,
                Math.max(MACD_SLOW_PERIOD + MACD_SIGNAL_PERIOD + 10, SWING_LOOKBACK + ATR_PERIOD + 10)
        );
        if (end < minBars) {
            return signal;
        }

        double close = close(series, end);
        double closePrev = close(series, end - 1);
        double high = high(series, end);
        double low = low(series, end);
        double emaFast = ema(series, end, FAST_EMA_PERIOD);
        double emaTrend = ema(series, end, TREND_EMA_PERIOD);
        double emaTrendPrev = ema(series, end - SLOPE_LOOKBACK, TREND_EMA_PERIOD);
        double emaSlow = ema(series, end, SLOW_EMA_PERIOD);
        double atr = atr(series, end, ATR_PERIOD);
        if (atr <= 0.0) {
            return signal;
        }

        double rsi = rsi(series, end, RSI_PERIOD);
        double rsiPrev = rsi(series, end - 1, RSI_PERIOD);
        double macdBar = macdBar(series, end, MACD_FAST_PERIOD, MACD_SLOW_PERIOD, MACD_SIGNAL_PERIOD);
        double macdBarPrev = macdBar(series, end - 1, MACD_FAST_PERIOD, MACD_SLOW_PERIOD, MACD_SIGNAL_PERIOD);

        if (isUpTrend(emaTrend, emaSlow, emaTrendPrev, atr)) {
            return tryBuildLongSignal(signal, series, end, close, closePrev, high, low,
                    emaFast, emaTrend, emaSlow, atr, rsi, rsiPrev, macdBar, macdBarPrev);
        }
        if (isDownTrend(emaTrend, emaSlow, emaTrendPrev, atr)) {
            return tryBuildShortSignal(signal, series, end, close, closePrev, high, low,
                    emaFast, emaTrend, emaSlow, atr, rsi, rsiPrev, macdBar, macdBarPrev);
        }
        return signal;
    }

    private Signal tryBuildLongSignal(Signal signal,
                                      BarSeries series,
                                      int end,
                                      double close,
                                      double closePrev,
                                      double high,
                                      double low,
                                      double emaFast,
                                      double emaTrend,
                                      double emaSlow,
                                      double atr,
                                      double rsi,
                                      double rsiPrev,
                                      double macdBar,
                                      double macdBarPrev) {
        double recentHigh = highestHigh(series, end - 1, PULLBACK_LOOKBACK);
        double recentPullbackLow = lowestLow(series, end, PULLBACK_LOOKBACK);
        double pullbackAtr = (recentHigh - close) / atr;
        if (pullbackAtr < MIN_PULLBACK_ATR || pullbackAtr > MAX_PULLBACK_ATR) {
            return signal;
        }
        if (recentPullbackLow < emaSlow - atr * 0.8) {
            return signal;
        }
        if (!isLowStabilizing(series, end)) {
            return signal;
        }

        int score = 0;
        if (close > emaFast) {
            score++;
        }
        if (close > high(series, end - 1)) {
            score++;
        }
        if (close > closePrev) {
            score++;
        }
        if (rsi >= LONG_RSI_RECOVER && rsi >= rsiPrev) {
            score++;
        }
        if (!NEED_MACD_IMPROVE || macdBar > macdBarPrev) {
            score++;
        }
        if (score < 4 || !isBullRecoveryBar(close, high, low)) {
            return signal;
        }

        double swingLow = lowestLow(series, end, Math.min(SWING_LOOKBACK, PULLBACK_LOOKBACK + 4));
        double stop = swingLow - atr * STOP_ATR_BUFFER;
        if (stop >= close) {
            stop = close - atr * 0.5;
        }

        double target = highestHigh(series, end - 1, SWING_LOOKBACK);
        if (target <= close || (target - close) < atr * MIN_TARGET_ATR) {
            return signal;
        }

        signal.side = Side.BUY;
        signal.stopPrice = BinanceStrategyMath.scale(stop);
        signal.takerPrice = BinanceStrategyMath.scale(target);
        signal.algoName = "TPR_LONG";
        signal.remark = String.format(
                "side=BUY close=%.6f emaFast=%.6f emaTrend=%.6f emaSlow=%.6f atr=%.6f rsi=%.2f pullbackAtr=%.2f stop=%.6f tp=%.6f score=%d",
                close, emaFast, emaTrend, emaSlow, atr, rsi, pullbackAtr, stop, target, score
        );
        return signal;
    }

    private Signal tryBuildShortSignal(Signal signal,
                                       BarSeries series,
                                       int end,
                                       double close,
                                       double closePrev,
                                       double high,
                                       double low,
                                       double emaFast,
                                       double emaTrend,
                                       double emaSlow,
                                       double atr,
                                       double rsi,
                                       double rsiPrev,
                                       double macdBar,
                                       double macdBarPrev) {
        double recentLow = lowestLow(series, end - 1, PULLBACK_LOOKBACK);
        double recentPullbackHigh = highestHigh(series, end, PULLBACK_LOOKBACK);
        double reboundAtr = (close - recentLow) / atr;
        if (reboundAtr < MIN_PULLBACK_ATR || reboundAtr > MAX_PULLBACK_ATR) {
            return signal;
        }
        if (recentPullbackHigh > emaSlow + atr * 0.8) {
            return signal;
        }
        if (!isHighStabilizing(series, end)) {
            return signal;
        }

        int score = 0;
        if (close < emaFast) {
            score++;
        }
        if (close < low(series, end - 1)) {
            score++;
        }
        if (close < closePrev) {
            score++;
        }
        if (rsi <= SHORT_RSI_RECOVER && rsi <= rsiPrev) {
            score++;
        }
        if (!NEED_MACD_IMPROVE || macdBar < macdBarPrev) {
            score++;
        }
        if (score < 4 || !isBearRecoveryBar(close, high, low)) {
            return signal;
        }

        double swingHigh = highestHigh(series, end, Math.min(SWING_LOOKBACK, PULLBACK_LOOKBACK + 4));
        double stop = swingHigh + atr * STOP_ATR_BUFFER;
        if (stop <= close) {
            stop = close + atr * 0.5;
        }

        double target = lowestLow(series, end - 1, SWING_LOOKBACK);
        if (target >= close || (close - target) < atr * MIN_TARGET_ATR) {
            return signal;
        }

        signal.side = Side.SELL;
        signal.stopPrice = BinanceStrategyMath.scale(stop);
        signal.takerPrice = BinanceStrategyMath.scale(target);
        signal.algoName = "TPR_SHORT";
        signal.remark = String.format(
                "side=SELL close=%.6f emaFast=%.6f emaTrend=%.6f emaSlow=%.6f atr=%.6f rsi=%.2f reboundAtr=%.2f stop=%.6f tp=%.6f score=%d",
                close, emaFast, emaTrend, emaSlow, atr, rsi, reboundAtr, stop, target, score
        );
        return signal;
    }

    private boolean isUpTrend(double emaTrend, double emaSlow, double emaTrendPrev, double atr) {
        return atr > 0.0 && emaTrend > emaSlow && (emaTrend - emaTrendPrev) / atr > 0.15;
    }

    private boolean isDownTrend(double emaTrend, double emaSlow, double emaTrendPrev, double atr) {
        return atr > 0.0 && emaTrend < emaSlow && (emaTrendPrev - emaTrend) / atr > 0.15;
    }

    private boolean isLowStabilizing(BarSeries series, int end) {
        if (end < 2) {
            return false;
        }
        double low0 = low(series, end);
        double low1 = low(series, end - 1);
        double low2 = low(series, end - 2);
        return low0 >= low1 || low0 >= low2;
    }

    private boolean isHighStabilizing(BarSeries series, int end) {
        if (end < 2) {
            return false;
        }
        double high0 = high(series, end);
        double high1 = high(series, end - 1);
        double high2 = high(series, end - 2);
        return high0 <= high1 || high0 <= high2;
    }

    private boolean isBullRecoveryBar(double close, double high, double low) {
        double range = Math.max(high - low, 1e-8);
        return (close - low) / range >= 0.55;
    }

    private boolean isBearRecoveryBar(double close, double high, double low) {
        double range = Math.max(high - low, 1e-8);
        return (close - low) / range <= 0.45;
    }

    private double close(BarSeries series, int index) {
        return series.getBar(index).getClosePrice().doubleValue();
    }

    private double high(BarSeries series, int index) {
        return series.getBar(index).getHighPrice().doubleValue();
    }

    private double low(BarSeries series, int index) {
        return series.getBar(index).getLowPrice().doubleValue();
    }

    private double highestHigh(BarSeries series, int end, int lookback) {
        return BinanceStrategyMath.highestHigh(series, end, lookback);
    }

    private double lowestLow(BarSeries series, int end, int lookback) {
        return BinanceStrategyMath.lowestLow(series, end, lookback);
    }

    private double atr(BarSeries series, int end, int period) {
        return BinanceStrategyMath.atr(series, end, period);
    }

    private double sma(BarSeries series, int end, int period) {
        if (end - period + 1 < 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = end - period + 1; i <= end; i++) {
            sum += close(series, i);
        }
        return sum / period;
    }

    private double ema(BarSeries series, int end, int period) {
        if (period <= 0 || end < period - 1) {
            return Double.NaN;
        }
        double k = 2.0 / (period + 1.0);
        double ema = sma(series, period - 1, period);
        for (int i = period; i <= end; i++) {
            ema = close(series, i) * k + ema * (1.0 - k);
        }
        return ema;
    }

    private double rsi(BarSeries series, int end, int period) {
        if (period <= 0 || end < period) {
            return Double.NaN;
        }
        double gain = 0.0;
        double loss = 0.0;
        for (int i = end - period + 1; i <= end; i++) {
            double diff = close(series, i) - close(series, i - 1);
            if (diff > 0) {
                gain += diff;
            } else {
                loss += -diff;
            }
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        if (avgLoss == 0.0) {
            return 100.0;
        }
        if (avgGain == 0.0) {
            return 0.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double macdBar(BarSeries series, int end, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (end < slowPeriod + signalPeriod) {
            return Double.NaN;
        }
        int start = slowPeriod - 1;
        double[] difArr = new double[end - start + 1];
        double fast = sma(series, fastPeriod - 1, fastPeriod);
        double fastK = 2.0 / (fastPeriod + 1.0);
        for (int i = fastPeriod; i <= start; i++) {
            fast = close(series, i) * fastK + fast * (1.0 - fastK);
        }
        double slow = sma(series, slowPeriod - 1, slowPeriod);
        double slowK = 2.0 / (slowPeriod + 1.0);
        difArr[0] = fast - slow;
        for (int i = start + 1; i <= end; i++) {
            fast = close(series, i) * fastK + fast * (1.0 - fastK);
            slow = close(series, i) * slowK + slow * (1.0 - slowK);
            difArr[i - start] = fast - slow;
        }
        double dea = emaOfArray(difArr, signalPeriod);
        return difArr[difArr.length - 1] - dea;
    }

    private double emaOfArray(double[] arr, int period) {
        if (arr == null || arr.length < period || period <= 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += arr[i];
        }
        double ema = sum / period;
        double k = 2.0 / (period + 1.0);
        for (int i = period; i < arr.length; i++) {
            ema = arr[i] * k + ema * (1.0 - k);
        }
        return ema;
    }
}
