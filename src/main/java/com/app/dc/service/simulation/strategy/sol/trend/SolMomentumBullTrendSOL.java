package com.app.dc.service.simulation.strategy.sol.trend;

import com.app.dc.service.simulation.strategy.channel.BinanceChannelBacktestStrategy;
import com.app.dc.service.simulation.strategy.trend.*;
import org.springframework.stereotype.Service;

/** SOL owned implementation entry point; customize here without changing the other symbol pool. */
@Service("solMomentumBullTrendSOL")
public class SolMomentumBullTrendSOL extends SolMomentumBullTrendBacktestStrategy {
    @Override public String getName() { return "solMomentumBullTrendSOL"; }
}
