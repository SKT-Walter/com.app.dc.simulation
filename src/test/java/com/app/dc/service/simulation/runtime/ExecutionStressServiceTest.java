package com.app.dc.service.simulation.runtime;

import com.app.dc.service.simulation.BacktestModels;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Collections;

public class ExecutionStressServiceTest {

    @Test
    public void adjustedPnlShouldSubtractSlippageSpreadAndLatencyFromBothLegs() {
        ExecutionStressService service = new ExecutionStressService();
        service.configureForTest(true, decimal("2"), decimal("3"), decimal("2"), decimal("1"));

        BacktestModels.TradeRecord trade = new BacktestModels.TradeRecord();
        trade.qty = decimal("2");
        trade.entryPrice = decimal("100");
        trade.exitPrice = decimal("110");
        BacktestModels.BacktestResult result = new BacktestModels.BacktestResult();
        result.totalPnl = decimal("25");
        result.tradeList = Collections.singletonList(trade);

        Assert.assertEquals(decimal("0.19000000"), service.additionalCost(result.tradeList));
        Assert.assertEquals(decimal("24.81000000"), service.adjustedPnl(result));
    }

    @Test
    public void disabledStressShouldPreserveRawPnl() {
        ExecutionStressService service = new ExecutionStressService();
        service.configureForTest(false, decimal("2"), decimal("3"), decimal("2"), decimal("1"));
        BacktestModels.BacktestResult result = new BacktestModels.BacktestResult();
        result.totalPnl = decimal("12.34");

        Assert.assertEquals(decimal("12.34000000"), service.adjustedPnl(result));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
