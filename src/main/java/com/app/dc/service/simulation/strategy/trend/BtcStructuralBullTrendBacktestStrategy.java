package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.trend.bull.BtcStructuralBullTrendService;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** BTC-owned implementation; deliberately independent from the ETH strategy. */
@Service("btcStructuralBullTrend")
public class BtcStructuralBullTrendBacktestStrategy implements BinanceBacktestStrategy {
    @Autowired private BtcStructuralBullTrendService lifecycle;
    public String getName(){return BtcStructuralBullTrendService.STRATEGY;}
    public Signal evaluate(String symbol,String text,BarSeries series,TTbookOhlc current){
        Signal signal=BinanceStrategyMath.createBaseSignal(symbol,text,current);
        BullTrendSnapshot snapshot=lifecycle.current(symbol,text);
        if(snapshot.actionable&&snapshot.barIndex==series.getEndIndex()&&Double.isFinite(snapshot.stopPrice)){
            signal.side=Side.BUY;signal.stopPrice=BinanceStrategyMath.scale(snapshot.stopPrice);
            signal.takerPrice=null;signal.algoName="BTC_STRUCTURAL_BULL";
            signal.remark="NO_FIXED_TAKE_PROFIT|"+snapshot.triggerType;
        }
        return signal;
    }
    public void resetSession(String symbol){lifecycle.reset(symbol);}
    public void onTradeClosed(String symbol,int exitBarIndex,TradeRecord trade){lifecycle.tradeClosed(symbol,exitBarIndex,trade);}
    public void onTradeOpened(String symbol,String timeframe,int entryBarIndex){lifecycle.consume(symbol,timeframe,entryBarIndex);}
}
