package com.app.dc.service.simulation.strategy.btc.range;
import com.app.dc.service.simulation.strategy.range.AtrChannelReversionBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("atrChannelReversionBTC") public class AtrChannelReversionBTC extends AtrChannelReversionBacktestStrategy {
    @Override public String getName(){return "atrChannelReversionBTC";}
}
