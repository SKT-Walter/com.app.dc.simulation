package com.app.dc.service.simulation.strategy.btc.range;
import com.app.dc.service.simulation.strategy.range.BinanceRangeBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("binanceRangeBTC") public class BinanceRangeBTC extends BinanceRangeBacktestStrategy {
    @Override public String getName(){return "binanceRangeBTC";}
}
