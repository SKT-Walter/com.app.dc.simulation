package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.ta4j.core.BarSeries;

/** Read-only input shared by candidate rules and pure strategy scorers. */
public final class StrategyEvaluationContext {
    public final String symbol;
    public final String timeframe;
    public final int barIndex;
    public final BarSeries series;
    public final TTbookOhlc currentOhlc;
    public final BacktestRegime regime;
    public final TechnicalSnapshot technical;

    public StrategyEvaluationContext(String symbol, String timeframe, int barIndex, BarSeries series,
                                     TTbookOhlc currentOhlc, BacktestRegime regime,
                                     TechnicalSnapshot technical) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.barIndex = barIndex;
        this.series = series;
        this.currentOhlc = currentOhlc;
        this.regime = regime;
        this.technical = technical;
    }
}
