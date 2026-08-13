package com.app.dc.service.simulation;

import com.app.dc.po.backtest.BacktestParam;
import org.junit.Assert;
import org.junit.Test;

public class BacktestServiceSymbolTest {

    @Test
    public void firstSymbolShouldPreserveCandidateScopeInsteadOfDefaultingToEth() {
        Assert.assertEquals("BNBUSDT", BacktestService.firstSymbol("BNBUSDT"));
        Assert.assertEquals("BTCUSDT", BacktestService.firstSymbol("BTCUSDT|ETHUSDT"));
        Assert.assertEquals("", BacktestService.firstSymbol(""));
    }

    @Test
    public void normalizeParamShouldTreatSymbolsAsAuthoritativeScope() {
        BacktestParam param = new BacktestParam();
        param.symbol = "ETHUSDT";
        param.symbols = "BNBUSDT";

        BacktestService.normalizeSymbolScope(param);

        Assert.assertEquals("BNBUSDT", param.symbol);
        Assert.assertEquals("BNBUSDT", param.symbols);
    }
}
