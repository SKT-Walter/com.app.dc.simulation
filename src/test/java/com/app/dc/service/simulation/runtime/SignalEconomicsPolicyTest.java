package com.app.dc.service.simulation.runtime;

import com.app.dc.po.OCType;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;

public class SignalEconomicsPolicyTest {

    @Test
    public void shouldMatchLiveEconomicsBoundary() {
        Signal targetTooCloseWithoutStop = signal(Side.SELL, "720.73", "719.925", "721");
        targetTooCloseWithoutStop.stopPrice = null;
        Assert.assertTrue(SignalEconomicsPolicy.shouldReject(
                targetTooCloseWithoutStop, 0.08D, 2.0D, 1.5D, 1.2D));
        Assert.assertFalse(SignalEconomicsPolicy.shouldReject(
                signal(Side.BUY, "100", "101.00", "99.60"), 0.08D, 2.0D, 1.5D, 1.2D));
    }

    @Test
    public void shouldRejectStopThatBarelyCoversRoundTripCost() {
        Assert.assertEquals("signal_economics_stop_too_close",
                SignalEconomicsPolicy.rejectionReason(
                        signal(Side.BUY, "0.33488", "0.33565142857142866", "0.3345714285714286"),
                        0.08D, 2.0D, 1.5D, 1.2D));
    }

    @Test
    public void shouldRejectLowFeeAdjustedRewardRisk() {
        Assert.assertEquals("signal_economics_net_reward_risk_too_low",
                SignalEconomicsPolicy.rejectionReason(
                        signal(Side.BUY, "100", "100.20", "99.88"),
                        0.08D, 2.0D, 1.5D, 1.2D));
    }

    @Test
    public void shouldNeverRejectCloseSignal() {
        Signal signal = signal(Side.SELL, "720.73", "719.925", "721");
        signal.ocType = OCType.ClOSE;

        Assert.assertFalse(SignalEconomicsPolicy.shouldReject(signal, 0.08D, 2.0D, 1.5D, 1.2D));
    }

    private Signal signal(Side side, String entry, String target, String stop) {
        Signal signal = new Signal();
        signal.side = side;
        signal.ocType = OCType.OPEN;
        signal.price = new BigDecimal(entry);
        signal.takerPrice = new BigDecimal(target);
        signal.stopPrice = new BigDecimal(stop);
        return signal;
    }
}
