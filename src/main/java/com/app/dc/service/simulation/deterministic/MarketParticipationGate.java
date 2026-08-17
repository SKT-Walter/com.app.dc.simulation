package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.strategy.SymbolStrategyNames;
import com.app.dc.service.simulation.strategy.range.AtrChannelBiasSetupSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
import org.springframework.stereotype.Service;

/** Converts an unready reserved strategy into explicit NO_TRADE execution. */
@Service
public class MarketParticipationGate {
    public String rejection(String symbol, StrategyRoutingDecision decision,
                            StrategyEvaluationContext context,
                            AtrChannelBiasSetupSnapshot atrChannel,
                            String positionOwner) {
        if (!("SOLUSDT".equalsIgnoreCase(symbol) || "BTCUSDT".equalsIgnoreCase(symbol))
                || decision == null) return null;
        String strategy = SymbolStrategyNames.baseName(decision.strategyName);
        if (strategy == null) return null;
        if (sameBase(strategy, positionOwner)) return "POSITION_RUNNING";
        if ("atrChannelBiasReversion".equalsIgnoreCase(strategy)) {
            if (atrChannel != null && atrChannel.routingReady()) return null;
            return atrChannel == null ? "ATR_CHANNEL_NOT_UPDATED" : atrChannel.reason;
        }
        if ("BTCUSDT".equalsIgnoreCase(symbol)) {
            if ("btcBullLaunchTrend".equalsIgnoreCase(strategy))
                return bullReason("BTC_LAUNCH", context == null ? null : context.btcBullLaunchTrend);
            if ("btcStructuralBearTrend".equalsIgnoreCase(strategy))
                return bearReason("BTC_BEAR", context == null ? null : context.btcBearTrend);
            return null;
        }
        if (!"SOLUSDT".equalsIgnoreCase(symbol)) return null;
        if ("solMomentumBullTrend".equalsIgnoreCase(strategy))
            return bullReason("SOL_MOMENTUM", context == null ? null : context.solBullTrend);
        if ("solBullLaunchTrend".equalsIgnoreCase(strategy))
            return bullReason("SOL_LAUNCH", context == null ? null : context.solBullLaunchTrend);
        if ("solStructuralBearTrend".equalsIgnoreCase(strategy))
            return bearReason(context == null ? null : context.solBearTrend);
        if ("compressionBreak".equalsIgnoreCase(strategy))
            return compressionReason(context == null ? null : context.trendCompression);
        return null;
    }

    private String bullReason(String prefix, BullTrendSnapshot value) {
        if (value == null) return prefix + "_SETUP_NOT_UPDATED";
        if (BullTrendSnapshot.ARMED.equals(value.phase)
                || BullTrendSnapshot.TRIGGERED.equals(value.phase)) return null;
        return prefix + "_" + value.phase;
    }

    private String bearReason(BearTrendSnapshot value) {
        return bearReason("SOL_BEAR", value);
    }

    private String bearReason(String prefix, BearTrendSnapshot value) {
        if (value == null) return prefix + "_SETUP_NOT_UPDATED";
        if (BearTrendSnapshot.ARMED.equals(value.phase)
                || BearTrendSnapshot.TRIGGERED.equals(value.phase)) return null;
        return prefix + "_" + value.phase;
    }

    private String compressionReason(TrendCompressionSnapshot value) {
        if (value == null) return "COMPRESSION_SETUP_NOT_UPDATED";
        if (TrendCompressionSnapshot.ARMED.equals(value.phase)
                || TrendCompressionSnapshot.BREAKOUT_PENDING.equals(value.phase)
                || TrendCompressionSnapshot.TRIGGERED.equals(value.phase)) return null;
        return "COMPRESSION_" + value.phase;
    }

    private boolean sameBase(String strategy, String positionOwner) {
        return positionOwner != null && strategy.equalsIgnoreCase(
                SymbolStrategyNames.baseName(positionOwner));
    }
}
