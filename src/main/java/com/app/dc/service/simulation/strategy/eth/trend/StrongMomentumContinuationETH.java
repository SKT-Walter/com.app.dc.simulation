package com.app.dc.service.simulation.strategy.eth.trend;

import com.app.dc.service.simulation.strategy.channel.BinanceChannelBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.*;
import org.springframework.stereotype.Service;

/** ETH owned implementation entry point; customize here without changing the other symbol pool. */
@Service("strongMomentumContinuationETH")
public class StrongMomentumContinuationETH extends StrongMomentumContinuationBacktestStrategy {
    @Override public String getName() { return "strongMomentumContinuationETH"; }
}
