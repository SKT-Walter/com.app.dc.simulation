package com.app.dc.service.simulation.strategy.eth.range;

import com.app.dc.service.simulation.strategy.range.*;
import org.springframework.stereotype.Service;

/** ETH owned implementation entry point; customize here without changing the other symbol pool. */
@Service("failedBreakReversalETH")
public class FailedBreakReversalETH extends FailedBreakReversalBacktestStrategy {
    @Override public String getName() { return "failedBreakReversalETH"; }
}
