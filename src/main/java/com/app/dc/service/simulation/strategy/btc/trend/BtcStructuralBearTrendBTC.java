package com.app.dc.service.simulation.strategy.btc.trend;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.BtcStructuralBearTrendBacktestStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** BTC-only top/A-wave and bearish continuation execution. */
@Service("btcStructuralBearTrendBTC")
public class BtcStructuralBearTrendBTC implements BinanceBacktestStrategy {
    @Autowired private BtcStructuralBearTrendBacktestStrategy delegate;
    public String getName(){return "btcStructuralBearTrendBTC";}
    public Signal evaluate(String symbol,String text,BarSeries series,TTbookOhlc current){return delegate.evaluate(symbol,text,series,current);}
    public void resetSession(String symbol){delegate.resetSession(symbol);}
    public void onTradeClosed(String symbol,int exit,TradeRecord trade){delegate.onTradeClosed(symbol,exit,trade);}
    public void onTradeOpened(String symbol,String timeframe,int entry){delegate.onTradeOpened(symbol,timeframe,entry);}
}
