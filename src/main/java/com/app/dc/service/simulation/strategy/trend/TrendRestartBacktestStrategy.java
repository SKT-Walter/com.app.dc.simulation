package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 趋势恢复 / 二次启动。
 */
@Service("trendRestart")
public class TrendRestartBacktestStrategy extends AbstractTrendBacktestStrategy {

    private static final int FAST_EMA = 10;
    private static final int MID_EMA = 20;
    private static final int SLOW_EMA = 60;
    private static final int ATR_PERIOD = 14;
    private static final int RSI_PERIOD = 14;
    private static final int PULLBACK_LOOKBACK = 12;
    private static final int SWING_LOOKBACK = 24;
    private static final double MIN_PULLBACK_ATR = 0.8;
    private static final double MAX_PULLBACK_ATR = 2.6;
    private static final double STOP_ATR_BUFFER = 0.35;
    private static final double MIN_TARGET_ATR = 1.0;

    @Override
    public String getName() {
        return "trendRestart";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = baseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < SLOW_EMA + SWING_LOOKBACK + 5) {
            return signal;
        }

        double c = close(series, end);
        double atr = atr(series, end, ATR_PERIOD);
        double ema10 = ema(series, end, FAST_EMA);
        double ema20 = ema(series, end, MID_EMA);
        double ema60 = ema(series, end, SLOW_EMA);
        double rsi = rsi(series, end, RSI_PERIOD);
        double rsiPrev = rsi(series, end - 1, RSI_PERIOD);

        if (c > ema20 && ema20 > ema60) {
            double recentHigh = highestHigh(series, end - 1, PULLBACK_LOOKBACK);
            double pullbackAtr = (recentHigh - c) / Math.max(atr, 1e-8);
            boolean validPullback = pullbackAtr >= MIN_PULLBACK_ATR && pullbackAtr <= MAX_PULLBACK_ATR;
            boolean structureOk = lowestLow(series, end, PULLBACK_LOOKBACK) >= ema60 - atr * 0.8;
            boolean restart = c > ema10 && c > high(series, end - 1) && rsi >= 48 && rsi >= rsiPrev && closeInUpperHalf(series, end);
            if (validPullback && structureOk && restart) {
                double stop = lowestLow(series, end, PULLBACK_LOOKBACK + 3) - atr * STOP_ATR_BUFFER;
                double target = highestHigh(series, end - 1, SWING_LOOKBACK);
                if (target - c >= atr * MIN_TARGET_ATR) {
                    signal.side = Side.BUY;
                    signal.stopPrice = BinanceStrategyMath.scale(stop);
                    signal.takerPrice = BinanceStrategyMath.scale(target);
                    BinanceStrategyMath.bindStrategyIdentity(signal, getName());
                    return signal;
                }
            }
        }

        if (c < ema20 && ema20 < ema60) {
            double recentLow = lowestLow(series, end - 1, PULLBACK_LOOKBACK);
            double reboundAtr = (c - recentLow) / Math.max(atr, 1e-8);
            boolean validRebound = reboundAtr >= MIN_PULLBACK_ATR && reboundAtr <= MAX_PULLBACK_ATR;
            boolean structureOk = highestHigh(series, end, PULLBACK_LOOKBACK) <= ema60 + atr * 0.8;
            boolean restart = c < ema10 && c < low(series, end - 1) && rsi <= 52 && rsi <= rsiPrev && closeInLowerHalf(series, end);
            if (validRebound && structureOk && restart) {
                double stop = highestHigh(series, end, PULLBACK_LOOKBACK + 3) + atr * STOP_ATR_BUFFER;
                double target = lowestLow(series, end - 1, SWING_LOOKBACK);
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

