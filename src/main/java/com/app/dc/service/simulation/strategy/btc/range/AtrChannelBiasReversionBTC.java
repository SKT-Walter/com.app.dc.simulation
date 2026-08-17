package com.app.dc.service.simulation.strategy.btc.range;
import com.app.dc.service.simulation.strategy.range.AtrChannelBiasReversionBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("atrChannelBiasReversionBTC") public class AtrChannelBiasReversionBTC extends AtrChannelBiasReversionBacktestStrategy {
    @Override public String getName(){return "atrChannelBiasReversionBTC";}
}
