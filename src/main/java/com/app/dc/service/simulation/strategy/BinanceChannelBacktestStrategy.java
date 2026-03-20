package com.app.dc.service.simulation.strategy;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("binanceChannel")
public class BinanceChannelBacktestStrategy implements BinanceBacktestStrategy {

    private static final int CHANNEL_LOOKBACK = 20;

    @Override
    public String getName() {
        return "binanceChannel";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int endIndex = series.getEndIndex();
        if (endIndex < CHANNEL_LOOKBACK + 1) {
            return signal;
        }

        double close = BinanceStrategyMath.close(series, endIndex);
        double upper = BinanceStrategyMath.highestHigh(series, endIndex - 1, CHANNEL_LOOKBACK);
        double lower = BinanceStrategyMath.lowestLow(series, endIndex - 1, CHANNEL_LOOKBACK);
        if (close > upper) {
            signal.side = Side.BUY;
        } else if (close < lower) {
            signal.side = Side.SELL;
        }
        return signal;
    }
}
