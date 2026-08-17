package com.app.dc.service.simulation.strategy.btc.breakout;

import com.app.dc.service.simulation.strategy.range.CompressionBreakBacktestStrategy;
import org.springframework.stereotype.Service;

/** BTC-owned implementation entry point. */
@Service("compressionBreakBTC")
public class CompressionBreakBTC extends CompressionBreakBacktestStrategy {
    @Override public String getName() { return "compressionBreakBTC"; }
}
