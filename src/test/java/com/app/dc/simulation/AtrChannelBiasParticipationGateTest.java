package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.strategy.core.deterministic.MarketParticipationGate;
import com.app.dc.strategy.core.deterministic.StrategyRoutingDecision;
import com.app.dc.strategy.core.strategy.range.AtrChannelBiasSetupSnapshot;
import org.junit.Assert;
import org.junit.Test;

public class AtrChannelBiasParticipationGateTest {
    private final MarketParticipationGate gate = new MarketParticipationGate();

    @Test
    public void unreadySolReservationBecomesExplicitNoTrade() {
        StrategyRoutingDecision decision = decision("atrChannelBiasReversionSOL");
        Assert.assertEquals("ATR_CHANNEL_WAITING_FOR_TOUCH", gate.rejection(
                "SOLUSDT", decision, null, AtrChannelBiasSetupSnapshot.watch(
                        "ATR_CHANNEL_WAITING_FOR_TOUCH"), null));
    }

    @Test
    public void armedAndTriggeredSetupsCanExecute() {
        StrategyRoutingDecision decision = decision("atrChannelBiasReversionSOL");
        AtrChannelBiasSetupSnapshot armed = new AtrChannelBiasSetupSnapshot(
                AtrChannelBiasSetupSnapshot.ARMED, "ATR_CHANNEL_TOUCH_ARMED",
                .82, Side.BUY, null, null, 10);
        AtrChannelBiasSetupSnapshot triggered = new AtrChannelBiasSetupSnapshot(
                AtrChannelBiasSetupSnapshot.TRIGGERED,
                "ATR_CHANNEL_RECOVERY_TRIGGERED", 1, Side.BUY,
                java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, 10);
        Assert.assertNull(gate.rejection("SOLUSDT", decision, null, armed, null));
        Assert.assertNull(gate.rejection("SOLUSDT", decision, null, triggered, null));
    }

    @Test
    public void gateDoesNotChangeOtherStrategiesOrSymbols() {
        Assert.assertNull(gate.rejection("SOLUSDT", decision("binanceTrendSOL"), null,
                AtrChannelBiasSetupSnapshot.watch("WAIT"), null));
        Assert.assertNull(gate.rejection("ETHUSDT",
                decision("atrChannelBiasReversionETH"), null,
                AtrChannelBiasSetupSnapshot.watch("WAIT"), null));
    }

    @Test
    public void lifecycleReservationSeparatesSetupWaitFromPositionRunning() {
        StrategyRoutingDecision momentum = decision("solMomentumBullTrendSOL");
        Assert.assertEquals("SOL_MOMENTUM_SETUP_NOT_UPDATED", gate.rejection(
                "SOLUSDT", momentum, null,
                AtrChannelBiasSetupSnapshot.watch("WAIT"), null));
        Assert.assertEquals("POSITION_RUNNING", gate.rejection(
                "SOLUSDT", momentum, null,
                AtrChannelBiasSetupSnapshot.watch("WAIT"),
                "solMomentumBullTrendSOL"));
        Assert.assertEquals("COMPRESSION_SETUP_NOT_UPDATED", gate.rejection(
                "SOLUSDT", decision("compressionBreakSOL"), null,
                AtrChannelBiasSetupSnapshot.watch("WAIT"), null));
    }

    private StrategyRoutingDecision decision(String strategyName) {
        StrategyRoutingDecision value = new StrategyRoutingDecision();
        value.strategyName = strategyName;
        return value;
    }
}
