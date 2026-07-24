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
    public void preservesHistoricalMaximumDrawdownAfterEquityRecovers() {
        BacktestMetricService service = new BacktestMetricService();
        EquityContext equity = service.initEquityContext(10000);
        BacktestResult result = result();

        service.applyTrade(result, trade("0.10"), equity);
        service.applyTrade(result, trade("-0.05"), equity);
        service.applyTrade(result, trade("0.01"), equity);

        Assert.assertEquals(new BigDecimal("0.050000"), result.maxDrawdownPct);
    }

    private BacktestResult result() {
        BacktestResult result = new BacktestResult();
        result.initialCapital = new BigDecimal("10000");
        result.tradeList = new ArrayList<TradeRecord>();
        return result;
    }

    private TradeRecord trade(String returnPct) {
        TradeRecord trade = new TradeRecord();
        trade.returnPct = new BigDecimal(returnPct);
        trade.holdBars = 1;
        return trade;
    }
}
