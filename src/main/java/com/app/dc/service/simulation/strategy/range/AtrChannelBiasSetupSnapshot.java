package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import java.math.BigDecimal;

/** Immutable ATR-channel setup state shared by routing, scoring and execution. */
public final class AtrChannelBiasSetupSnapshot {
    public static final String WATCH = "WATCH";
    public static final String PREPARING = "PREPARING";
    public static final String ARMED = "ARMED";
    public static final String TRIGGERED = "TRIGGERED";

    public final String phase;
    public final String reason;
    public final double readiness;
    public final Side side;
    public final BigDecimal stopPrice;
    public final BigDecimal takePrice;
    public final int validUntilIndex;

    public AtrChannelBiasSetupSnapshot(String phase, String reason,
                                       double readiness, Side side,
                                       BigDecimal stopPrice, BigDecimal takePrice,
                                       int validUntilIndex) {
        this.phase = phase;
        this.reason = reason;
        this.readiness = readiness;
        this.side = side;
        this.stopPrice = stopPrice;
        this.takePrice = takePrice;
        this.validUntilIndex = validUntilIndex;
    }

    public boolean routingReady() {
        return ARMED.equals(phase) || TRIGGERED.equals(phase);
    }

    public boolean triggered() {
        return TRIGGERED.equals(phase) && side != null && side != Side.NONE;
    }

    public static AtrChannelBiasSetupSnapshot watch(String reason) {
        return new AtrChannelBiasSetupSnapshot(WATCH, reason, 0, Side.NONE,
                null, null, -1);
    }
}
