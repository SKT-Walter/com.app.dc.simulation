package com.app.dc.service.simulation.strategy.risk;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** Applies a tighter initial stop only to 15m trend entries opposing slow structure. */
@Service
public class BinanceTrendEntryRiskService {
    @Value("${backtest.binance-trend.initial-stop-atr-multiplier:2.0}")
    private double initialStopAtrMultiplier;
    @Value("${backtest.binance-trend.minimum-initial-stop-pct:0.03}")
    private double minimumInitialStopPct;
    @Value("${backtest.binance-trend.maximum-initial-stop-pct:0.04}")
    private double maximumInitialStopPct;

    public boolean apply(String strategyName, Signal signal, BarSeries series,
                         StructuralTrendSnapshot structural) {
        if (!"binanceTrend".equalsIgnoreCase(strategyName) || signal == null
                || series == null || structural == null || !structural.ready)
            return false;
        boolean counterStructure = signal.side == Side.SELL && structural.isBull()
                || signal.side == Side.BUY && structural.isBear();
        if (!counterStructure) return false;

        int end = series.getEndIndex();
        double close = BinanceStrategyMath.close(series, end);
        double atr = BinanceStrategyMath.atr(series, end, 14);
        double atrDistance = Math.max(0, atr * initialStopAtrMultiplier);
        double minimumDistance = Math.max(0, close * minimumInitialStopPct);
        double maximumDistance = Math.max(minimumDistance, close * maximumInitialStopPct);
        double distance = Math.min(Math.max(atrDistance, minimumDistance), maximumDistance);
        if (!Double.isFinite(distance) || distance <= 0 || close <= 0) return false;
        signal.stopPrice = BinanceStrategyMath.scale(
                signal.side == Side.BUY ? close - distance : close + distance);
        return true;
    }
}
