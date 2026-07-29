package com.app.dc.simulation;

import com.app.dc.service.simulation.BacktestMetricService;
import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.EquityContext;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;

public class BacktestMetricServiceTest {

    @Test
    public void usesFixedTradeNotionalInsteadOfCompoundingEquity() {
        BacktestMetricService service = new BacktestMetricService();
        BacktestResult result = result();
        EquityContext equity = service.initEquityContext(10000, 10000);

        service.applyTrade(result, trade("0.10"), equity);
        service.applyTrade(result, trade("0.10"), equity);

        Assert.assertEquals(12000.0, equity.equity, 0.000001);
        Assert.assertEquals("1000.000000", result.tradeList.get(0).pnl.toPlainString());
        Assert.assertEquals("1000.000000", result.tradeList.get(1).pnl.toPlainString());
    }

    @Test
    public void retainsHistoricalMaximumDrawdownAfterEquityRecovers() {
        BacktestMetricService service = new BacktestMetricService();
        BacktestResult result = result();
        EquityContext equity = service.initEquityContext(10000, 10000);

        service.applyTrade(result, trade("-0.10"), equity);
        service.applyTrade(result, trade("0.20"), equity);

        Assert.assertEquals("0.100000", result.maxDrawdownPct.toPlainString());
        Assert.assertEquals(11000.0, equity.equity, 0.000001);
    }

    private BacktestResult result() {
        BacktestResult result = new BacktestResult();
        result.tradeList = new ArrayList<TradeRecord>();
        result.maxDrawdownPct = BigDecimal.ZERO;
        return result;
    }

    private TradeRecord trade(String returnPct) {
        TradeRecord trade = new TradeRecord();
        trade.returnPct = new BigDecimal(returnPct);
        trade.holdBars = 1;
        trade.exitReason = "test";
        return trade;
    }
}
