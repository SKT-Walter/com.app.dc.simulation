package com.app.dc.service.simulation.strategy.btc.range;
import com.app.dc.service.simulation.strategy.range.ImpulseReclaimBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("impulseReclaimBTC") public class ImpulseReclaimBTC extends ImpulseReclaimBacktestStrategy {
    @Override public String getName(){return "impulseReclaimBTC";}
}
