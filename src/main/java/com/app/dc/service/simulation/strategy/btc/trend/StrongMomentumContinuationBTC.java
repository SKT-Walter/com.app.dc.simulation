package com.app.dc.service.simulation.strategy.btc.trend;
import com.app.dc.service.simulation.strategy.trend.StrongMomentumContinuationBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("strongMomentumContinuationBTC") public class StrongMomentumContinuationBTC extends StrongMomentumContinuationBacktestStrategy {
    @Override public String getName(){return "strongMomentumContinuationBTC";}
}
