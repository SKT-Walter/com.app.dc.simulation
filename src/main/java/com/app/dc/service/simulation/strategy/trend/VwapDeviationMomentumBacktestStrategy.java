package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * Trades continued displacement from rolling VWAP in an established trend.
 * This is intentionally independent from vwapReversion and uses its own exits.
 */
@Service("vwapDeviationMomentum")
public class VwapDeviationMomentumBacktestStrategy implements BinanceBacktestStrategy {

    private static final int VWAP_PERIOD = 20;
    private static final int FAST_MA = 9;
    private static final int SLOW_MA = 21;
    private static final double MIN_DEVIATION = 0.0045;
    private static final double REWARD_RISK = 2.0;

    @Override
    public String getName() {
        return "vwapDeviationMomentum";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < SLOW_MA + 2) return signal;

        double close = BinanceStrategyMath.close(series, end);
        double previousClose = BinanceStrategyMath.close(series, end - 1);
        double open = series.getBar(end).getOpenPrice().doubleValue();
        double vwap = rollingVwap(series, end, VWAP_PERIOD);
        double previousVwap = rollingVwap(series, end - 1, VWAP_PERIOD);
        if (vwap <= 0 || previousVwap <= 0) return signal;

        double deviation = (close - vwap) / vwap;
        double previousDeviation = (previousClose - previousVwap) / previousVwap;
        double fast = BinanceStrategyMath.sma(series, end, FAST_MA);
        double slow = BinanceStrategyMath.sma(series, end, SLOW_MA);
        double risk = Math.max(close * .0035, BinanceStrategyMath.atr(series, end, 14) * .6);
        if (risk <= 0) return signal;

        boolean bullishContinuation = deviation >= MIN_DEVIATION
                && deviation > previousDeviation
                && close > previousClose
                && close > open
                && fast > slow;
        boolean bearishContinuation = deviation <= -MIN_DEVIATION
                && deviation < previousDeviation
                && close < previousClose
                && close < open
                && fast < slow;

        if (bullishContinuation) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(close - risk);
            signal.takerPrice = BinanceStrategyMath.scale(close + risk * REWARD_RISK);
        } else if (bearishContinuation) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(close + risk);
            signal.takerPrice = BinanceStrategyMath.scale(close - risk * REWARD_RISK);
        }
        return signal;
    }

    private double rollingVwap(BarSeries series, int end, int period) {
        int start = Math.max(0, end - period + 1);
        double priceVolume = 0;
        double volume = 0;
        for (int i = start; i <= end; i++) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            double low = series.getBar(i).getLowPrice().doubleValue();
            double close = series.getBar(i).getClosePrice().doubleValue();
            double barVolume = series.getBar(i).getVolume().doubleValue();
            priceVolume += ((high + low + close) / 3.0) * barVolume;
            volume += barVolume;
        }
        return volume == 0 ? 0 : priceVolume / volume;
    }
}
