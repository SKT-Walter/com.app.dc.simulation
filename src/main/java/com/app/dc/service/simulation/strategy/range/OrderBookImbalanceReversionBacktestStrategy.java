package com.app.dc.service.simulation.strategy.range;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("orderBookImbalanceReversion")
public class OrderBookImbalanceReversionBacktestStrategy implements BinanceBacktestStrategy {

    private static final int VOL_PERIOD = 20;

    @Override
    public String getName() {
        return "orderBookImbalanceReversion";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < VOL_PERIOD + 2) {
            return signal;
        }

        double close = series.getBar(end).getClosePrice().doubleValue();
        double open = series.getBar(end).getOpenPrice().doubleValue();
        double high = series.getBar(end).getHighPrice().doubleValue();
        double low = series.getBar(end).getLowPrice().doubleValue();
        double vol = series.getBar(end).getVolume().doubleValue();
        double avgVol = avgVolume(series, end, VOL_PERIOD);
        if (avgVol <= 0) {
            return signal;
        }

        double bodyImbalance = (close - open) / Math.max(1e-9, (high - low));
        boolean burstVol = vol >= avgVol * 1.5;
        double atr = BinanceStrategyMath.atr(series, end, 14);
        double buffer = Math.max(close * 0.002, atr * 0.4);

        if (bodyImbalance <= -0.6 && burstVol) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, close - buffer));
            signal.takerPrice = BinanceStrategyMath.scale(close + buffer * 1.5);
        } else if (bodyImbalance >= 0.6 && burstVol) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(close + buffer);
            signal.takerPrice = BinanceStrategyMath.scale(close - buffer * 1.5);
        }
        return signal;
    }

    private double avgVolume(BarSeries s, int end, int period) {
        int start = Math.max(0, end - period + 1);
        double sum = 0;
        int cnt = 0;
        for (int i = start; i <= end; i++) {
            sum += s.getBar(i).getVolume().doubleValue();
            cnt++;
        }
        return cnt == 0 ? 0 : sum / cnt;
    }
}
