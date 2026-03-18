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

/**
 * 回测策略信号评估入口（仅做策略分发）。
 */
@Service
public class BinanceBacktestStrategyService {

    private final Map<String, BinanceBacktestStrategy> strategyMap = new ConcurrentHashMap<>();

    @Autowired
    public BinanceBacktestStrategyService(List<BinanceBacktestStrategy> strategies) {
        for (BinanceBacktestStrategy strategy : strategies) {
            strategyMap.put(strategy.getName(), strategy);
        }
    }

    /**
     * 评估某根 K 线结束后的策略信号。
     */
    public Signal evaluateSignal(String strategyName, String symbol, String text, BarSeries replaySeries,
                                 TTbookOhlc currentOhlc) {
        BinanceBacktestStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("unsupported strategyName: " + strategyName);
        }
        return strategy.evaluate(symbol, text, replaySeries, currentOhlc);
    }
}
