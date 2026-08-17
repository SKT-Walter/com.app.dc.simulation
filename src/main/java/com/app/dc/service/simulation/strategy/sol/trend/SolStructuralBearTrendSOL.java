package com.app.dc.service.simulation.strategy.sol.trend;

import com.app.dc.service.simulation.strategy.trend.SolStructuralBearTrendBacktestStrategy;
import org.springframework.stereotype.Service;

/** Symbol-owned SOL top-distribution and A-wave strategy. */
@Service("solStructuralBearTrendSOL")
public class SolStructuralBearTrendSOL extends SolStructuralBearTrendBacktestStrategy {
    @Override public String getName(){return "solStructuralBearTrendSOL";}
}
