package com.app.dc.service.simulation.strategy.range;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service("vwapReversion")
public class VwapReversionBacktestStrategy implements BinanceBacktestStrategy {

    private static final int PERIOD = 20;
    private static final double DEV_THRESHOLD = 0.0038;
    private static final double MIN_REWARD_RISK = 1.25;
    private static final int STOP_COOLDOWN_BARS = 4;
    private static final String COOLDOWN_REJECTION = "VWAP_STOP_COOLDOWN";

    private final Map<String, Integer> cooldownUntilIndex = new ConcurrentHashMap<String, Integer>();
    private final Map<String, Map<String, Integer>> rejectStats =
            new ConcurrentHashMap<String, Map<String, Integer>>();

    @Override
    public String getName() {
        return "vwapReversion";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int end = series.getEndIndex();
        if (end < PERIOD + 2) {
            return signal;
        }
        Integer cooldownUntil = cooldownUntilIndex.get(symbol);
        if (cooldownUntil != null && end <= cooldownUntil) {
            incrementReject(symbol, COOLDOWN_REJECTION);
            return signal;
        }

        double close = BinanceStrategyMath.close(series, end);
        double previousClose = BinanceStrategyMath.close(series, end - 1);
        double open = series.getBar(end).getOpenPrice().doubleValue();
        double low = series.getBar(end).getLowPrice().doubleValue();
        double high = series.getBar(end).getHighPrice().doubleValue();
        double previousLow = series.getBar(end - 1).getLowPrice().doubleValue();
        double previousHigh = series.getBar(end - 1).getHighPrice().doubleValue();
        double vwap = rollingVwap(series, end, PERIOD);
        double previousVwap = rollingVwap(series, end - 1, PERIOD);
        if (vwap <= 0 || previousVwap <= 0) {
            return signal;
        }
        double dev = (close - vwap) / vwap;
        double previousDev = (previousClose - previousVwap) / previousVwap;
        double buffer = Math.max(close * 0.0025, BinanceStrategyMath.atr(series, end, 14) * 0.4);
        double reward = Math.abs(vwap - close);
        if (buffer <= 0 || reward / buffer < MIN_REWARD_RISK) {
            return signal;
        }

        boolean bullishRecovery = dev <= -DEV_THRESHOLD
                && dev > previousDev
                && close > previousClose
                && close > open
                && low >= previousLow;
        boolean bearishRecovery = dev >= DEV_THRESHOLD
                && dev < previousDev
                && close < previousClose
                && close < open
                && high <= previousHigh;

        if (bullishRecovery) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(Math.max(0.0, close - buffer));
            signal.takerPrice = BinanceStrategyMath.scale(vwap);
        } else if (bearishRecovery) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(close + buffer);
            signal.takerPrice = BinanceStrategyMath.scale(vwap);
        }
        return signal;
    }

    private double rollingVwap(BarSeries s, int end, int period) {
        int start = Math.max(0, end - period + 1);
        double pv = 0.0;
        double vol = 0.0;
        for (int i = start; i <= end; i++) {
            double h = s.getBar(i).getHighPrice().doubleValue();
            double l = s.getBar(i).getLowPrice().doubleValue();
            double c = s.getBar(i).getClosePrice().doubleValue();
            double tp = (h + l + c) / 3.0;
            double v = s.getBar(i).getVolume().doubleValue();
            pv += tp * v;
            vol += v;
        }
        return vol == 0 ? 0.0 : pv / vol;
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
    }

    @Override
    public void resetRuntime(String symbol) {
        // Routing activation must not erase a cooldown created by the previous stop.
    }

    @Override
    public void resetRejectStats(String symbol) {
        rejectStats.remove(symbol);
    }

    @Override
    public Map<String, Integer> snapshotRejectStats(String symbol) {
        Map<String, Integer> stats = rejectStats.get(symbol);
        return stats == null ? new LinkedHashMap<String, Integer>() : new LinkedHashMap<String, Integer>(stats);
    }

    private void incrementReject(String symbol, String reason) {
        Map<String, Integer> stats = rejectStats.get(symbol);
        if (stats == null) {
            stats = new ConcurrentHashMap<String, Integer>();
            rejectStats.put(symbol, stats);
        }
        Integer current = stats.get(reason);
        stats.put(reason, current == null ? 1 : current + 1);
    }
}
