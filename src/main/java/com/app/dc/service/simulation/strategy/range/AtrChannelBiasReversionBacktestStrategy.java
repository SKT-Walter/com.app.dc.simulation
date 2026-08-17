package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ATR 通道偏置回归回测策略。
 *
 * 策略逻辑：
 * 1. 用中轴斜率识别轻微偏多或偏空。
 * 2. 价格回踩到动态 ATR 通道边缘时，记录待确认方向。
 * 3. 后续 1 到 3 根 K 线重新转强或转弱后，顺着偏置方向开仓。
 */
@Service("atrChannelBiasReversion")
public class AtrChannelBiasReversionBacktestStrategy implements BinanceBacktestStrategy {

    private final Map<String, Map<String, Integer>> rejectStats = new ConcurrentHashMap<>();
    @Autowired private AtrChannelBiasSetupService setupService;

    @Override
    public String getName() {
        return "atrChannelBiasReversion";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = com.app.dc.service.simulation.strategy.BinanceStrategyMath
                .createBaseSignal(symbol, text, currentOhlc);
        AtrChannelBiasSetupSnapshot setup = setupService.update(symbol, text, series);
        if (!setup.triggered()) {
            recordReject(symbol, setup.reason);
            return signal;
        }
        signal.side = setup.side;
        signal.stopPrice = setup.stopPrice;
        signal.takerPrice = setup.takePrice;
        return signal;
    }

    @Override
    public void resetRejectStats(String symbol) {
        rejectStats.remove(symbol);
    }

    @Override
    public void resetRuntime(String symbol) {
        rejectStats.remove(symbol);
    }

    @Override
    public void resetSession(String symbol) {
        rejectStats.remove(symbol);
        setupService.reset(symbol);
    }

    @Override
    public Map<String, Integer> snapshotRejectStats(String symbol) {
        Map<String, Integer> stats = rejectStats.get(symbol);
        return stats == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stats);
    }

    private void recordReject(String symbol, String reason) {
        rejectStats.computeIfAbsent(symbol, key -> new ConcurrentHashMap<>())
                .merge(reason, 1, Integer::sum);
    }

}
