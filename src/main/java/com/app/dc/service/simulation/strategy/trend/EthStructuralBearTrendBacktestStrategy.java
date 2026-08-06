package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.*;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.trend.bear.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** ETH-only structural bearish continuation strategy. */
@Service("ethStructuralBearTrend")
public class EthStructuralBearTrendBacktestStrategy extends AbstractTrendBacktestStrategy {
    @Autowired private EthStructuralBearTrendService lifecycle;
    public String getName(){return EthStructuralBearTrendService.STRATEGY;}
    public Signal evaluate(String symbol,String text,BarSeries series,TTbookOhlc current){Signal signal=baseSignal(symbol,text,current);BearTrendSnapshot s=lifecycle.current(symbol,text);if(s.actionable&&s.barIndex==series.getEndIndex()&&Double.isFinite(s.stopPrice)){signal.side=Side.SELL;signal.stopPrice=BinanceStrategyMath.scale(s.stopPrice);signal.takerPrice=null;signal.algoName="ETH_STRUCTURAL_BEAR";signal.remark="NO_FIXED_TAKE_PROFIT|"+s.triggerType;}return signal;}
    public void resetSession(String symbol){lifecycle.reset(symbol);}public void onTradeClosed(String symbol,int exit,TradeRecord trade){lifecycle.tradeClosed(symbol,exit,trade);}public void onTradeOpened(String symbol,String timeframe,int entry){lifecycle.consume(symbol,timeframe,entry);}
}
