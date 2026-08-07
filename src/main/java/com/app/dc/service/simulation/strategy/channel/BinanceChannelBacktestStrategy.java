package com.app.dc.service.simulation.strategy.channel;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("binanceChannel")
public class BinanceChannelBacktestStrategy implements BinanceBacktestStrategy {

    private static final int CHANNEL_LOOKBACK = 20;
    private static final int ATR_PERIOD = 14;

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
        if ("ETHUSDT".equalsIgnoreCase(symbol) && "15m".equalsIgnoreCase(text)) {
            return evaluateEthBreakout(signal, series, endIndex, close, upper);
        }
        if (close > upper) {
            signal.side = Side.BUY;
        } else if (close < lower) {
            signal.side = Side.SELL;
        }
        return signal;
    }

    private Signal evaluateEthBreakout(Signal signal, BarSeries series, int end,
                                       double close, double upper) {
        double atr = BinanceStrategyMath.atr(series, end, ATR_PERIOD);
        if (!Double.isFinite(atr) || atr <= 0 || close <= upper) return signal;
        double open = series.getBar(end).getOpenPrice().doubleValue();
        double high = series.getBar(end).getHighPrice().doubleValue();
        double low = series.getBar(end).getLowPrice().doubleValue();
        double range = Math.max(1e-9, high - low);
        double bodyAtr = (close - open) / atr;
        double closeLocation = (close - low) / range;
        double breakoutAtr = (close - upper) / atr;
        double volumeRatio = volumeRatio(series, end, CHANNEL_LOOKBACK);
        double ema20 = ema(series, end, 20);
        double ema60 = ema(series, end, 60);
        double previousEma60 = ema(series, end - 4, 60);
        boolean quality = close > open && bodyAtr >= .35 && bodyAtr <= 1.20
                && closeLocation >= .70 && volumeRatio >= 1.0
                && breakoutAtr >= .10 && breakoutAtr <= 1.50
                && ema20 > ema60 && ema60 > previousEma60;
        if (!quality) return signal;

        double structuralStop = Math.min(low, upper - .50 * atr);
        double riskAtr = (close - structuralStop) / atr;
        if (!Double.isFinite(riskAtr) || riskAtr > 2.50) return signal;
        double stopDistance = Math.max(close - structuralStop, 1.20 * atr);
        signal.side = Side.BUY;
        signal.stopPrice = BinanceStrategyMath.scale(close - stopDistance);
        signal.takerPrice = null;
        signal.algoName = "ETH_CHANNEL_BREAKOUT";
        signal.remark = "NO_FIXED_TAKE_PROFIT|ETH_UP_HIGH_CHANNEL_BREAKOUT";
        return signal;
    }

    private double volumeRatio(BarSeries series, int end, int lookback) {
        int start = Math.max(series.getBeginIndex(), end - lookback);
        double sum = 0; int count = 0;
        for (int i = start; i < end; i++) {
            sum += series.getBar(i).getVolume().doubleValue();
            count++;
        }
        double average = count == 0 ? 0 : sum / count;
        double current = series.getBar(end).getVolume().doubleValue();
        return average <= 0 ? 0 : current / average;
    }

    private double ema(BarSeries series, int end, int period) {
        int safeEnd = Math.max(series.getBeginIndex(), Math.min(end, series.getEndIndex()));
        int start = Math.max(series.getBeginIndex(), safeEnd - period * 4);
        double value = BinanceStrategyMath.close(series, start);
        double alpha = 2d / (period + 1d);
        for (int i = start + 1; i <= safeEnd; i++)
            value = BinanceStrategyMath.close(series, i) * alpha + value * (1 - alpha);
        return value;
    }
}
