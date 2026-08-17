package com.app.dc.service.simulation.strategy.btc.trend;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.BtcBullLaunchTrendBacktestStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** BTC-only early bull launch execution; lifecycle is independent from mature continuation. */
@Service("btcBullLaunchTrendBTC")
public class BtcBullLaunchTrendBTC implements BinanceBacktestStrategy {
    @Autowired private BtcBullLaunchTrendBacktestStrategy delegate;
    public String getName(){return "btcBullLaunchTrendBTC";}
    public Signal evaluate(String symbol,String text,BarSeries series,TTbookOhlc current){return delegate.evaluate(symbol,text,series,current);}
    public void resetSession(String symbol){delegate.resetSession(symbol);}
    public void onTradeClosed(String symbol,int exit,TradeRecord trade){delegate.onTradeClosed(symbol,exit,trade);}
    public void onTradeOpened(String symbol,String timeframe,int entry){delegate.onTradeOpened(symbol,timeframe,entry);}
}
