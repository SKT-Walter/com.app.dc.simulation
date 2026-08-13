package com.app.dc.service.simulation;

import org.junit.Assert;
import org.junit.Test;

public class BacktestServiceSymbolTest {

    @Test
    public void firstSymbolShouldPreserveCandidateScopeInsteadOfDefaultingToEth() {
        Assert.assertEquals("BNBUSDT", BacktestService.firstSymbol("BNBUSDT"));
        Assert.assertEquals("BTCUSDT", BacktestService.firstSymbol("BTCUSDT|ETHUSDT"));
        Assert.assertEquals("", BacktestService.firstSymbol(""));
    }
}
