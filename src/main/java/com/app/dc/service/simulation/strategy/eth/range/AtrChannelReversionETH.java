package com.app.dc.service.simulation.strategy.eth.range;

import com.app.dc.service.simulation.strategy.range.*;
import org.springframework.stereotype.Service;

/** ETH owned implementation entry point; customize here without changing the other symbol pool. */
@Service("atrChannelReversionETH")
public class AtrChannelReversionETH extends AtrChannelReversionBacktestStrategy {
    @Override public String getName() { return "atrChannelReversionETH"; }
}
