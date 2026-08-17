package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.trend.bear.BearTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.EthStructuralBearTrendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** BTC-owned bearish strategy entry; it does not inherit an ETH strategy class. */
@Service("btcStructuralBearTrend")
public class BtcStructuralBearTrendBacktestStrategy implements BinanceBacktestStrategy {
    @Autowired private EthStructuralBearTrendService lifecycle;
    public String getName(){return "btcStructuralBearTrend";}
    public Signal evaluate(String symbol,String text,BarSeries series,TTbookOhlc current){
        Signal signal=BinanceStrategyMath.createBaseSignal(symbol,text,current);
        BearTrendSnapshot snapshot=lifecycle.current(symbol,text);
        if(snapshot.actionable&&snapshot.barIndex==series.getEndIndex()&&Double.isFinite(snapshot.stopPrice)){
            signal.side=Side.SELL;signal.stopPrice=BinanceStrategyMath.scale(snapshot.stopPrice);
            signal.takerPrice=null;signal.algoName="BTC_STRUCTURAL_BEAR";
            signal.remark="NO_FIXED_TAKE_PROFIT|"+snapshot.triggerType;
        }
        return signal;
    }
    public void resetSession(String symbol){lifecycle.reset(symbol);}
    public void onTradeClosed(String symbol,int exit,TradeRecord trade){lifecycle.tradeClosed(symbol,exit,trade);}
    public void onTradeOpened(String symbol,String timeframe,int entry){lifecycle.consume(symbol,timeframe,entry);}
}
