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

    private static Bar fakeBar(String endTime, double closePrice) {
        final ZonedDateTime barEndTime = ZonedDateTime.parse(endTime);
        final org.ta4j.core.num.Num barClosePrice = DecimalNum.valueOf(closePrice);
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
