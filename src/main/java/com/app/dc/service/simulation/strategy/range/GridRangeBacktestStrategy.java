package com.app.dc.service.simulation.strategy.range;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("gridRange")
public class GridRangeBacktestStrategy implements BinanceBacktestStrategy {

    private static final int LOOKBACK = 20;
    // 放宽边界触发区域，提升震荡区间触发次数
    private static final double EDGE_PCT = 0.20;
    // 将止盈从中轴改为更近位置，缩短持仓时间以提升换手
    private static final double TAKE_PROFIT_PCT = 0.35;

    @Override
    public String getName() {
        return "gridRange";
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
        if (range <= 0) {
            return signal;
        }
        double edge = range * EDGE_PCT;

        if (close <= low + edge) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, low - range * 0.10));
            signal.takerPrice = BinanceStrategyMath.scale(low + range * TAKE_PROFIT_PCT);
        } else if (close >= high - edge) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(high + range * 0.10);
            signal.takerPrice = BinanceStrategyMath.scale(high - range * TAKE_PROFIT_PCT);
        }
        return signal;
    }
}
