package com.app.dc.simulation;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.BacktestStrategyService;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class BacktestStrategyLifecycleTest {
    @Test
    public void lifecycleIsDispatchedOnlyToTheOpeningStrategy() {
        TrackingStrategy first = new TrackingStrategy("first");
        TrackingStrategy second = new TrackingStrategy("second");
        BacktestStrategyService service = new BacktestStrategyService(Arrays.<BinanceBacktestStrategy>asList(first, second));

        service.resetAll("ETHUSDT");
        Assert.assertEquals(1, first.sessionResets);
        Assert.assertEquals(1, second.sessionResets);

        TradeRecord trade = new TradeRecord();
        service.onTradeClosed("first", "ETHUSDT", 42, trade);
        Assert.assertEquals(1, first.closedTrades);
        Assert.assertEquals(0, second.closedTrades);

        service.onTradeClosed("unknownStrategy", "ETHUSDT", 43, trade);
        Assert.assertEquals(1, first.closedTrades);
        Assert.assertEquals(0, second.closedTrades);
    }

    private static final class TrackingStrategy implements BinanceBacktestStrategy {
        private final String name;
        int sessionResets;
        int closedTrades;

        TrackingStrategy(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
            return new Signal();
        }
        @Override public void resetSession(String symbol) { sessionResets++; }
        @Override public void onTradeClosed(String symbol, int exitBarIndex, TradeRecord trade) { closedTrades++; }
        @Override public Map<String, Integer> snapshotRejectStats(String symbol) {
            return Collections.singletonMap(name + "_REJECT", 1);
        }
    }
}
