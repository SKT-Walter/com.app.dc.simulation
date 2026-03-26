package com.app.dc.service.simulation.strategy.oscillation;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("donchianReversion")
public class DonchianReversionBacktestStrategy implements BinanceBacktestStrategy {

    private static final int LOOKBACK = 20;

    @Override
    public String getName() {
        return "donchianReversion";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < LOOKBACK + 2) {
            return signal;
        }

        double high = BinanceStrategyMath.highestHigh(series, end, LOOKBACK);
        double low = BinanceStrategyMath.lowestLow(series, end, LOOKBACK);
        double close = BinanceStrategyMath.close(series, end);
        double range = Math.max(0.0, high - low);
        double edge = range * 0.12;

        if (close <= low + edge) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, low - range * 0.08));
            signal.takerPrice = BinanceStrategyMath.scale((high + low) / 2.0);
        } else if (close >= high - edge) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(high + range * 0.08);
            signal.takerPrice = BinanceStrategyMath.scale((high + low) / 2.0);
        }
        return signal;
    }
}
