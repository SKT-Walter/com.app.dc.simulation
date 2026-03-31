package com.app.dc.service.simulation.strategy.range;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("rsiKdjReversion")
public class RsiKdjReversionBacktestStrategy implements BinanceBacktestStrategy {

    private static final int RSI_PERIOD = 14;
    private static final int STOCH_PERIOD = 9;

    @Override
    public String getName() {
        return "rsiKdjReversion";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < RSI_PERIOD + STOCH_PERIOD + 5) {
            return signal;
        }

        double close = BinanceStrategyMath.close(series, end);
        double rsi = rsi(series, end, RSI_PERIOD);
        double k = stochK(series, end, STOCH_PERIOD);
        double d = smaStochK(series, end, STOCH_PERIOD, 3);
        double atr = BinanceStrategyMath.atr(series, end, 14);
        double buffer = Math.max(close * 0.002, atr * 0.4);

        if (rsi <= 30 && k <= 20 && k > d) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, close - buffer));
            signal.takerPrice = BinanceStrategyMath.scale(close + buffer * 1.6);
        } else if (rsi >= 70 && k >= 80 && k < d) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(close + buffer);
            signal.takerPrice = BinanceStrategyMath.scale(close - buffer * 1.6);
        }
        return signal;
    }

    private double rsi(BarSeries s, int end, int period) {
        int start = Math.max(1, end - period + 1);
        double gain = 0.0;
        double loss = 0.0;
        for (int i = start; i <= end; i++) {
            double diff = s.getBar(i).getClosePrice().doubleValue() - s.getBar(i - 1).getClosePrice().doubleValue();
            if (diff > 0) {
                gain += diff;
            } else {
                loss += -diff;
            }
        }
        if (loss == 0) {
            return 100.0;
        }
        double rs = gain / loss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double stochK(BarSeries s, int end, int period) {
        double high = BinanceStrategyMath.highestHigh(s, end, period);
        double low = BinanceStrategyMath.lowestLow(s, end, period);
        double close = BinanceStrategyMath.close(s, end);
        if (high <= low) {
            return 50.0;
        }
        return (close - low) / (high - low) * 100.0;
    }

    private double smaStochK(BarSeries s, int end, int period, int smooth) {
        int start = Math.max(0, end - smooth + 1);
        double sum = 0;
        int count = 0;
        for (int i = start; i <= end; i++) {
            sum += stochK(s, i, period);
            count++;
        }
        return count == 0 ? 50.0 : sum / count;
    }
}
