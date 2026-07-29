package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.ta4j.core.BarSeries;

/** Read-only closed-bar context used by position exit policies. */
public final class StrategyPositionExitContext {
    public final BarSeries series;
    public final BacktestRegime regime;
    public final StructuralTrendSnapshot structuralTrend;

    public StrategyPositionExitContext(BarSeries series, BacktestRegime regime,
                                       StructuralTrendSnapshot structuralTrend) {
        this.series = series;
        this.regime = regime;
        this.structuralTrend = structuralTrend;
    }
}
