package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("binanceTrend")
public class BinanceTrendBacktestStrategy implements BinanceBacktestStrategy {

    private static final int FAST = 9;
    private static final int MID = 21;
    private static final int SLOW = 55;
    private static final double TREND_PULLBACK_PCT = 0.006;
    @Autowired(required = false)
    private SymbolStrategyProfileService strategyProfiles;
    @Autowired(required = false)
    private TrendLifecycleService lifecycleService;

    @Override
    public String getName() {
        return "binanceTrend";
    }

    @Override
    public Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc) {
        Signal signal = BinanceStrategyMath.createBaseSignal(symbol, text, currentOhlc);
        BinanceTrendSettings settings = strategyProfiles == null
                ? BinanceTrendSettings.legacy()
                : strategyProfiles.binanceTrendSettings(symbol, text);
        if (settings.lifecycleEnabled && lifecycleService != null) {
            TrendLifecycleSnapshot lifecycle=lifecycleService.current(symbol,text);
            if(lifecycle.actionable) {
                signal.side="BUY".equals(lifecycle.signalSide())?Side.BUY:Side.SELL;
                signal.stopPrice=BinanceStrategyMath.scale(lifecycle.stopPrice);
                signal.takerPrice=null;
                signal.remark="CONTINUATION_BREAKOUT|"+lifecycle.phase;
                lifecycleService.consume(symbol,text,series.getEndIndex());
            }
            return signal;
        }
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
            if(strategyProfiles==null||strategyProfiles.binanceTrendBuyEnabled(symbol,text))
                signal.side = Side.BUY;
        } else if (downTrend && nearFastMa) {
            signal.side = Side.SELL;
        }
        return signal;
    }

    @Override
    public void resetSession(String symbol) {
        if(lifecycleService!=null)lifecycleService.reset(symbol);
    }

    @Override
    public void onTradeClosed(String symbol,int exitBarIndex,
                              com.app.dc.service.simulation.BacktestModels.TradeRecord trade){
        if(lifecycleService!=null&&trade!=null)
            lifecycleService.tradeClosed(symbol);
    }
}
