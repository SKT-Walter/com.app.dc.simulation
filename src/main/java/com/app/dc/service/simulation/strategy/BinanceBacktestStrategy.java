package com.app.dc.service.simulation.strategy;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import org.ta4j.core.BarSeries;

import java.util.Collections;
import java.util.Map;

public interface BinanceBacktestStrategy {

    String getName();

    Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc);

    default void resetRejectStats(String symbol) {
    }

    default void resetRuntime(String symbol) {
        resetRejectStats(symbol);
    }

    /** Clears all state that must not leak from one backtest session into another. */
    default void resetSession(String symbol) {
        resetRuntime(symbol);
    }

    /** Receives completed trades so a strategy can update deterministic lifecycle state. */
    default void onTradeClosed(String symbol, int exitBarIndex, TradeRecord trade) {
    }

    default Map<String, Integer> snapshotRejectStats(String symbol) {
        return Collections.emptyMap();
    }
}
