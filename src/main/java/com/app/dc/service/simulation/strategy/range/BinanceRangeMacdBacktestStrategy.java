package com.app.dc.service.simulation.strategy.range;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

@Service("binanceRangeMacd")
public class BinanceRangeMacdBacktestStrategy implements BinanceBacktestStrategy {

    private static final int RANGE_LOOKBACK = 20;
    private static final double RANGE_MAX_WIDTH_PCT = 0.05;
    private static final double EDGE_ZONE_PCT = 0.15;
    private static final double STOP_BUFFER_PCT_OF_RANGE = 0.10;
    private static final double TAKE_BUFFER_PCT_OF_ZONE = 0.20;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;

    @Override
    public String getName() {
        return "binanceRangeMacd";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int endIndex = series.getEndIndex();
        int minBars = Math.max(RANGE_LOOKBACK + 2, MACD_SLOW + MACD_SIGNAL + 2);
        if (endIndex < minBars) {
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

        ClosePriceIndicator closeIndicator = new ClosePriceIndicator(series);
        MACDIndicator macdIndicator = new MACDIndicator(closeIndicator, MACD_FAST, MACD_SLOW);
        EMAIndicator signalIndicator = new EMAIndicator(macdIndicator, MACD_SIGNAL);
        double hist = macdIndicator.getValue(endIndex).doubleValue()
                - signalIndicator.getValue(endIndex).doubleValue();
        double histPrev = macdIndicator.getValue(endIndex - 1).doubleValue()
                - signalIndicator.getValue(endIndex - 1).doubleValue();

        double zone = (high - low) * EDGE_ZONE_PCT;
        double range = Math.max(0.0, high - low);
        double stopBuffer = range * STOP_BUFFER_PCT_OF_RANGE;
        double takeBuffer = zone * TAKE_BUFFER_PCT_OF_ZONE;

        if (close <= low + zone) {
            // 下沿附近仅在空头动能衰减时做多。
            boolean bearishMomentumDecays = hist <= 0 && hist > histPrev;
            if (!bearishMomentumDecays) {
                return signal;
            }
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(low - stopBuffer);
            signal.takerPrice = BinanceStrategyMath.scale(high - takeBuffer);
        } else if (close >= high - zone) {
            // 上沿附近仅在多头动能衰减时做空。
            boolean bullishMomentumDecays = hist >= 0 && hist < histPrev;
            if (!bullishMomentumDecays) {
                return signal;
            }
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(high + stopBuffer);
            signal.takerPrice = BinanceStrategyMath.scale(low + takeBuffer);
        }

        return signal;
    }
}