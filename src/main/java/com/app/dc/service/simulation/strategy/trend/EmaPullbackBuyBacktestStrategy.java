package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/**
 * 均线多头排列 + 回踩买入。
 */
@Service("emaPullbackBuy")
public class EmaPullbackBuyBacktestStrategy extends AbstractTrendBacktestStrategy {

    private static final int FAST_EMA = 10;
    private static final int MID_EMA = 20;
    private static final int SLOW_EMA = 60;
    private static final int ATR_PERIOD = 14;
    private static final double PULLBACK_ATR_MAX = 0.6;
    private static final double STOP_ATR_BUFFER = 0.35;
    private static final double MIN_TARGET_ATR = 1.0;

    @Override
    public String getName() {
        return "emaPullbackBuy";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = baseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < SLOW_EMA + 5) {
            return signal;
        }

        double c = close(series, end);
        double atr = atr(series, end, ATR_PERIOD);
        double ema10 = ema(series, end, FAST_EMA);
        double ema20 = ema(series, end, MID_EMA);
        double ema60 = ema(series, end, SLOW_EMA);
        double pullbackAtr = Math.abs(c - ema10) / Math.max(atr, 1e-8);
        boolean trendAligned = ema10 > ema20 && ema20 > ema60;
        boolean pullbackReady = c >= ema20 && c <= ema10 + atr * PULLBACK_ATR_MAX;
        boolean recoveryBar = isBullBar(series, end) && closeInUpperHalf(series, end) && c > close(series, end - 1);
        if (!trendAligned || !pullbackReady || !recoveryBar) {
            return signal;
        }

        double stop = Math.min(low(series, end), ema20) - atr * STOP_ATR_BUFFER;
        double target = c + Math.max((c - stop) * 1.5, atr * MIN_TARGET_ATR);
        if (target - c < atr * MIN_TARGET_ATR) {
            return signal;
        }
        signal.side = Side.BUY;
        signal.stopPrice = BinanceStrategyMath.scale(stop);
        signal.takerPrice = BinanceStrategyMath.scale(target);
        BinanceStrategyMath.bindStrategyIdentity(signal, getName());
        return signal;
    }
}

