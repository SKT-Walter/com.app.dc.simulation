package com.app.dc.service.simulation.deterministic;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
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
    public final StructuralTrendSnapshot structuralTrend;
    public final TrendCompressionSnapshot trendCompression;
    public final TrendLifecycleSnapshot trendLifecycle;
    public final BullTrendSnapshot ethBullTrend;
    public final BullTrendSnapshot solBullTrend;
    public final BearTrendSnapshot ethBearTrend;

    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical){
        this(symbol,timeframe,barIndex,series,currentOhlc,regime,technical,
                StructuralTrendSnapshot.warmup(),TrendCompressionSnapshot.none(),
                TrendLifecycleSnapshot.none(),BullTrendSnapshot.none("ethStructuralBullTrend"),
                BullTrendSnapshot.none("solMomentumBullTrend"),BearTrendSnapshot.none("ethStructuralBearTrend"));
    }
    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical,StructuralTrendSnapshot structural){
        this(symbol,timeframe,barIndex,series,currentOhlc,regime,technical,structural,
                TrendCompressionSnapshot.none(),TrendLifecycleSnapshot.none(),
                BullTrendSnapshot.none("ethStructuralBullTrend"),
                BullTrendSnapshot.none("solMomentumBullTrend"),BearTrendSnapshot.none("ethStructuralBearTrend"));
    }
    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical,StructuralTrendSnapshot structural,
                                     TrendCompressionSnapshot compression){
        this(symbol,timeframe,barIndex,series,currentOhlc,regime,technical,structural,
                compression,TrendLifecycleSnapshot.none(),
                BullTrendSnapshot.none("ethStructuralBullTrend"),
                BullTrendSnapshot.none("solMomentumBullTrend"),BearTrendSnapshot.none("ethStructuralBearTrend"));
    }
    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical,StructuralTrendSnapshot structural,
                                     TrendCompressionSnapshot compression,
                                     TrendLifecycleSnapshot lifecycle){
        this(symbol,timeframe,barIndex,series,currentOhlc,regime,technical,structural,
                compression,lifecycle,BullTrendSnapshot.none("ethStructuralBullTrend"),
                BullTrendSnapshot.none("solMomentumBullTrend"),BearTrendSnapshot.none("ethStructuralBearTrend"));
    }
    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical,StructuralTrendSnapshot structural,
                                     TrendCompressionSnapshot compression,
                                     TrendLifecycleSnapshot lifecycle,
                                     BullTrendSnapshot ethBullTrend,
                                     BullTrendSnapshot solBullTrend){
        this(symbol,timeframe,barIndex,series,currentOhlc,regime,technical,structural,
                compression,lifecycle,ethBullTrend,solBullTrend,BearTrendSnapshot.none("ethStructuralBearTrend"));
    }
    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical,StructuralTrendSnapshot structural,
                                     TrendCompressionSnapshot compression,
                                     TrendLifecycleSnapshot lifecycle,
                                     BullTrendSnapshot ethBullTrend,
                                     BullTrendSnapshot solBullTrend,
                                     BearTrendSnapshot ethBearTrend){
        this.symbol=symbol;this.timeframe=timeframe;this.barIndex=barIndex;this.series=series;
        this.currentOhlc=currentOhlc;this.regime=regime;this.technical=technical;
        this.structuralTrend=structural==null?StructuralTrendSnapshot.warmup():structural;
        this.trendCompression=compression==null?TrendCompressionSnapshot.none():compression;
        this.trendLifecycle=lifecycle==null?TrendLifecycleSnapshot.none():lifecycle;
        this.ethBullTrend=ethBullTrend==null?BullTrendSnapshot.none("ethStructuralBullTrend"):ethBullTrend;
        this.solBullTrend=solBullTrend==null?BullTrendSnapshot.none("solMomentumBullTrend"):solBullTrend;
        this.ethBearTrend=ethBearTrend==null?BearTrendSnapshot.none("ethStructuralBearTrend"):ethBearTrend;
    }
}
