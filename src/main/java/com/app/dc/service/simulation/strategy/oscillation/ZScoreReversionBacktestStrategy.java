package com.app.dc.service.simulation.strategy.oscillation;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("zscoreReversion")
public class ZScoreReversionBacktestStrategy implements BinanceBacktestStrategy {

    private static final int PERIOD = 30;
    private static final double OPEN_Z = 2.0;

    @Override
    public String getName() {
        return "zscoreReversion";
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
        if (std <= 0) {
            return signal;
        }
        double z = (close - mean) / std;

        if (z <= -OPEN_Z) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, close - std * 0.8));
            signal.takerPrice = BinanceStrategyMath.scale(mean);
        } else if (z >= OPEN_Z) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(close + std * 0.8);
            signal.takerPrice = BinanceStrategyMath.scale(mean);
        }
        return signal;
    }

    private double stdClose(BarSeries s, int end, int period, double mean) {
        int start = Math.max(0, end - period + 1);
        double sum = 0;
        int cnt = 0;
        for (int i = start; i <= end; i++) {
            double d = s.getBar(i).getClosePrice().doubleValue() - mean;
            sum += d * d;
            cnt++;
        }
        return cnt <= 1 ? 0 : Math.sqrt(sum / cnt);
    }
}
