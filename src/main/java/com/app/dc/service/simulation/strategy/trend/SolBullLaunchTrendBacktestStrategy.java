package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.SolBullLaunchTrendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

@Service("solBullLaunchTrend")
public class SolBullLaunchTrendBacktestStrategy extends AbstractTrendBacktestStrategy {
    @Autowired private SolBullLaunchTrendService lifecycle;
    public String getName(){return SolBullLaunchTrendService.STRATEGY;}
    public Signal evaluate(String symbol,String text,BarSeries series,TTbookOhlc current){Signal signal=baseSignal(symbol,text,current);BullTrendSnapshot s=lifecycle.current(symbol,text);if(s.actionable&&s.barIndex==series.getEndIndex()&&Double.isFinite(s.stopPrice)){signal.side=Side.BUY;signal.stopPrice=BinanceStrategyMath.scale(s.stopPrice);signal.takerPrice=null;signal.algoName="BTCUSDT".equalsIgnoreCase(symbol)?"BTC_BULL_LAUNCH":"SOL_BULL_LAUNCH";signal.remark="NO_FIXED_TAKE_PROFIT|"+s.triggerType;}return signal;}
    public void resetSession(String symbol){lifecycle.reset(symbol);}public void onTradeClosed(String symbol,int exitBarIndex,TradeRecord trade){lifecycle.tradeClosed(symbol,exitBarIndex,trade);}public void onTradeOpened(String symbol,String timeframe,int entryBarIndex){lifecycle.consume(symbol,timeframe,entryBarIndex);}
}
