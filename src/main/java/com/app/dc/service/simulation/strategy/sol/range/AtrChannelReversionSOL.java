package com.app.dc.service.simulation.strategy.sol.range;

import com.app.dc.service.simulation.strategy.range.*;
import org.springframework.stereotype.Service;

/** SOL owned implementation entry point; customize here without changing the other symbol pool. */
@Service("atrChannelReversionSOL")
public class AtrChannelReversionSOL extends AtrChannelReversionBacktestStrategy {
    @Override public String getName() { return "atrChannelReversionSOL"; }
}
