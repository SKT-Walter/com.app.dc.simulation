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
    public final BullTrendSnapshot solBullLaunchTrend;
    public final BearTrendSnapshot ethBearTrend;
    public final BearTrendSnapshot solBearTrend;
    public final BullTrendSnapshot btcBullLaunchTrend;
    public final BullTrendSnapshot btcBullTrend;
    public final BearTrendSnapshot btcBearTrend;

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
        this(symbol,timeframe,barIndex,series,currentOhlc,regime,technical,structural,
                compression,lifecycle,ethBullTrend,solBullTrend,
                BullTrendSnapshot.none("solBullLaunchTrend"),ethBearTrend);
    }
    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical,StructuralTrendSnapshot structural,
                                     TrendCompressionSnapshot compression,
                                     TrendLifecycleSnapshot lifecycle,
                                     BullTrendSnapshot ethBullTrend,
                                     BullTrendSnapshot solBullTrend,
                                     BullTrendSnapshot solBullLaunchTrend,
                                     BearTrendSnapshot ethBearTrend){
        this(symbol,timeframe,barIndex,series,currentOhlc,regime,technical,structural,
                compression,lifecycle,ethBullTrend,solBullTrend,solBullLaunchTrend,ethBearTrend,
                BearTrendSnapshot.none("solStructuralBearTrend"));
    }
    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical,StructuralTrendSnapshot structural,
                                     TrendCompressionSnapshot compression,
                                     TrendLifecycleSnapshot lifecycle,
                                     BullTrendSnapshot ethBullTrend,
                                     BullTrendSnapshot solBullTrend,
                                     BullTrendSnapshot solBullLaunchTrend,
                                     BearTrendSnapshot ethBearTrend,
                                     BearTrendSnapshot solBearTrend){
        this(symbol,timeframe,barIndex,series,currentOhlc,regime,technical,structural,compression,lifecycle,
                ethBullTrend,solBullTrend,solBullLaunchTrend,ethBearTrend,solBearTrend,
                BullTrendSnapshot.none("btcBullLaunchTrend"),BearTrendSnapshot.none("btcStructuralBearTrend"));
    }
    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical,StructuralTrendSnapshot structural,
                                     TrendCompressionSnapshot compression,
                                     TrendLifecycleSnapshot lifecycle,
                                     BullTrendSnapshot ethBullTrend,
                                     BullTrendSnapshot solBullTrend,
                                     BullTrendSnapshot solBullLaunchTrend,
                                     BearTrendSnapshot ethBearTrend,
                                     BearTrendSnapshot solBearTrend,
                                     BullTrendSnapshot btcBullLaunchTrend,
                                     BearTrendSnapshot btcBearTrend){
        this(symbol,timeframe,barIndex,series,currentOhlc,regime,technical,structural,compression,lifecycle,
                ethBullTrend,solBullTrend,solBullLaunchTrend,ethBearTrend,solBearTrend,btcBullLaunchTrend,
                BullTrendSnapshot.none("btcStructuralBullTrend"),btcBearTrend);
    }
    public StrategyEvaluationContext(String symbol,String timeframe,int barIndex,BarSeries series,
                                     TTbookOhlc currentOhlc,BacktestRegime regime,
                                     TechnicalSnapshot technical,StructuralTrendSnapshot structural,
                                     TrendCompressionSnapshot compression,
                                     TrendLifecycleSnapshot lifecycle,
                                     BullTrendSnapshot ethBullTrend,
                                     BullTrendSnapshot solBullTrend,
                                     BullTrendSnapshot solBullLaunchTrend,
                                     BearTrendSnapshot ethBearTrend,
                                     BearTrendSnapshot solBearTrend,
                                     BullTrendSnapshot btcBullLaunchTrend,
                                     BullTrendSnapshot btcBullTrend,
                                     BearTrendSnapshot btcBearTrend){
        this.symbol=symbol;this.timeframe=timeframe;this.barIndex=barIndex;this.series=series;
        this.currentOhlc=currentOhlc;this.regime=regime;this.technical=technical;
        this.structuralTrend=structural==null?StructuralTrendSnapshot.warmup():structural;
        this.trendCompression=compression==null?TrendCompressionSnapshot.none():compression;
        this.trendLifecycle=lifecycle==null?TrendLifecycleSnapshot.none():lifecycle;
        this.ethBullTrend=ethBullTrend==null?BullTrendSnapshot.none("ethStructuralBullTrend"):ethBullTrend;
        this.solBullTrend=solBullTrend==null?BullTrendSnapshot.none("solMomentumBullTrend"):solBullTrend;
        this.solBullLaunchTrend=solBullLaunchTrend==null?BullTrendSnapshot.none("solBullLaunchTrend"):solBullLaunchTrend;
        this.ethBearTrend=ethBearTrend==null?BearTrendSnapshot.none("ethStructuralBearTrend"):ethBearTrend;
        this.solBearTrend=solBearTrend==null?BearTrendSnapshot.none("solStructuralBearTrend"):solBearTrend;
        this.btcBullLaunchTrend=btcBullLaunchTrend==null?BullTrendSnapshot.none("btcBullLaunchTrend"):btcBullLaunchTrend;
        this.btcBullTrend=btcBullTrend==null?BullTrendSnapshot.none("btcStructuralBullTrend"):btcBullTrend;
        this.btcBearTrend=btcBearTrend==null?BearTrendSnapshot.none("btcStructuralBearTrend"):btcBearTrend;
    }
}
