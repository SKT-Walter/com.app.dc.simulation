package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestTradeService;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.ta4j.core.BaseBar;

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
    public void rejectsNonPositiveExplicitPricesInsteadOfUsingFallback() {
        BacktestParam param = param("6", "6");
        Assert.assertEquals(BacktestTradeService.INVALID_ENTRY_PRICE,
                service.validateOpenSignal(signal(Side.BUY, "0", "90", "110"), param));
        Assert.assertEquals(BacktestTradeService.INVALID_STOP_PRICE,
                service.validateOpenSignal(signal(Side.BUY, "100", "0", "110"), param));
        Assert.assertEquals(BacktestTradeService.INVALID_TAKE_PRICE,
                service.validateOpenSignal(signal(Side.BUY, "100", "90", "0"), param));
    }

    @Test public void bullTrendRemarkSuppressesFallbackTakeProfit(){
        BacktestParam param=param("6","10");Signal signal=signal(Side.BUY,"100","98",null);
        signal.remark="NO_FIXED_TAKE_PROFIT|ETH_PULLBACK_RECOVERY";
        com.app.dc.strategy.core.StrategyRuntimeModels.Position position=service.openPosition(signal,0,
                new BaseBar(Duration.ofMinutes(15),ZonedDateTime.now(),new BigDecimal("99"),
                        new BigDecimal("101"),new BigDecimal("98"),new BigDecimal("100"),BigDecimal.ONE),param);
        Assert.assertNull(position.takePrice);Assert.assertEquals(98d,position.stopPrice,.000001);
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
