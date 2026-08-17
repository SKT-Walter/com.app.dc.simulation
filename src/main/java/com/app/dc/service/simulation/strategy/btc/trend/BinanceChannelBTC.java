package com.app.dc.service.simulation.strategy.btc.trend;
import com.app.dc.service.simulation.strategy.channel.BinanceChannelBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("binanceChannelBTC") public class BinanceChannelBTC extends BinanceChannelBacktestStrategy {
    @Override public String getName(){return "binanceChannelBTC";}
}
