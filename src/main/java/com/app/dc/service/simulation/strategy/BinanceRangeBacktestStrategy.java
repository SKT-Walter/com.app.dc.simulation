package com.app.dc.service.simulation.strategy;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 币安震荡策略（与 indsvr 逻辑对齐）。
 */
@Service("binanceRange")
public class BinanceRangeBacktestStrategy implements BinanceBacktestStrategy {

    private static final int RANGE_LOOKBACK = 20;
    private static final double RANGE_MAX_WIDTH_PCT = 0.05;
    private static final double EDGE_ZONE_PCT = 0.15;
    private static final double STOP_BUFFER_PCT_OF_RANGE = 0.10;
    private static final double TAKE_BUFFER_PCT_OF_ZONE = 0.20;

    @Override
    public String getName() {
        return "binanceRange";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int endIndex = series.getEndIndex();
        if (endIndex < RANGE_LOOKBACK + 2) {
            return signal;
        }

        double high = BinanceStrategyMath.highestHigh(series, endIndex, RANGE_LOOKBACK);
        double low = BinanceStrategyMath.lowestLow(series, endIndex, RANGE_LOOKBACK);
        double close = BinanceStrategyMath.close(series, endIndex);
        double widthPct = BinanceStrategyMath.bandWidthPct(high, low);

        double smaNow = BinanceStrategyMath.sma(series, endIndex, RANGE_LOOKBACK);
        double smaPrev = BinanceStrategyMath.sma(series, endIndex - 1, RANGE_LOOKBACK);
        double smaSlopePct = smaPrev == 0 ? 0.0 : Math.abs((smaNow - smaPrev) / smaPrev);
        if (widthPct > RANGE_MAX_WIDTH_PCT || smaSlopePct > 0.003) {
            return signal;
        }

        double zone = (high - low) * EDGE_ZONE_PCT;
        double range = Math.max(0.0, high - low);
        double stopBuffer = range * STOP_BUFFER_PCT_OF_RANGE;
        double takeBuffer = zone * TAKE_BUFFER_PCT_OF_ZONE;

        if (close <= low + zone) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(low - stopBuffer);
            signal.takerPrice = BinanceStrategyMath.scale(high - takeBuffer);
        } else if (close >= high - zone) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(high + stopBuffer);
            signal.takerPrice = BinanceStrategyMath.scale(low + takeBuffer);
        }
        return signal;
    }
}
