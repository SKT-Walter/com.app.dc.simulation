package com.app.dc.service.simulation.strategy.eth.trend;

import com.app.dc.service.simulation.strategy.channel.BinanceChannelBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.*;
import org.springframework.stereotype.Service;

/** ETH owned implementation entry point; customize here without changing the other symbol pool. */
@Service("trendRestartETH")
public class TrendRestartETH extends TrendRestartBacktestStrategy {
    @Override public String getName() { return "trendRestartETH"; }
}
