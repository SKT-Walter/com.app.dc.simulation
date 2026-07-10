package com.app.dc.service.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.backtest.BacktestParam;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.Bar;
import org.ta4j.core.num.DecimalNum;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

public class BacktestTradeServiceTest {

    @Test
    public void openPositionShouldFallbackToBarCloseWhenSignalPriceIsZero() {
        BacktestTradeService service = new BacktestTradeService();
        Signal signal = new Signal();
        signal.side = Side.BUY;
        signal.price = BigDecimal.ZERO;
        signal.stopPrice = BigDecimal.valueOf(95);
        signal.takerPrice = BigDecimal.valueOf(110);

        BacktestParam param = new BacktestParam();
        param.maxHoldBars = 12;

        BacktestModels.Position position = service.openPosition(
                signal,
                7,
                fakeBar("2026-06-23T10:15:00Z", 100.0d),
                param,
                1000.0d);

        Assert.assertEquals(100.0d, position.entryPrice, 0.000001d);
        Assert.assertEquals(0.0d, position.signalPrice, 0.000001d);
        Assert.assertEquals(10.0d, position.qty, 0.000001d);

        BacktestModels.TradeRecord record = service.closePosition(
                position,
                110.0d,
                "2026-06-23T10:30:00Z",
                "take_profit",
                8,
                0.02d,
                0.05d);

        Assert.assertEquals(new BigDecimal("100.000000"), record.entryPrice);
        Assert.assertEquals(new BigDecimal("110.000000"), record.exitPrice);
        Assert.assertEquals(new BigDecimal("0.100000"), record.grossReturnPct);
    }

    @Test
    public void losingLongMoveMustNotTriggerTrailingProfitProtection() {
        BacktestTradeService service = new BacktestTradeService();
        BacktestModels.Position position = position(Side.BUY, 100.0d, 95.0d);

        BacktestModels.TradeRecord record = service.tryCloseByRisk(
                position,
                fakeBar("2026-06-23T10:30:00Z", 100.0d, 98.0d, 99.0d),
                8,
                0.02d,
                0.05d);

        Assert.assertNull(record);
        Assert.assertEquals(95.0d, position.stopPrice.doubleValue(), 0.000001d);
    }

    @Test
    public void losingShortMoveMustNotTriggerTrailingProfitProtection() {
        BacktestTradeService service = new BacktestTradeService();
        BacktestModels.Position position = position(Side.SELL, 100.0d, 105.0d);

        BacktestModels.TradeRecord record = service.tryCloseByRisk(
                position,
                fakeBar("2026-06-23T10:30:00Z", 102.0d, 100.0d, 101.0d),
                8,
                0.02d,
                0.05d);

        Assert.assertNull(record);
        Assert.assertEquals(105.0d, position.stopPrice.doubleValue(), 0.000001d);
    }

    @Test
    public void favorableMoveShouldStillRaiseTrailingProtection() {
        BacktestTradeService service = new BacktestTradeService();
        BacktestModels.Position longPosition = position(Side.BUY, 100.0d, 95.0d);
        BacktestModels.Position shortPosition = position(Side.SELL, 100.0d, 105.0d);

        service.tryCloseByRisk(longPosition,
                fakeBar("2026-06-23T10:30:00Z", 101.0d, 100.0d, 101.0d),
                8, 0.02d, 0.05d);
        service.tryCloseByRisk(shortPosition,
                fakeBar("2026-06-23T10:30:00Z", 100.0d, 99.0d, 99.0d),
                8, 0.02d, 0.05d);

        Assert.assertEquals(100.5d, longPosition.stopPrice.doubleValue(), 0.000001d);
        Assert.assertEquals(99.5d, shortPosition.stopPrice.doubleValue(), 0.000001d);
    }

    @Test
    public void limitOrderShouldOnlyFillWhenNextBarTouchesPrice() {
        BacktestTradeService service = new BacktestTradeService();
        Signal signal = new Signal();
        signal.side = Side.BUY;
        signal.orderType = "LIMIT";
        signal.price = BigDecimal.valueOf(99.0d);
        signal.stopPrice = BigDecimal.valueOf(95.0d);
        signal.takerPrice = BigDecimal.valueOf(105.0d);
        BacktestParam param = new BacktestParam();
        param.entryMakerFeeRatePct = BigDecimal.valueOf(0.02d);

        Assert.assertNull(service.tryOpenLimitPosition(signal, 8,
                fakeBar("2026-06-23T10:30:00Z", 101.0d, 100.0d, 100.5d),
                param, 1000.0d));

        BacktestModels.Position filled = service.tryOpenLimitPosition(signal, 9,
                fakeBar("2026-06-23T10:45:00Z", 100.0d, 98.5d, 99.5d),
                param, 1000.0d);
        Assert.assertNotNull(filled);
        Assert.assertEquals(99.0d, filled.entryPrice, 0.000001d);
        Assert.assertEquals(0.02d, filled.entryFeeRatePct.doubleValue(), 0.000001d);
    }

    @Test
    public void marketEntryShouldUseProvidedTakerFee() {
        BacktestTradeService service = new BacktestTradeService();
        Signal signal = new Signal();
        signal.side = Side.BUY;
        signal.orderType = "MARKET";
        signal.price = BigDecimal.valueOf(100.0d);
        signal.stopPrice = BigDecimal.valueOf(95.0d);
        signal.takerPrice = BigDecimal.valueOf(110.0d);
        BacktestParam param = new BacktestParam();

        BacktestModels.Position position = service.openPosition(signal, 7,
                fakeBar("2026-06-23T10:15:00Z", 100.0d), param, 1000.0d, 0.05d);
        BacktestModels.TradeRecord record = service.closePosition(position, 101.0d,
                "2026-06-23T10:30:00Z", "take_profit", 8, 0.02d, 0.05d);

        Assert.assertEquals(new BigDecimal("0.050000"), record.entryFeeRatePct);
        Assert.assertEquals(new BigDecimal("1.000000"), record.totalFee);
        Assert.assertEquals(new BigDecimal("0.009000"), record.returnPct);
    }

    private static BacktestModels.Position position(Side side, double entryPrice, double stopPrice) {
        BacktestModels.Position position = new BacktestModels.Position();
        position.side = side;
        position.entryPrice = entryPrice;
        position.stopPrice = stopPrice;
        position.takePrice = side == Side.BUY ? 110.0d : 90.0d;
        position.fallbackTriggerProfitPct = 0.5d;
        position.fallbackTakeProfitPct = 0.4d;
        position.trailingFirstStepPct = 0.8d;
        position.trailingStepPct = 0.3d;
        position.entryIndex = 7;
        position.entryCapital = 1000.0d;
        position.qty = 10.0d;
        return position;
    }

    private static Bar fakeBar(String endTime, double closePrice) {
        return fakeBar(endTime, closePrice, closePrice, closePrice);
    }

    private static Bar fakeBar(String endTime, double highPrice, double lowPrice, double closePrice) {
        final ZonedDateTime barEndTime = ZonedDateTime.parse(endTime);
        final org.ta4j.core.num.Num barClosePrice = DecimalNum.valueOf(closePrice);
        final org.ta4j.core.num.Num barHighPrice = DecimalNum.valueOf(highPrice);
        final org.ta4j.core.num.Num barLowPrice = DecimalNum.valueOf(lowPrice);
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("getEndTime".equals(name)) {
                    return barEndTime;
                }
                if ("getClosePrice".equals(name)) {
                    return barClosePrice;
                }
                if ("getHighPrice".equals(name)) {
                    return barHighPrice;
                }
                if ("getLowPrice".equals(name)) {
                    return barLowPrice;
                }
                if ("toString".equals(name)) {
                    return "FakeBar(" + barEndTime + "," + closePrice + ")";
                }
                if ("hashCode".equals(name)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(name)) {
                    return Boolean.valueOf(proxy == args[0]);
                }
                throw new UnsupportedOperationException("Unsupported Bar method: " + name);
            }
        };
        return (Bar) Proxy.newProxyInstance(
                BacktestTradeServiceTest.class.getClassLoader(),
                new Class[]{Bar.class},
                handler);
    }
}
