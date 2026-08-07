package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import org.springframework.stereotype.Service;

/** Protects a confirmed ETH range reversal after it has produced meaningful excursion. */
@Service
public class BinanceRangePositionExitPolicy implements StrategyPositionExitPolicy {
    static final double ACTIVATION_R = .50;
    static final double ROUND_TRIP_FEE_BUFFER = .0008;

    @Override public boolean supports(String strategyName) {
        return "binanceRange".equalsIgnoreCase(strategyName);
    }

    @Override public PositionExitDecision evaluate(Position position,
                                                   StrategyPositionExitContext context) {
        if (position == null || context == null || context.series == null
                || !"ETHUSDT".equalsIgnoreCase(context.symbol)
                || !"15M".equalsIgnoreCase(context.timeframe)
                || !Double.isFinite(position.initialRiskPriceDistance)
                || position.initialRiskPriceDistance <= 0)
            return PositionExitDecision.hold();
        double close = context.series.getLastBar().getClosePrice().doubleValue();
        double favorableDistance = position.side == Side.BUY
                ? position.highestSinceEntry - position.entryPrice
                : position.entryPrice - position.lowestSinceEntry;
        if (!Double.isFinite(favorableDistance)
                || favorableDistance < ACTIVATION_R * position.initialRiskPriceDistance)
            return PositionExitDecision.hold();

        double candidate = position.side == Side.BUY
                ? position.entryPrice * (1 + ROUND_TRIP_FEE_BUFFER)
                : position.entryPrice * (1 - ROUND_TRIP_FEE_BUFFER);
        boolean closeStillFavorable = position.side == Side.BUY
                ? close > candidate : close < candidate;
        boolean tightens = position.side == Side.BUY
                ? position.stopPrice == null || candidate > position.stopPrice
                : position.stopPrice == null || candidate < position.stopPrice;
        if (closeStillFavorable && tightens) {
            position.stopPrice = candidate;
            position.stopExitReason = "range_breakeven_protection_exit";
            position.exitLifecyclePhase = "RANGE_BREAKEVEN_PROTECTED";
        }
        return PositionExitDecision.hold();
    }
}
