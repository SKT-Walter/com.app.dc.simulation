package com.app.dc.service.simulation.strategy.eth.breakout;

import com.app.dc.service.simulation.strategy.range.CompressionBreakBacktestStrategy;
import org.springframework.stereotype.Service;

/** ETH owned implementation entry point; customize here without changing the other symbol pool. */
@Service("compressionBreakETH")
public class CompressionBreakETH extends CompressionBreakBacktestStrategy {
    @Override public String getName() { return "compressionBreakETH"; }
}
