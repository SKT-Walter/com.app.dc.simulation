package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 脉冲回收回测策略。
 */
@Service("impulseReclaim")
public class ImpulseReclaimBacktestStrategy extends AbstractSceneRangeBacktestStrategy {

    private static final int ATR_PERIOD = 14;
    private static final double IMPULSE_BODY_ATR = 1.2;
    private static final double RECLAIM_RATIO = 0.55;
    private static final double STOP_ATR_BUFFER = 0.3;
    private static final double MIN_TARGET_ATR = 1.0;

    @Override
    public String getName() {
        return "impulseReclaim";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = baseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < ATR_PERIOD + 5) {
            return signal;
        }

        double atr = atr(series, end, ATR_PERIOD);
        double close = close(series, end);
        double prevBody = body(series, end - 1);
        boolean extremeBear = isBearBar(series, end - 1) && prevBody >= atr * IMPULSE_BODY_ATR;
        boolean extremeBull = isBullBar(series, end - 1) && prevBody >= atr * IMPULSE_BODY_ATR;

        if (extremeBear) {
            double prevOpen = open(series, end - 1);
            double prevClose = close(series, end - 1);
            double reclaimLine = prevClose + (prevOpen - prevClose) * RECLAIM_RATIO;
            boolean reclaim = close >= reclaimLine && isBullBar(series, end) && closeInUpperHalf(series, end);
            if (reclaim) {
                double stop = low(series, end - 1) - atr * STOP_ATR_BUFFER;
                double target = Math.max(highestHigh(series, end - 1, 8), close + atr * 1.2);
                if (target - close >= atr * MIN_TARGET_ATR) {
                    signal.side = Side.BUY;
                    signal.stopPrice = BinanceStrategyMath.scale(stop);
                    signal.takerPrice = BinanceStrategyMath.scale(target);
                    BinanceStrategyMath.bindStrategyIdentity(signal, getName());
                    return signal;
                }
            }
        }

        if (extremeBull) {
            double prevOpen = open(series, end - 1);
            double prevClose = close(series, end - 1);
            double reclaimLine = prevClose - (prevClose - prevOpen) * RECLAIM_RATIO;
            boolean reject = close <= reclaimLine && isBearBar(series, end) && closeInLowerHalf(series, end);
            if (reject) {
                double stop = high(series, end - 1) + atr * STOP_ATR_BUFFER;
                double target = Math.min(lowestLow(series, end - 1, 8), close - atr * 1.2);
                if (close - target >= atr * MIN_TARGET_ATR) {
                    signal.side = Side.SELL;
                    signal.stopPrice = BinanceStrategyMath.scale(stop);
                    signal.takerPrice = BinanceStrategyMath.scale(target);
                    BinanceStrategyMath.bindStrategyIdentity(signal, getName());
                }
            }
        }
        return signal;
    }
}
