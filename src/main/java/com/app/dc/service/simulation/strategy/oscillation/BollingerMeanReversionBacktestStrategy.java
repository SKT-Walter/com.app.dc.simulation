package com.app.dc.service.simulation.strategy.oscillation;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("bollingerMeanReversion")
public class BollingerMeanReversionBacktestStrategy implements BinanceBacktestStrategy {

    private static final int PERIOD = 20;
    private static final double BAND_K = 2.0;

    @Override
    public String getName() {
        return "bollingerMeanReversion";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < PERIOD + 2) {
            return signal;
        }

        double close = BinanceStrategyMath.close(series, end);
        double mean = BinanceStrategyMath.sma(series, end, PERIOD);
        double std = stdClose(series, end, PERIOD, mean);
        if (std <= 0.0) {
            return signal;
        }
        double upper = mean + BAND_K * std;
        double lower = mean - BAND_K * std;

        if (close <= lower) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, lower - std * 0.5));
            signal.takerPrice = BinanceStrategyMath.scale(mean);
        } else if (close >= upper) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(upper + std * 0.5);
            signal.takerPrice = BinanceStrategyMath.scale(mean);
        }
        return signal;
    }

    private double stdClose(BarSeries series, int endIndex, int period, double mean) {
        int start = Math.max(0, endIndex - period + 1);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i <= endIndex; i++) {
            double v = series.getBar(i).getClosePrice().doubleValue();
            double d = v - mean;
            sum += d * d;
            count++;
        }
        return count <= 1 ? 0.0 : Math.sqrt(sum / count);
    }
}
