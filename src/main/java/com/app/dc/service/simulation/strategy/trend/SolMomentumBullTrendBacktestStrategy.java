package com.app.dc.service.simulation.strategy.trend;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.SolMomentumBullTrendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** SOL-only compression breakout continuation strategy. */
@Service("solMomentumBullTrend")
public class SolMomentumBullTrendBacktestStrategy extends AbstractTrendBacktestStrategy {
    @Autowired private SolMomentumBullTrendService lifecycle;
    public String getName(){return SolMomentumBullTrendService.STRATEGY;}
    public Signal evaluate(String symbol,String text,BarSeries series,TTbookOhlc current){
        Signal signal=baseSignal(symbol,text,current);
        BullTrendSnapshot s=lifecycle.current(symbol,text);
        if(s.actionable&&s.barIndex==series.getEndIndex()&&Double.isFinite(s.stopPrice)){
            signal.side=Side.BUY;signal.stopPrice=BinanceStrategyMath.scale(s.stopPrice);
            signal.takerPrice=null;signal.algoName="SOL_MOMENTUM_BULL";
            signal.remark="NO_FIXED_TAKE_PROFIT|"+s.triggerType;
        }
        return signal;
    }
    public void resetSession(String symbol){lifecycle.reset(symbol);}
    public void onTradeClosed(String symbol,int exitBarIndex,TradeRecord trade){lifecycle.tradeClosed(symbol,exitBarIndex,trade);}
    public void onTradeOpened(String symbol,String timeframe,int entryBarIndex){lifecycle.consume(symbol,timeframe,entryBarIndex);}
}
