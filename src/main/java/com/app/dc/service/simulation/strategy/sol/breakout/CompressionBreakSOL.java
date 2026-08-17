package com.app.dc.service.simulation.strategy.sol.breakout;

import com.app.dc.service.simulation.strategy.range.CompressionBreakBacktestStrategy;
import org.springframework.stereotype.Service;

/** SOL owned implementation entry point; customize here without changing the other symbol pool. */
@Service("compressionBreakSOL")
public class CompressionBreakSOL extends CompressionBreakBacktestStrategy {
    @Override public String getName() { return "compressionBreakSOL"; }
}
