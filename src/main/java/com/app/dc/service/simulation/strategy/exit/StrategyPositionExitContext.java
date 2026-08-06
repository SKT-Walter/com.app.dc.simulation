package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.ta4j.core.BarSeries;
import com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot;

/** Read-only closed-bar context used by position exit policies. */
public final class StrategyPositionExitContext {
    public final BarSeries series;
    public final BacktestRegime regime;
    public final StructuralTrendSnapshot structuralTrend;
    public final TrendLifecycleSnapshot trendLifecycle;
    public final String symbol;
    public final String timeframe;

    public StrategyPositionExitContext(BarSeries series, BacktestRegime regime,
                                       StructuralTrendSnapshot structuralTrend) {
        this(series,regime,structuralTrend,TrendLifecycleSnapshot.none(),null,null);
    }

    public StrategyPositionExitContext(BarSeries series,BacktestRegime regime,
                                       StructuralTrendSnapshot structuralTrend,
                                       TrendLifecycleSnapshot trendLifecycle,
                                       String symbol,String timeframe) {
        this.series = series;
        this.regime = regime;
        this.structuralTrend = structuralTrend;
        this.trendLifecycle=trendLifecycle==null?TrendLifecycleSnapshot.none():trendLifecycle;
        this.symbol=symbol;this.timeframe=timeframe;
    }
}
