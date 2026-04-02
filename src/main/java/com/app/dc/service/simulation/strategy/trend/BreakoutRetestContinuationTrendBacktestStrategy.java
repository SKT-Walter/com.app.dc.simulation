package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 突破 + 回踩确认。
 */
@Service("breakoutRetestContinuationTrend")
public class BreakoutRetestContinuationTrendBacktestStrategy extends AbstractTrendBacktestStrategy {

    private static final int FAST_EMA = 10;
    private static final int MID_EMA = 20;
    private static final int SLOW_EMA = 60;
    private static final int ATR_PERIOD = 14;
    private static final int BREAK_LOOKBACK = 20;
    private static final double RETEST_ATR_MAX = 0.8;
    private static final double STOP_ATR_BUFFER = 0.35;
    private static final double MIN_TARGET_ATR = 1.0;

    @Override
    public String getName() {
        return "breakoutRetestContinuationTrend";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = baseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < SLOW_EMA + BREAK_LOOKBACK + 5) {
            return signal;
        }

        double c = close(series, end);
        double atr = atr(series, end, ATR_PERIOD);
        double ema10 = ema(series, end, FAST_EMA);
        double ema20 = ema(series, end, MID_EMA);
        double ema60 = ema(series, end, SLOW_EMA);
        double prevHigh = highestHigh(series, end - 2, BREAK_LOOKBACK);
        double prevLow = lowestLow(series, end - 2, BREAK_LOOKBACK);
        boolean longTrend = c > ema20 && ema20 > ema60;
        boolean shortTrend = c < ema20 && ema20 < ema60;

        if (longTrend) {
            boolean brokeBefore = high(series, end - 1) > prevHigh || close(series, end - 1) > prevHigh;
            boolean retest = low(series, end) <= prevHigh + atr * RETEST_ATR_MAX
                    && low(series, end) >= prevHigh - atr * RETEST_ATR_MAX;
            boolean reclaim = c > prevHigh && c > ema10 && isBullBar(series, end) && closeInUpperHalf(series, end);
            if (brokeBefore && retest && reclaim) {
                double stop = Math.min(low(series, end), prevHigh) - atr * STOP_ATR_BUFFER;
                double target = highestHigh(series, end - 1, BREAK_LOOKBACK) + atr * 1.2;
                if (target - c >= atr * MIN_TARGET_ATR) {
                    signal.side = Side.BUY;
                    signal.stopPrice = BinanceStrategyMath.scale(stop);
                    signal.takerPrice = BinanceStrategyMath.scale(target);
                    BinanceStrategyMath.bindStrategyIdentity(signal, getName());
                    return signal;
                }
            }
        }

        if (shortTrend) {
            boolean brokeBefore = low(series, end - 1) < prevLow || close(series, end - 1) < prevLow;
            boolean retest = high(series, end) >= prevLow - atr * RETEST_ATR_MAX
                    && high(series, end) <= prevLow + atr * RETEST_ATR_MAX;
            boolean reject = c < prevLow && c < ema10 && isBearBar(series, end) && closeInLowerHalf(series, end);
            if (brokeBefore && retest && reject) {
                double stop = Math.max(high(series, end), prevLow) + atr * STOP_ATR_BUFFER;
                double target = lowestLow(series, end - 1, BREAK_LOOKBACK) - atr * 1.2;
                if (c - target >= atr * MIN_TARGET_ATR) {
                    signal.side = Side.SELL;
                    signal.stopPrice = BinanceStrategyMath.scale(stop);
                    signal.takerPrice = BinanceStrategyMath.scale(target);
                    BinanceStrategyMath.bindStrategyIdentity(signal, getName());
                    return signal;
                }
            }
        }
        return signal;
    }
}

