package com.app.dc.service.simulation;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
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
        BinanceBacktestStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("unsupported strategyName: " + strategyName);
        }
        return strategy.evaluate(symbol, text, replaySeries, currentOhlc);
    }
}
