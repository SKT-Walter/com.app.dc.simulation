package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 突破回踩续涨回测策略。
 */
@Service("breakoutRetestContinuation")
public class BreakoutRetestContinuationBacktestStrategy extends AbstractSceneRangeBacktestStrategy {

    private static final int FAST_EMA_PERIOD = 10;
    private static final int TREND_EMA_PERIOD = 20;
    private static final int SLOW_EMA_PERIOD = 60;
    private static final int ATR_PERIOD = 14;
    private static final int BREAK_LOOKBACK = 20;
    private static final double RETEST_ATR_MAX = 0.8;
    private static final double STOP_ATR_BUFFER = 0.35;
    private static final double MIN_TARGET_ATR = 1.0;

    @Override
    public String getName() {
        return "breakoutRetestContinuation";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = baseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < SLOW_EMA_PERIOD + BREAK_LOOKBACK + 5) {
            return signal;
        }

        double close = close(series, end);
        double atr = atr(series, end, ATR_PERIOD);
        double ema10 = ema(series, end, FAST_EMA_PERIOD);
        double ema20 = ema(series, end, TREND_EMA_PERIOD);
        double ema60 = ema(series, end, SLOW_EMA_PERIOD);
        double prevRangeHigh = highestHigh(series, end - 2, BREAK_LOOKBACK);
        double prevRangeLow = lowestLow(series, end - 2, BREAK_LOOKBACK);

        boolean longTrend = close > ema20 && ema20 > ema60;
        boolean shortTrend = close < ema20 && ema20 < ema60;

        if (longTrend) {
            boolean brokeBefore = high(series, end - 1) > prevRangeHigh || close(series, end - 1) > prevRangeHigh;
            boolean retest = low(series, end) <= prevRangeHigh + atr * RETEST_ATR_MAX
                    && low(series, end) >= prevRangeHigh - atr * RETEST_ATR_MAX;
            boolean reclaim = close > prevRangeHigh && close > ema10 && isBullBar(series, end) && closeInUpperHalf(series, end);
            if (brokeBefore && retest && reclaim) {
                double stop = Math.min(low(series, end), prevRangeHigh) - atr * STOP_ATR_BUFFER;
                double target = highestHigh(series, end - 1, BREAK_LOOKBACK) + atr * 1.2;
                if (target - close >= atr * MIN_TARGET_ATR) {
                    signal.side = Side.BUY;
                    signal.stopPrice = BinanceStrategyMath.scale(stop);
                    signal.takerPrice = BinanceStrategyMath.scale(target);
                    signal.algoName = "BRC_LONG";
                }
            }
        }

        if (shortTrend) {
            boolean brokeBefore = low(series, end - 1) < prevRangeLow || close(series, end - 1) < prevRangeLow;
            boolean retest = high(series, end) >= prevRangeLow - atr * RETEST_ATR_MAX
                    && high(series, end) <= prevRangeLow + atr * RETEST_ATR_MAX;
            boolean reject = close < prevRangeLow && close < ema10 && isBearBar(series, end) && closeInLowerHalf(series, end);
            if (brokeBefore && retest && reject) {
                double stop = Math.max(high(series, end), prevRangeLow) + atr * STOP_ATR_BUFFER;
                double target = lowestLow(series, end - 1, BREAK_LOOKBACK) - atr * 1.2;
                if (close - target >= atr * MIN_TARGET_ATR) {
                    signal.side = Side.SELL;
                    signal.stopPrice = BinanceStrategyMath.scale(stop);
                    signal.takerPrice = BinanceStrategyMath.scale(target);
                    signal.algoName = "BRC_SHORT";
                }
            }
        }
        return signal;
    }
}
