package com.app.dc.service.simulation.strategy.range;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stable-box mean reversion. The current bar may trigger a trade but never participates
 * in calculating the reference box.
 */
@Service("binanceRange")
public class BinanceRangeBacktestStrategy implements BinanceBacktestStrategy {
    public static final int STOP_COOLDOWN_BARS = 6;
    public static final String COOLDOWN_REJECTION = "BINANCE_RANGE_STOP_COOLDOWN";

    private final Map<String, Integer> cooldownUntilIndex =
            new ConcurrentHashMap<String, Integer>();
    private final Map<String, Map<String, Integer>> rejectStats =
            new ConcurrentHashMap<String, Map<String, Integer>>();
    @Autowired(required = false)
    private SymbolStrategyProfileService strategyProfiles;
    @Autowired(required = false)
    private BinanceRangeStateMachine stateMachine;

    @Override
    public String getName() {
        return "binanceRange";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series == null ? -1 : series.getEndIndex();
        Integer cooldown = cooldownUntilIndex.get(symbol);
        if (cooldown != null && end <= cooldown) {
            reject(symbol, COOLDOWN_REJECTION);
            return signal;
        }

        BinanceRangeStateSnapshot setup;
        if (stateMachine != null) {
            setup = stateMachine.evaluate(symbol, text, series);
        } else {
            double minimumTriggerRangeAtr = strategyProfiles == null ? 0.0
                    : strategyProfiles.minimumTriggerRangeAtr(
                            symbol, text, getName());
            double minimumBuyRecoveryBodyAtr = strategyProfiles == null ? 0.0
                    : strategyProfiles.minimumBuyRecoveryBodyAtr(
                            symbol, text, getName());
            double minimumBuyCloseLocation = strategyProfiles == null ? .65
                    : strategyProfiles.minimumBuyCloseLocation(
                            symbol, text, getName());
            BinanceRangeSetupAnalyzer.Snapshot legacy =
                    BinanceRangeSetupAnalyzer.analyze(series,
                            minimumTriggerRangeAtr,
                            minimumBuyRecoveryBodyAtr,
                            minimumBuyCloseLocation);
            setup = new BinanceRangeStateSnapshot("LEGACY", legacy.side,
                    legacy.readiness, legacy.reason, legacy.high, legacy.low,
                    legacy.stopPrice, legacy.takePrice, legacy.rewardRisk,
                    legacy.triggerRangeAtr, legacy.bodyAtr,
                    legacy.closeLocation, Double.NaN,
                    legacy.stable ? .5 : 0);
        }
        if (!setup.actionable()) {
            reject(symbol, setup.reason);
            return signal;
        }
        if ("BUY".equals(setup.side)) signal.side = Side.BUY;
        else if ("SELL".equals(setup.side)) signal.side = Side.SELL;
        else return signal;
        signal.stopPrice = BinanceStrategyMath.scale(setup.stopPrice);
        signal.takerPrice = BinanceStrategyMath.scale(setup.takePrice);
        if (stateMachine != null)
            stateMachine.consume(symbol, text, series.getEndIndex());
        return signal;
    }

    @Override
    public void onTradeClosed(String symbol, int exitBarIndex, TradeRecord trade) {
        if (trade != null && trade.exitReason != null && trade.exitReason.startsWith("stop")) {
            cooldownUntilIndex.put(symbol, exitBarIndex + STOP_COOLDOWN_BARS);
        }
    }

    @Override
    public void resetSession(String symbol) {
        cooldownUntilIndex.remove(symbol);
        rejectStats.remove(symbol);
        if (stateMachine != null) stateMachine.reset(symbol);
    }

    @Override
    public void resetRejectStats(String symbol) {
        rejectStats.remove(symbol);
    }

    @Override
    public Map<String, Integer> snapshotRejectStats(String symbol) {
        Map<String, Integer> values = rejectStats.get(symbol);
        return values == null ? new LinkedHashMap<String, Integer>()
                : new LinkedHashMap<String, Integer>(values);
    }

    private void reject(String symbol, String reason) {
        rejectStats.computeIfAbsent(symbol, key -> new ConcurrentHashMap<String, Integer>())
                .merge(reason, 1, Integer::sum);
    }
}
