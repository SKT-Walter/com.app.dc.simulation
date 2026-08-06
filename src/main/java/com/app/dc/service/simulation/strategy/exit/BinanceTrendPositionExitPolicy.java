package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import com.app.dc.service.simulation.strategy.trend.BinanceTrendSettings;
import com.app.dc.service.simulation.strategy.trend.TrendLifecycleSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** Close-based trend invalidation for positions opened by binanceTrend. */
@Service
public class BinanceTrendPositionExitPolicy implements StrategyPositionExitPolicy {
    public static final String CONSENSUS_CONFIRMATION = "trend_consensus_confirmation_exit";

    @Value("${backtest.binance-trend.regime-exit-confirmation-bars:3}")
    private int regimeConfirmationBars;
    @Value("${backtest.binance-trend.structural-exit-confirmation-bars:3}")
    private int structuralConfirmationBars;
    @Value("${backtest.binance-trend.structural-exit-minimum-confidence:0.60}")
    private double structuralMinimumConfidence;
    @Value("${backtest.binance-trend.structural-confidence-increase-epsilon:0.005}")
    private double confidenceIncreaseEpsilon;
    @Autowired(required=false) private SymbolStrategyProfileService profiles;

    @Override
    public boolean supports(String strategyName) {
        return "binanceTrend".equalsIgnoreCase(strategyName);
    }

    @Override
    public PositionExitDecision evaluate(Position position, StrategyPositionExitContext context) {
        BarSeries series = context == null ? null : context.series;
        if (position == null || series == null || series.getBarCount() < 56
                || series.getEndIndex() <= position.entryIndex) return PositionExitDecision.hold();
        boolean shortPosition = position.side == Side.SELL;
        boolean longPosition = position.side == Side.BUY;
        if (!shortPosition && !longPosition) return PositionExitDecision.hold();
        BinanceTrendSettings settings=profiles==null?BinanceTrendSettings.legacy()
                :profiles.binanceTrendSettings(context.symbol,context.timeframe);
        if(settings.lifecycleEnabled)return lifecycleExit(position,context,settings);

        boolean regimeConflict = context.regime != null
                && (shortPosition ? "UP".equals(context.regime.trend)
                                  : "DOWN".equals(context.regime.trend));
        position.trendRegimeConflictBars = regimeConflict
                ? position.trendRegimeConflictBars + 1 : 0;
        boolean regimeConfirmed = position.trendRegimeConflictBars
                >= Math.max(1, regimeConfirmationBars);

        int end = series.getEndIndex();
        double fast = BinanceStrategyMath.sma(series, end, 9);
        double mid = BinanceStrategyMath.sma(series, end, 21);
        double previousFast = BinanceStrategyMath.sma(series, end - 1, 9);
        double previousMid = BinanceStrategyMath.sma(series, end - 1, 21);
        boolean crossed = shortPosition
                ? previousFast <= previousMid && fast > mid
                : previousFast >= previousMid && fast < mid;
        boolean maAlignmentInvalid = shortPosition ? fast > mid : fast < mid;

        double close = BinanceStrategyMath.close(series, end);
        double slow = BinanceStrategyMath.sma(series, end, 55);
        boolean ma55Invalid = shortPosition ? close > slow : close < slow;
        // A crossing is useful evidence on the transition bar. Afterwards the
        // invalid alignment remains evidence, so a three-bar Regime confirmation
        // cannot miss the exit merely because the cross happened one bar earlier.
        boolean technicalInvalidation = crossed || maAlignmentInvalid || ma55Invalid;

        StructuralTrendSnapshot structural = context.structuralTrend;
        boolean structuralConflict = structural != null && structural.ready
                && structural.confidence >= structuralMinimumConfidence
                && (shortPosition ? structural.isBull() : structural.isBear());
        if (!structuralConflict) {
            resetStructuralTracking(position);
        } else {
            boolean firstConflict = !Double.isFinite(position.lastStructuralConflictConfidence);
            boolean strengthening = !firstConflict
                    && structural.confidence >= position.lastStructuralConflictConfidence
                        + Math.max(0, confidenceIncreaseEpsilon);
            position.trendStructuralStrengtheningBars = firstConflict
                    ? 1 : strengthening ? position.trendStructuralStrengtheningBars + 1 : 1;
            position.lastStructuralConflictConfidence = structural.confidence;
        }
        boolean structuralConfirmed = position.trendStructuralStrengtheningBars
                >= Math.max(1, structuralConfirmationBars);

        // None of the three observations can close a trend position alone.
        // Requiring fast Regime, MA invalidation and slow structure to agree
        // filters ordinary 15m pullbacks without blocking counter-structure entry.
        if (technicalInvalidation && regimeConfirmed && structuralConfirmed)
            return PositionExitDecision.exit(CONSENSUS_CONFIRMATION);
        return PositionExitDecision.hold();
    }

    private PositionExitDecision lifecycleExit(Position position,
                                                StrategyPositionExitContext context,
                                                BinanceTrendSettings settings){
        BarSeries series=context.series;int end=series.getEndIndex();
        double atr=BinanceStrategyMath.atr(series,end,14);
        if(!Double.isFinite(atr)||atr<=0)return PositionExitDecision.hold();
        boolean buy=position.side==Side.BUY;
        StructuralTrendSnapshot structural=context.structuralTrend;
        boolean reversed=structural!=null&&structural.ready
                &&(buy?structural.isBear():structural.isBull());
        TrendLifecycleSnapshot lifecycle=context.trendLifecycle;
        boolean invalidated=lifecycle!=null
                &&TrendLifecycleSnapshot.INVALIDATED.equals(lifecycle.phase);
        if(reversed||invalidated){
            position.exitLifecyclePhase=reversed?"SLOW_STRUCTURE_REVERSED":lifecycle.reason;
            return PositionExitDecision.exit(reversed?"trend_slow_structure_reversed"
                    :"trend_lifecycle_invalidated");
        }

        double favorableDistance=buy?position.highestSinceEntry-position.entryPrice
                :position.entryPrice-position.lowestSinceEntry;
        double favorableAtr=favorableDistance/atr;
        if(favorableAtr<settings.trailActivationAtr)return PositionExitDecision.hold();
        position.trendTrailingActive=true;
        double multiplier=favorableAtr>=settings.matureTrailActivationAtr
                ?settings.matureTrailAtr:settings.initialTrailAtr;
        double close=BinanceStrategyMath.close(series,end);
        double chandelier=buy?position.highestSinceEntry-multiplier*atr
                :position.lowestSinceEntry+multiplier*atr;
        double pivot=pivotStop(series,end,buy,atr,settings.stopPaddingAtr);
        double candidate=buy?Math.max(chandelier,pivot):Math.min(chandelier,pivot);
        candidate=buy?Math.min(candidate,close-atr):Math.max(candidate,close+atr);
        if(Double.isFinite(candidate)&&candidate>0){
            if(buy&&(position.stopPrice==null||candidate>position.stopPrice))position.stopPrice=candidate;
            if(!buy&&(position.stopPrice==null||candidate<position.stopPrice))position.stopPrice=candidate;
        }
        return PositionExitDecision.hold();
    }

    private double pivotStop(BarSeries series,int end,boolean buy,double atr,double padding){
        int candidate=end-2;if(candidate<series.getBeginIndex()+2)return buy?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
        double value=buy?series.getBar(candidate).getLowPrice().doubleValue()
                :series.getBar(candidate).getHighPrice().doubleValue();
        for(int i=candidate-2;i<=candidate+2;i++){
            double compared=buy?series.getBar(i).getLowPrice().doubleValue()
                    :series.getBar(i).getHighPrice().doubleValue();
            if(buy&&compared<value||!buy&&compared>value)
                return buy?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
        }
        return buy?value-padding*atr:value+padding*atr;
    }

    private void resetStructuralTracking(Position position) {
        position.trendStructuralStrengtheningBars = 0;
        position.lastStructuralConflictConfidence = Double.NaN;
    }
}
