package com.app.dc.service.simulation.strategy.range;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("vwapReversion")
public class VwapReversionBacktestStrategy implements BinanceBacktestStrategy {

    private static final int PERIOD = 20;
    // 适度降低偏离阈值，增加 VWAP 回归入场频率
    private static final double DEV_THRESHOLD = 0.0038;

    @Override
    public String getName() {
        return "vwapReversion";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < PERIOD + 2) {
            return signal;
        }

        double close = BinanceStrategyMath.close(series, end);
        double vwap = rollingVwap(series, end, PERIOD);
        if (vwap <= 0) {
            return signal;
        }
        double dev = (close - vwap) / vwap;
        double buffer = Math.max(close * 0.0025, BinanceStrategyMath.atr(series, end, 14) * 0.4);

        if (dev <= -DEV_THRESHOLD) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, close - buffer));
            signal.takerPrice = BinanceStrategyMath.scale(vwap);
        } else if (dev >= DEV_THRESHOLD) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(close + buffer);
            signal.takerPrice = BinanceStrategyMath.scale(vwap);
        }
        return signal;
    }

    private double rollingVwap(BarSeries s, int end, int period) {
        int start = Math.max(0, end - period + 1);
        double pv = 0.0;
        double vol = 0.0;
        for (int i = start; i <= end; i++) {
            double h = s.getBar(i).getHighPrice().doubleValue();
            double l = s.getBar(i).getLowPrice().doubleValue();
            double c = s.getBar(i).getClosePrice().doubleValue();
            double tp = (h + l + c) / 3.0;
            double v = s.getBar(i).getVolume().doubleValue();
            pv += tp * v;
            vol += v;
        }
        return vol == 0 ? 0.0 : pv / vol;
    }
}
