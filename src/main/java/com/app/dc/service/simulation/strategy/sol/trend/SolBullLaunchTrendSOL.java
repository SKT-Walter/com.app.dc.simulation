package com.app.dc.service.simulation.strategy.sol.trend;

import com.app.dc.service.simulation.strategy.trend.SolBullLaunchTrendBacktestStrategy;
import org.springframework.stereotype.Service;

/** SOL-owned early 4H turn-up entry point. */
@Service("solBullLaunchTrendSOL")
public class SolBullLaunchTrendSOL extends SolBullLaunchTrendBacktestStrategy {
    @Override public String getName(){return "solBullLaunchTrendSOL";}
}
