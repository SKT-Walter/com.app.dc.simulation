package com.app.dc.service.simulation.strategy;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import org.ta4j.core.BarSeries;

import java.util.Collections;
import java.util.Map;

public interface BinanceBacktestStrategy {

    String getName();

    Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc);

    /**
     * Manages a position opened by this strategy after the current bar has closed.
     * Any returned stop applies from the next bar, avoiding same-bar look-ahead.
     */
    default PositionManagementResult managePosition(String symbol, String text, BarSeries series,
                                                    TTbookOhlc currentOhlc, Position position) {
        return PositionManagementResult.hold();
    }

    /** Whether a missing strategy take price should use the backtest-wide fallback take profit. */
    default boolean useFallbackTakeProfit() {
        return true;
    }

    default void resetRejectStats(String symbol) {
    }

    default void resetRuntime(String symbol) {
        resetRejectStats(symbol);
    }

    /** Clears all state that must not leak from one backtest session into another. */
    default void resetSession(String symbol) {
        resetRuntime(symbol);
    }

    /** Receives completed trades so stateful strategies can apply deterministic cooldowns. */
    default void onTradeClosed(String symbol, int exitBarIndex, TradeRecord trade) {
    }

    default Map<String, Integer> snapshotRejectStats(String symbol) {
        return Collections.emptyMap();
    }
}
