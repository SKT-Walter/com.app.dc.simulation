package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.PositionManagementResult;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("binanceTrend")
public class BinanceTrendBacktestStrategy implements BinanceBacktestStrategy {

    private static final int FAST = 9;
    private static final int MID = 21;
    private static final int SLOW = 55;
    private static final double TREND_PULLBACK_PCT = 0.006;
    private static final double STOP_LOSS_PCT = 0.05;
    private static final int ATR_PERIOD = 14;

    @Value("${backtest.strategy.binance-trend.profit-protection-pct:0.07}")
    private double profitProtectionPct = 0.07;

    @Value("${backtest.strategy.binance-trend.atr-trailing-multiplier:4.0}")
    private double atrTrailingMultiplier = 4.0;

    @Override
    public String getName() {
        return "binanceTrend";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        int endIndex = series.getEndIndex();
        if (endIndex < SLOW + 3) {
            return signal;
        }

        double close = BinanceStrategyMath.close(series, endIndex);
        double closePrev = BinanceStrategyMath.close(series, endIndex - 1);
        double maFast = BinanceStrategyMath.sma(series, endIndex, FAST);
        double maMid = BinanceStrategyMath.sma(series, endIndex, MID);
        double maSlow = BinanceStrategyMath.sma(series, endIndex, SLOW);

        double pullbackPct = maFast == 0 ? 0.0 : Math.abs((close - maFast) / maFast);
        boolean nearFastMa = pullbackPct <= TREND_PULLBACK_PCT;
        boolean upTrend = maFast > maMid && maMid > maSlow && close > closePrev;
        boolean downTrend = maFast < maMid && maMid < maSlow && close < closePrev;

        if (upTrend && nearFastMa) {
            signal.side = Side.BUY;
            signal.stopPrice = BinanceStrategyMath.scale(close * (1.0 - STOP_LOSS_PCT));
        } else if (downTrend && nearFastMa) {
            signal.side = Side.SELL;
            signal.stopPrice = BinanceStrategyMath.scale(close * (1.0 + STOP_LOSS_PCT));
        }
        return signal;
    }

    @Override
    public boolean useFallbackTakeProfit() {
        return false;
    }

    @Override
    public PositionManagementResult managePosition(String symbol, String text, BarSeries series,
                                                   TTbookOhlc currentOhlc, Position position) {
        if (series == null || position == null || position.entryIndex < 0
                || series.getEndIndex() <= position.entryIndex) {
            return PositionManagementResult.hold();
        }
        int endIndex = series.getEndIndex();
        double close = BinanceStrategyMath.close(series, endIndex);

        int holdBars = endIndex - position.entryIndex + 1;
        double atr = BinanceStrategyMath.atr(series, endIndex, ATR_PERIOD);
        if (!Double.isFinite(atr) || atr <= 0.0) {
            return PositionManagementResult.hold();
        }
        if (position.side == Side.BUY) {
            double highest = BinanceStrategyMath.highestHigh(series, endIndex, holdBars);
            if (highest < position.entryPrice * (1.0 + profitProtectionPct)) {
                return PositionManagementResult.hold();
            }
            double trailingStop = Math.max(position.entryPrice, highest - atr * atrTrailingMultiplier);
            if (trailingStop >= close) {
                return PositionManagementResult.exit("take_trailing_stop");
            }
            return PositionManagementResult.tightenStop(trailingStop);
        }
        if (position.side == Side.SELL) {
            double lowest = BinanceStrategyMath.lowestLow(series, endIndex, holdBars);
            if (lowest > position.entryPrice * (1.0 - profitProtectionPct)) {
                return PositionManagementResult.hold();
            }
            double trailingStop = Math.min(position.entryPrice, lowest + atr * atrTrailingMultiplier);
            if (trailingStop <= close) {
                return PositionManagementResult.exit("take_trailing_stop");
            }
            return PositionManagementResult.tightenStop(trailingStop);
        }
        return PositionManagementResult.hold();
    }
}
