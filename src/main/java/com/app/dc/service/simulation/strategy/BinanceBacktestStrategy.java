package com.app.dc.service.simulation.strategy;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.ta4j.core.BarSeries;

import java.util.Collections;
import java.util.Map;

public interface BinanceBacktestStrategy {

    String getName();

    Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc);

    default void resetRejectStats(String symbol) {
    }

    default Map<String, Integer> snapshotRejectStats(String symbol) {
        return Collections.emptyMap();
    }
}
