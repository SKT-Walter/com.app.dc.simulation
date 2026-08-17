package com.app.dc.service.simulation.strategy.trend;

import org.springframework.stereotype.Service;

/** SOL-only top-distribution and A-wave short lifecycle. */
@Service("solStructuralBearTrend")
public class SolStructuralBearTrendBacktestStrategy extends EthStructuralBearTrendBacktestStrategy {
    @Override public String getName(){return "solStructuralBearTrend";}
}
