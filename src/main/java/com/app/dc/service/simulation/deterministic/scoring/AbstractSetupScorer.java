package com.app.dc.service.simulation.deterministic.scoring;

import com.app.dc.service.simulation.deterministic.MarketContextFactory;
import com.app.dc.service.simulation.deterministic.StrategyEvaluationContext;
import com.app.dc.service.simulation.deterministic.StrategySetupScore;
import com.app.dc.service.simulation.deterministic.StrategySetupScorer;
import com.app.dc.service.simulation.deterministic.TechnicalSnapshot;

abstract class AbstractSetupScorer implements StrategySetupScorer {
    protected double n(double value, double min, double max) {
        return MarketContextFactory.normalize(value, min, max);
    }

    protected double c(double value) { return MarketContextFactory.clamp(value); }

    protected StrategySetupScore result(double readiness, String factor) {
        return StrategySetupScore.of(readiness, factor);
    }

    protected double rangeEdge(TechnicalSnapshot t) {
        double width = Math.max(1e-9, t.recentHigh - t.recentLow);
        double position = c((t.close - t.recentLow) / width);
        return c(Math.abs(position - .5) * 2);
    }

    protected double reversal(TechnicalSnapshot t) {
        boolean bullish = t.close > t.open && t.close > t.previousClose;
        boolean bearish = t.close < t.open && t.close < t.previousClose;
        return (bullish || bearish) ? c(.5 + t.bodyAtr / 2) : c(t.bodyAtr / 3);
    }

    protected double trendAlignment(StrategyEvaluationContext context) {
        TechnicalSnapshot t = context.technical;
        if ("UP".equals(context.regime.trend))
            return (t.ema10 > t.ema20 && t.ema20 > t.ema60) ? 1 : .25;
        if ("DOWN".equals(context.regime.trend))
            return (t.ema10 < t.ema20 && t.ema20 < t.ema60) ? 1 : .25;
        return 0;
    }

    protected double pullback(TechnicalSnapshot t) {
        if (t.atr <= 0) return 0;
        return c(1 - Math.abs(t.close - t.ema20) / (t.atr * 2));
    }

    protected double breakout(StrategyEvaluationContext context) {
        TechnicalSnapshot t = context.technical;
        if ("DOWN".equals(context.regime.trend))
            return n(t.previousLow - t.close, 0, Math.max(t.atr, 1e-9));
        if ("UP".equals(context.regime.trend))
            return n(t.close - t.previousHigh, 0, Math.max(t.atr, 1e-9));
        return Math.max(n(t.close - t.previousHigh, 0, Math.max(t.atr, 1e-9)),
                n(t.previousLow - t.close, 0, Math.max(t.atr, 1e-9)));
    }

    protected double compression(TechnicalSnapshot t) {
        double range = t.recentHigh - t.recentLow;
        double rangeCompression = t.atr <= 0 ? 0 : c(1 - range / (t.atr * 8));
        double bandwidthCompression = c(1 - t.previousBandwidth / .05);
        return (rangeCompression + bandwidthCompression) / 2;
    }
}
