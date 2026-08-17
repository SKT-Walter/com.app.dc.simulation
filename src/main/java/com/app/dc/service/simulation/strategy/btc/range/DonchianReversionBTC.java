package com.app.dc.service.simulation.strategy.btc.range;
import com.app.dc.service.simulation.strategy.range.DonchianReversionBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("donchianReversionBTC") public class DonchianReversionBTC extends DonchianReversionBacktestStrategy {
    @Override public String getName(){return "donchianReversionBTC";}
}
