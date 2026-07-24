package com.app.dc.service.simulation;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.PositionManagementResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BacktestStrategyService {

    private final Map<String, BinanceBacktestStrategy> strategyMap = new ConcurrentHashMap<>();

    @Autowired
    public BacktestStrategyService(List<BinanceBacktestStrategy> strategies) {
        for (BinanceBacktestStrategy strategy : strategies) {
            strategyMap.put(strategy.getName(), strategy);
        }
    }

    public Signal evaluateSignal(String strategyName, String symbol, String text, BarSeries replaySeries,
                                 TTbookOhlc currentOhlc) {
        BinanceBacktestStrategy strategy = getStrategy(strategyName);
        return strategy.evaluate(symbol, text, replaySeries, currentOhlc);
    }

    public BinanceBacktestStrategy getStrategy(String strategyName) {
        BinanceBacktestStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("unsupported strategyName: " + strategyName);
        }
        return strategy;
    }

    public PositionManagementResult managePosition(String strategyName, String symbol, String text,
                                                   BarSeries series, TTbookOhlc currentOhlc,
                                                   Position position) {
        return getStrategy(strategyName).managePosition(symbol, text, series, currentOhlc, position);
    }

    public boolean useFallbackTakeProfit(String strategyName) {
        return getStrategy(strategyName).useFallbackTakeProfit();
    }

    public void resetAll(String symbol) {
        for (BinanceBacktestStrategy strategy : strategyMap.values()) strategy.resetSession(symbol);
    }

    public void resetRuntime(String strategyName, String symbol) {
        getStrategy(strategyName).resetRuntime(symbol);
    }

    public void onTradeClosed(String strategyName, String symbol, int exitBarIndex, TradeRecord trade) {
        if (strategyName == null || strategyName.trim().isEmpty() || trade == null) return;
        getStrategy(strategyName).onTradeClosed(symbol, exitBarIndex, trade);
    }
}
