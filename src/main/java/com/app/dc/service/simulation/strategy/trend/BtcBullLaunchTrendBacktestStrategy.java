package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.SolBullLaunchTrendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** BTC-owned launch entry; disabled by default and independent of SOL class inheritance. */
@Service("btcBullLaunchTrend")
public class BtcBullLaunchTrendBacktestStrategy implements BinanceBacktestStrategy {
    @Autowired private SolBullLaunchTrendService lifecycle;
    public String getName(){return "btcBullLaunchTrend";}
    public Signal evaluate(String symbol,String text,BarSeries series,TTbookOhlc current){
        Signal signal=BinanceStrategyMath.createBaseSignal(symbol,text,current);
        BullTrendSnapshot snapshot=lifecycle.current(symbol,text);
        if(snapshot.actionable&&snapshot.barIndex==series.getEndIndex()&&Double.isFinite(snapshot.stopPrice)){
            signal.side=Side.BUY;signal.stopPrice=BinanceStrategyMath.scale(snapshot.stopPrice);
            signal.takerPrice=null;signal.algoName="BTC_BULL_LAUNCH";
            signal.remark="NO_FIXED_TAKE_PROFIT|"+snapshot.triggerType;
        }
        return signal;
    }
    public void resetSession(String symbol){lifecycle.reset(symbol);}
    public void onTradeClosed(String symbol,int exit,TradeRecord trade){lifecycle.tradeClosed(symbol,exit,trade);}
    public void onTradeOpened(String symbol,String timeframe,int entry){lifecycle.consume(symbol,timeframe,entry);}
}
