package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** ETH channel-breakout ownership: protect open profit and trail a continuing move. */
@Service
public class BinanceChannelPositionExitPolicy implements StrategyPositionExitPolicy {
    @Override public boolean supports(String strategyName) {
        return "binanceChannel".equalsIgnoreCase(strategyName);
    }

    @Override public PositionExitDecision evaluate(Position position,
                                                   StrategyPositionExitContext context) {
        if (position == null || position.side != Side.BUY || context == null
                || context.series == null || !"ETHUSDT".equalsIgnoreCase(context.symbol)
                || !"15M".equalsIgnoreCase(context.timeframe))
            return PositionExitDecision.hold();
        StructuralTrendSnapshot structural = context.structuralTrend;
        if (structural != null && structural.ready && structural.isBear()) {
            position.exitLifecyclePhase = "CHANNEL_SLOW_STRUCTURE_REVERSED";
            return PositionExitDecision.exit("channel_slow_structure_reversed");
        }
        BarSeries series = context.series; int end = series.getEndIndex();
        if (end <= position.entryIndex) return PositionExitDecision.hold();
        double atr = BinanceStrategyMath.atr(series, end, 14);
        double close = BinanceStrategyMath.close(series, end);
        double entryAtr = Double.isFinite(position.entryAtr) && position.entryAtr > 0
                ? position.entryAtr : atr;
        if (!Double.isFinite(atr) || atr <= 0 || !Double.isFinite(entryAtr) || entryAtr <= 0)
            return PositionExitDecision.hold();

        if (Double.isFinite(position.channelBreakoutLevel)) {
            boolean failed = close < position.channelBreakoutLevel
                    && close < BinanceStrategyMath.sma(series, end, 20);
            position.channelInvalidationBars = failed ? position.channelInvalidationBars + 1 : 0;
            if (position.channelInvalidationBars >= 2) {
                position.exitLifecyclePhase = "CHANNEL_BREAKOUT_FAILED";
                return PositionExitDecision.exit("channel_breakout_failed");
            }
        }

        double favorableAtr = (position.highestSinceEntry - position.entryPrice) / entryAtr;
        if (favorableAtr < 1.5 || close <= position.entryPrice) return PositionExitDecision.hold();
        double multiplier = favorableAtr >= 4 ? 2.0 : 2.5;
        double breakeven = position.entryPrice * 1.0008;
        double candidate = Math.max(breakeven, position.highestSinceEntry - multiplier * atr);
        candidate = Math.min(candidate, close - .25 * atr);
        if (Double.isFinite(candidate) && candidate > 0
                && (position.stopPrice == null || candidate > position.stopPrice)) {
            position.stopPrice = candidate;
            position.stopExitReason = favorableAtr >= 4
                    ? "channel_mature_trailing_exit" : "channel_breakeven_trailing_exit";
            position.exitLifecyclePhase = favorableAtr >= 4
                    ? "CHANNEL_MATURE_TRAILING" : "CHANNEL_BREAKEVEN_PROTECTED";
            position.trendTrailingActive = true;
        }
        return PositionExitDecision.hold();
    }
}
