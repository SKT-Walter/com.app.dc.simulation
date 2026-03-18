package com.app.dc.service.simulation.strategy;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 币安趋势策略（与 indsvr 逻辑对齐，MA=9/21/55）。
 */
@Service("binanceTrend")
public class BinanceTrendBacktestStrategy implements BinanceBacktestStrategy {

    private static final int FAST = 9;
    private static final int MID = 21;
    private static final int SLOW = 55;
    private static final double TREND_PULLBACK_PCT = 0.006;

    @Override
    public String getName() {
        return "binanceTrend";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int endIndex = series.getEndIndex();
        if (endIndex < SLOW + 3) {
            return signal;
        }

        double close = BinanceStrategyMath.close(series, endIndex);
        double closePrev = BinanceStrategyMath.close(series, endIndex - 1);
        double maFast = BinanceStrategyMath.sma(series, endIndex, FAST);
        double maMid = BinanceStrategyMath.sma(series, endIndex, MID);
        double maSlow = BinanceStrategyMath.sma(series, endIndex, SLOW);

        double pullbackPct = maFast == 0 ? 0.0 : Math.abs((close - maFast) / maFast);
        boolean nearFastMa = pullbackPct <= TREND_PULLBACK_PCT;
        boolean upTrend = maFast > maMid && maMid > maSlow && close > closePrev;
        boolean downTrend = maFast < maMid && maMid < maSlow && close < closePrev;

        if (upTrend && nearFastMa) {
            signal.side = Side.BUY;
        } else if (downTrend && nearFastMa) {
            signal.side = Side.SELL;
        }
        return signal;
    }
}
