package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.BacktestTradeService;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;

public class BacktestTradeServiceTest {

    private final BacktestTradeService service = new BacktestTradeService();

    @Test
    public void rejectsRiskPricesOnWrongSideOfEntry() {
        BacktestParam param = param("6", "6");

        Assert.assertEquals(BacktestTradeService.INVALID_STOP_PRICE,
                service.validateOpenSignal(signal(Side.BUY, "100", "100", "110"), param));
        Assert.assertEquals(BacktestTradeService.INVALID_TAKE_PRICE,
                service.validateOpenSignal(signal(Side.BUY, "100", "90", "100"), param));
        Assert.assertEquals(BacktestTradeService.INVALID_STOP_PRICE,
                service.validateOpenSignal(signal(Side.SELL, "100", "100", "90"), param));
        Assert.assertEquals(BacktestTradeService.INVALID_TAKE_PRICE,
                service.validateOpenSignal(signal(Side.SELL, "100", "110", "100"), param));
    }

    @Test
    public void acceptsLegalAndFallbackRiskPrices() {
        BacktestParam fallback = param("6", "6");
        Assert.assertNull(service.validateOpenSignal(signal(Side.BUY, "100", null, null), fallback));
        Assert.assertNull(service.validateOpenSignal(signal(Side.SELL, "100", null, null), fallback));
        Assert.assertNull(service.validateOpenSignal(signal(Side.BUY, "100", "94", "106"), fallback));
        Assert.assertNull(service.validateOpenSignal(signal(Side.SELL, "100", "106", "94"), fallback));

        BacktestParam noFallback = param("0", "0");
        Assert.assertNull(service.validateOpenSignal(signal(Side.BUY, "100", null, null), noFallback));
    }

    @Test
    public void trailingStopCanOnlyTightenRisk() {
        BacktestTradeService service = new BacktestTradeService();
        Position buy = new Position();
        buy.side = Side.BUY;
        buy.stopPrice = 95.0;
        service.tightenStop(buy, 101.0, 105.0);
        Assert.assertEquals(101.0, buy.stopPrice, 0.0);
        service.tightenStop(buy, 99.0, 105.0);
        Assert.assertEquals(101.0, buy.stopPrice, 0.0);

        Position sell = new Position();
        sell.side = Side.SELL;
        sell.stopPrice = 105.0;
        service.tightenStop(sell, 99.0, 95.0);
        Assert.assertEquals(99.0, sell.stopPrice, 0.0);
        service.tightenStop(sell, 101.0, 95.0);
        Assert.assertEquals(99.0, sell.stopPrice, 0.0);
    }

    @Test
    public void rejectsNonPositiveExplicitPricesInsteadOfUsingFallback() {
        BacktestParam param = param("6", "6");
        Assert.assertEquals(BacktestTradeService.INVALID_ENTRY_PRICE,
                service.validateOpenSignal(signal(Side.BUY, "0", "90", "110"), param));
        Assert.assertEquals(BacktestTradeService.INVALID_STOP_PRICE,
                service.validateOpenSignal(signal(Side.BUY, "100", "0", "110"), param));
        Assert.assertEquals(BacktestTradeService.INVALID_TAKE_PRICE,
                service.validateOpenSignal(signal(Side.BUY, "100", "90", "0"), param));
    }

    private BacktestParam param(String stop, String take) {
        BacktestParam param = new BacktestParam();
        param.fallbackStopLossPct = new BigDecimal(stop);
        param.fallbackTakeProfitPct = new BigDecimal(take);
        return param;
    }

    private Signal signal(Side side, String entry, String stop, String take) {
        Signal signal = new Signal();
        signal.side = side;
        signal.price = entry == null ? null : new BigDecimal(entry);
        signal.stopPrice = stop == null ? null : new BigDecimal(stop);
        signal.takerPrice = take == null ? null : new BigDecimal(take);
        return signal;
    }
}
