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
        Assert.assertTrue(SignalEconomicsPolicy.shouldReject(
                signal(Side.SELL, "720.73", "719.925"), 0.08D, 2.0D));
        Assert.assertFalse(SignalEconomicsPolicy.shouldReject(
                signal(Side.BUY, "100", "100.30"), 0.08D, 2.0D));
    }

    @Test
    public void shouldNeverRejectCloseSignal() {
        Signal signal = signal(Side.SELL, "720.73", "719.925");
        signal.ocType = OCType.ClOSE;

        Assert.assertFalse(SignalEconomicsPolicy.shouldReject(signal, 0.08D, 2.0D));
    }

    private Signal signal(Side side, String entry, String target) {
        Signal signal = new Signal();
        signal.side = side;
        signal.ocType = OCType.OPEN;
        signal.price = new BigDecimal(entry);
        signal.takerPrice = new BigDecimal(target);
        return signal;
    }
}
