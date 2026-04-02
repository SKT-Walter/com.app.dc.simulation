package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 压缩后突破回测策略。
 */
@Service("compressionBreak")
public class CompressionBreakBacktestStrategy extends AbstractSceneRangeBacktestStrategy {

    private static final int ATR_PERIOD = 14;
    private static final int COMPRESS_LOOKBACK = 6;
    private static final int BASE_LOOKBACK = 20;
    private static final double STOP_ATR_BUFFER = 0.35;
    private static final double MIN_TARGET_ATR = 1.0;

    @Override
    public String getName() {
        return "compressionBreak";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = baseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < BASE_LOOKBACK + COMPRESS_LOOKBACK + 5) {
            return signal;
        }

        double atr = atr(series, end, ATR_PERIOD);
        double recentHigh = highestHigh(series, end - 1, COMPRESS_LOOKBACK);
        double recentLow = lowestLow(series, end - 1, COMPRESS_LOOKBACK);
        double compressRange = recentHigh - recentLow;
        double widerHigh = highestHigh(series, end - 1, BASE_LOOKBACK);
        double widerLow = lowestLow(series, end - 1, BASE_LOOKBACK);
        double widerRange = widerHigh - widerLow;
        boolean compressed = compressRange <= widerRange * 0.38 && compressRange <= atr * 2.0;
        if (!compressed) {
            return signal;
        }

        double close = close(series, end);
        if (close > recentHigh && isBullBar(series, end) && closeInUpperHalf(series, end)) {
            double stop = recentLow - atr * STOP_ATR_BUFFER;
            double target = close + Math.max(compressRange * 1.5, atr * 1.2);
            if (target - close >= atr * MIN_TARGET_ATR) {
                signal.side = Side.BUY;
                signal.stopPrice = BinanceStrategyMath.scale(stop);
                signal.takerPrice = BinanceStrategyMath.scale(target);
                BinanceStrategyMath.bindStrategyIdentity(signal, getName());
            }
        } else if (close < recentLow && isBearBar(series, end) && closeInLowerHalf(series, end)) {
            double stop = recentHigh + atr * STOP_ATR_BUFFER;
            double target = close - Math.max(compressRange * 1.5, atr * 1.2);
            if (close - target >= atr * MIN_TARGET_ATR) {
                signal.side = Side.SELL;
                signal.stopPrice = BinanceStrategyMath.scale(stop);
                signal.takerPrice = BinanceStrategyMath.scale(target);
                BinanceStrategyMath.bindStrategyIdentity(signal, getName());
            }
        }
        return signal;
    }
}
