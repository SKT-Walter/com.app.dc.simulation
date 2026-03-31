package com.app.dc.service.simulation.strategy.range;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("atrChannelReversion")
public class AtrChannelReversionBacktestStrategy implements BinanceBacktestStrategy {

    private static final int MID_PERIOD = 20;
    private static final int ATR_PERIOD = 14;

    @Override
    public String getName() {
        return "atrChannelReversion";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < MID_PERIOD + ATR_PERIOD + 2) {
            return signal;
        }

        double close = BinanceStrategyMath.close(series, end);
        double mid = BinanceStrategyMath.sma(series, end, MID_PERIOD);
        double atr = BinanceStrategyMath.atr(series, end, ATR_PERIOD);
        double upper = mid + atr * 1.5;
        double lower = mid - atr * 1.5;

        if (close <= lower) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, lower - atr * 0.5));
            signal.takerPrice = BinanceStrategyMath.scale(mid);
        } else if (close >= upper) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(upper + atr * 0.5);
            signal.takerPrice = BinanceStrategyMath.scale(mid);
        }
        return signal;
    }
}
