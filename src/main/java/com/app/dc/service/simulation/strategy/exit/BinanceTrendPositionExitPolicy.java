package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
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

    private void resetStructuralTracking(Position position) {
        position.trendStructuralStrengtheningBars = 0;
        position.lastStructuralConflictConfidence = Double.NaN;
    }
}
