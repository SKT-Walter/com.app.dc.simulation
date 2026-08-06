package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;

/** Converts a confirmed compression expansion into a risk-bounded signal. */
@Service
public class TrendCompressionSignalService {
    public Signal evaluate(StrategyEvaluationContext context) {
        TrendCompressionSnapshot scene = context == null
                ? null : context.trendCompression;
        if (scene == null || !scene.triggered || context.technical == null)
            return null;
        TechnicalSnapshot t = context.technical;
        Signal signal = BinanceStrategyMath.createBaseSignal(
                context.symbol, context.timeframe, context.currentOhlc);
        double entry = t.close;
        double atr = t.atr;
        if (!Double.isFinite(entry) || entry <= 0 || !Double.isFinite(atr) || atr <= 0)
            return signal;
        if ("UP".equals(scene.direction)) {
            double stop = Math.min(t.low, scene.breakoutLevel) - .35 * atr;
            double risk = entry - stop;
            if (risk <= 0) return signal;
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(stop);
            signal.takerPrice = BinanceStrategyMath.scale(
                    entry + Math.max(2.0 * risk, 1.5 * atr));
            signal.algoName = "TREND_COMPRESSION_LONG";
        } else if ("DOWN".equals(scene.direction)) {
            double stop = Math.max(t.high, scene.breakoutLevel) + .35 * atr;
            double risk = stop - entry;
            if (risk <= 0) return signal;
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(stop);
            signal.takerPrice = BinanceStrategyMath.scale(
                    entry - Math.max(2.0 * risk, 1.5 * atr));
            signal.algoName = "TREND_COMPRESSION_SHORT";
        }
        return signal;
    }
}
