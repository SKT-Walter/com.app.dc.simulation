package com.app.dc.service.simulation.strategy.btc.trend;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.BtcStructuralBullTrendBacktestStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** BTC 4H direction + 1H pullback lifecycle + 15m recovery trigger. */
@Service("btcStructuralBullTrendBTC")
public class BtcStructuralBullTrendBTC implements BinanceBacktestStrategy {
    @Autowired private BtcStructuralBullTrendBacktestStrategy delegate;
    public String getName(){return "btcStructuralBullTrendBTC";}
    public Signal evaluate(String symbol,String text,BarSeries series,TTbookOhlc current){return delegate.evaluate(symbol,text,series,current);}
    public void resetSession(String symbol){delegate.resetSession(symbol);}
    public void resetRuntime(String symbol){delegate.resetRuntime(symbol);}
    public void resetRejectStats(String symbol){delegate.resetRejectStats(symbol);}
    public java.util.Map<String,Integer> snapshotRejectStats(String symbol){return delegate.snapshotRejectStats(symbol);}
    public void onTradeClosed(String symbol,int exitBarIndex,TradeRecord trade){delegate.onTradeClosed(symbol,exitBarIndex,trade);}
    public void onTradeOpened(String symbol,String timeframe,int entryBarIndex){delegate.onTradeOpened(symbol,timeframe,entryBarIndex);}
}
