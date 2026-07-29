package com.app.dc.service.simulation.strategy.risk;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** Applies a tighter initial stop only to 15m trend entries opposing slow structure. */
@Service
public class BinanceTrendEntryRiskService {
    @Autowired
    private SymbolStrategyProfileService strategyProfiles;
    @Value("${backtest.binance-trend.initial-stop-atr-multiplier:2.0}")
    private double initialStopAtrMultiplier;
    @Value("${backtest.binance-trend.minimum-initial-stop-pct:0.03}")
    private double minimumInitialStopPct;
    @Value("${backtest.binance-trend.maximum-initial-stop-pct:0.04}")
    private double maximumInitialStopPct;

    public boolean apply(String strategyName, Signal signal, BarSeries series,
                         StructuralTrendSnapshot structural) {
        return apply(strategyName, null, null, signal, series, structural);
    }

    public boolean apply(String strategyName, String symbol, String timeframe,
                         Signal signal, BarSeries series,
                         StructuralTrendSnapshot structural) {
        if (!"binanceTrend".equalsIgnoreCase(strategyName) || signal == null
                || series == null || (signal.side != Side.BUY && signal.side != Side.SELL))
            return false;
        boolean changed = applyTakeProfit(symbol, timeframe, signal, series);
        if (structural == null || !structural.ready) return changed;
        boolean counterStructure = signal.side == Side.SELL && structural.isBull()
                || signal.side == Side.BUY && structural.isBear();
        if (!counterStructure) return changed;

        int end = series.getEndIndex();
        double close = BinanceStrategyMath.close(series, end);
        double atr = BinanceStrategyMath.atr(series, end, 14);
        double atrDistance = Math.max(0, atr * initialStopAtrMultiplier);
        double minimumDistance = Math.max(0, close * minimumInitialStopPct);
        double maximumDistance = Math.max(minimumDistance, close * maximumInitialStopPct);
        double distance = Math.min(Math.max(atrDistance, minimumDistance), maximumDistance);
        if (!Double.isFinite(distance) || distance <= 0 || close <= 0) return changed;
        signal.stopPrice = BinanceStrategyMath.scale(
                signal.side == Side.BUY ? close - distance : close + distance);
        return true;
    }

    private boolean applyTakeProfit(String symbol, String timeframe, Signal signal,
                                    BarSeries series) {
        if (strategyProfiles == null) return false;
        Double takeProfitPct = strategyProfiles.takeProfitPct(
                symbol, timeframe, "binanceTrend");
        if (takeProfitPct == null || !Double.isFinite(takeProfitPct)
                || takeProfitPct <= 0) return false;
        double close = BinanceStrategyMath.close(series, series.getEndIndex());
        double ratio = takeProfitPct / 100.0;
        signal.takerPrice = BinanceStrategyMath.scale(signal.side == Side.BUY
                ? close * (1 + ratio) : close * (1 - ratio));
        return true;
    }
}
