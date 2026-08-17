package com.app.dc.service.simulation.strategy.btc.range;
import com.app.dc.service.simulation.strategy.range.FailedBreakReversalBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("failedBreakReversalBTC") public class FailedBreakReversalBTC extends FailedBreakReversalBacktestStrategy {
    @Override public String getName(){return "failedBreakReversalBTC";}
}
