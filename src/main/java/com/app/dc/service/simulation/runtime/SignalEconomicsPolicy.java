package com.app.dc.service.simulation.runtime;

import com.app.dc.po.OCType;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

final class SignalEconomicsPolicy {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private SignalEconomicsPolicy() {
    }

    static boolean shouldReject(Signal signal,
                                double estimatedRoundTripCostPct,
                                double minTargetCostMultiple,
                                double minStopCostMultiple,
                                double minNetRewardRisk) {
        return !rejectionReason(signal, estimatedRoundTripCostPct, minTargetCostMultiple,
                minStopCostMultiple, minNetRewardRisk).isEmpty();
    }

    static String rejectionReason(Signal signal,
                                  double estimatedRoundTripCostPct,
                                  double minTargetCostMultiple,
                                  double minStopCostMultiple,
                                  double minNetRewardRisk) {
        if (signal == null || signal.side == null || Side.NONE.equals(signal.side)) {
            return "";
        }
        if (signal.ocType != null && !OCType.OPEN.equals(signal.ocType)) {
            return "";
        }
        if (!Double.isFinite(estimatedRoundTripCostPct) || estimatedRoundTripCostPct <= 0D
                || !Double.isFinite(minTargetCostMultiple) || minTargetCostMultiple <= 0D
                || !Double.isFinite(minStopCostMultiple) || minStopCostMultiple <= 0D
                || !Double.isFinite(minNetRewardRisk) || minNetRewardRisk <= 0D) {
            return "";
        }

        BigDecimal entry = positiveOrNull(signal.price);
        BigDecimal target = positiveOrNull(signal.takerPrice);
        if (entry == null || target == null) {
            return "";
        }

        BigDecimal targetDistance;
        if (Side.BUY.equals(signal.side)) {
            targetDistance = target.subtract(entry);
        } else if (Side.SELL.equals(signal.side)) {
            targetDistance = entry.subtract(target);
        } else {
            return "";
        }
        if (targetDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }

        BigDecimal estimatedCost = entry
                .multiply(BigDecimal.valueOf(estimatedRoundTripCostPct), MathContext.DECIMAL64)
                .divide(ONE_HUNDRED, MathContext.DECIMAL64);
        if (estimatedCost.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }

        BigDecimal minimumTargetDistance = estimatedCost
                .multiply(BigDecimal.valueOf(minTargetCostMultiple), MathContext.DECIMAL64);
        if (targetDistance.compareTo(minimumTargetDistance) < 0) {
            return "signal_economics_target_too_close";
        }

        BigDecimal stop = positiveOrNull(signal.stopPrice);
        if (stop == null) {
            return "";
        }
        BigDecimal stopDistance = Side.BUY.equals(signal.side)
                ? entry.subtract(stop)
                : stop.subtract(entry);
        if (stopDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }

        BigDecimal minimumStopDistance = estimatedCost
                .multiply(BigDecimal.valueOf(minStopCostMultiple), MathContext.DECIMAL64);
        if (stopDistance.compareTo(minimumStopDistance) < 0) {
            return "signal_economics_stop_too_close";
        }

        BigDecimal netReward = targetDistance.subtract(estimatedCost);
        BigDecimal netRisk = stopDistance.add(estimatedCost);
        if (netReward.compareTo(BigDecimal.ZERO) <= 0 || netRisk.compareTo(BigDecimal.ZERO) <= 0) {
            return "signal_economics_non_positive_net_reward";
        }
        BigDecimal netRewardRisk = netReward.divide(netRisk, 4, RoundingMode.HALF_UP);
        if (netRewardRisk.compareTo(BigDecimal.valueOf(minNetRewardRisk)) < 0) {
            return "signal_economics_net_reward_risk_too_low";
        }
        return "";
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? null : value;
    }
}
