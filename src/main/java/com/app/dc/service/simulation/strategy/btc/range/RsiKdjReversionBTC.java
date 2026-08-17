package com.app.dc.service.simulation.strategy.btc.range;
import com.app.dc.service.simulation.strategy.range.RsiKdjReversionBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("rsiKdjReversionBTC") public class RsiKdjReversionBTC extends RsiKdjReversionBacktestStrategy {
    @Override public String getName(){return "rsiKdjReversionBTC";}
}
