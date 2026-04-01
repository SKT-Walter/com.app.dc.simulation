package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 强势币动量延续。
 */
@Service("strongMomentumContinuation")
public class StrongMomentumContinuationBacktestStrategy extends AbstractTrendBacktestStrategy {

    private static final int FAST_EMA = 10;
    private static final int MID_EMA = 20;
    private static final int SLOW_EMA = 60;
    private static final int ATR_PERIOD = 14;
    private static final int LOOKBACK = 20;
    private static final double IMPULSE_ATR_MIN = 1.2;
    private static final double STOP_ATR_BUFFER = 0.5;
    private static final double MIN_TARGET_ATR = 1.2;

    @Override
    public String getName() {
        return "strongMomentumContinuation";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = baseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < SLOW_EMA + LOOKBACK + 5) {
            return signal;
        }

        double c = close(series, end);
        double cPrev = close(series, end - 1);
        double atr = atr(series, end, ATR_PERIOD);
        double ema10 = ema(series, end, FAST_EMA);
        double ema20 = ema(series, end, MID_EMA);
        double ema60 = ema(series, end, SLOW_EMA);
        double impulse = Math.abs(c - cPrev) / Math.max(atr, 1e-8);
        double highN = highestHigh(series, end - 1, LOOKBACK);
        double lowN = lowestLow(series, end - 1, LOOKBACK);
        boolean longTrend = ema10 > ema20 && ema20 > ema60;
        boolean shortTrend = ema10 < ema20 && ema20 < ema60;

        if (longTrend && impulse >= IMPULSE_ATR_MIN && c > highN && isBullBar(series, end)) {
            double stop = Math.min(ema20, low(series, end)) - atr * STOP_ATR_BUFFER;
            double target = c + Math.max((c - stop) * 1.5, atr * MIN_TARGET_ATR);
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(stop);
            signal.takerPrice = BinanceStrategyMath.scale(target);
            signal.algoName = "SMC_LONG";
            return signal;
        }

        if (shortTrend && impulse >= IMPULSE_ATR_MIN && c < lowN && isBearBar(series, end)) {
            double stop = Math.max(ema20, high(series, end)) + atr * STOP_ATR_BUFFER;
            double target = c - Math.max((stop - c) * 1.5, atr * MIN_TARGET_ATR);
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(stop);
            signal.takerPrice = BinanceStrategyMath.scale(target);
            signal.algoName = "SMC_SHORT";
            return signal;
        }
        return signal;
    }
}

