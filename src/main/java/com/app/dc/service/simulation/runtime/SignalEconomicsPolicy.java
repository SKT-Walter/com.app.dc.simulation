package com.app.dc.service.simulation.runtime;

import com.app.dc.po.OCType;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;

import java.math.BigDecimal;
import java.math.MathContext;

final class SignalEconomicsPolicy {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private SignalEconomicsPolicy() {
    }

    static boolean shouldReject(Signal signal,
                                double estimatedRoundTripCostPct,
                                double minTargetCostMultiple) {
        if (signal == null || signal.side == null || Side.NONE.equals(signal.side)) {
            return false;
        }
        if (signal.ocType != null && !OCType.OPEN.equals(signal.ocType)) {
            return false;
        }
        if (!Double.isFinite(estimatedRoundTripCostPct) || estimatedRoundTripCostPct <= 0D
                || !Double.isFinite(minTargetCostMultiple) || minTargetCostMultiple <= 0D) {
            return false;
        }

        BigDecimal entry = positiveOrNull(signal.price);
        BigDecimal target = positiveOrNull(signal.takerPrice);
        if (entry == null || target == null) {
            return false;
        }

        BigDecimal targetDistance;
        if (Side.BUY.equals(signal.side)) {
            targetDistance = target.subtract(entry);
        } else if (Side.SELL.equals(signal.side)) {
            targetDistance = entry.subtract(target);
        } else {
            return false;
        }
        if (targetDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        BigDecimal estimatedCost = entry
                .multiply(BigDecimal.valueOf(estimatedRoundTripCostPct), MathContext.DECIMAL64)
                .divide(ONE_HUNDRED, MathContext.DECIMAL64);
        BigDecimal minimumDistance = estimatedCost
                .multiply(BigDecimal.valueOf(minTargetCostMultiple), MathContext.DECIMAL64);
        return estimatedCost.compareTo(BigDecimal.ZERO) > 0
                && targetDistance.compareTo(minimumDistance) < 0;
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? null : value;
    }
}
