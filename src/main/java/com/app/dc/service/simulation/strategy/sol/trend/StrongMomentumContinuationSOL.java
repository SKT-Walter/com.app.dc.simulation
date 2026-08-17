package com.app.dc.service.simulation.strategy.sol.trend;

import com.app.dc.service.simulation.strategy.channel.BinanceChannelBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.*;
import org.springframework.stereotype.Service;

/** SOL owned implementation entry point; customize here without changing the other symbol pool. */
@Service("strongMomentumContinuationSOL")
public class StrongMomentumContinuationSOL extends StrongMomentumContinuationBacktestStrategy {
    @Override public String getName() { return "strongMomentumContinuationSOL"; }
}
