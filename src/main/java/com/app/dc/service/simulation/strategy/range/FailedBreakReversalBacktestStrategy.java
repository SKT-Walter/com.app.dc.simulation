package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 假突破反转回测策略。
 */
@Service("failedBreakReversal")
public class FailedBreakReversalBacktestStrategy extends AbstractSceneRangeBacktestStrategy {

    private static final int ATR_PERIOD = 14;
    private static final int BREAK_LOOKBACK = 20;
    private static final double STOP_ATR_BUFFER = 0.3;
    private static final double MIN_TARGET_ATR = 1.0;

    @Override
    public String getName() {
        return "failedBreakReversal";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = baseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < BREAK_LOOKBACK + 5) {
            return signal;
        }

        double atr = atr(series, end, ATR_PERIOD);
        double prevHigh = highestHigh(series, end - 2, BREAK_LOOKBACK);
        double prevLow = lowestLow(series, end - 2, BREAK_LOOKBACK);
        double close = close(series, end);

        boolean fakeUp = high(series, end - 1) > prevHigh && close(series, end - 1) < prevHigh;
        boolean confirmDown = close < low(series, end - 1) && isBearBar(series, end) && closeInLowerHalf(series, end);
        if (fakeUp && confirmDown) {
            double stop = high(series, end - 1) + atr * STOP_ATR_BUFFER;
            double target = prevLow;
            if (close - target >= atr * MIN_TARGET_ATR) {
                signal.side = Side.SELL;
                signal.stopPrice = BinanceStrategyMath.scale(stop);
                signal.takerPrice = BinanceStrategyMath.scale(target);
                BinanceStrategyMath.bindStrategyIdentity(signal, getName());
                return signal;
            }
        }

        boolean fakeDown = low(series, end - 1) < prevLow && close(series, end - 1) > prevLow;
        boolean confirmUp = close > high(series, end - 1) && isBullBar(series, end) && closeInUpperHalf(series, end);
        if (fakeDown && confirmUp) {
            double stop = low(series, end - 1) - atr * STOP_ATR_BUFFER;
            double target = prevHigh;
            if (target - close >= atr * MIN_TARGET_ATR) {
                signal.side = Side.BUY;
                signal.stopPrice = BinanceStrategyMath.scale(stop);
                signal.takerPrice = BinanceStrategyMath.scale(target);
                BinanceStrategyMath.bindStrategyIdentity(signal, getName());
            }
        }
        return signal;
    }
}
