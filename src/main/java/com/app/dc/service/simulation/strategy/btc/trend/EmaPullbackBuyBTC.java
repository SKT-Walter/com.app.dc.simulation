package com.app.dc.service.simulation.strategy.btc.trend;
import com.app.dc.service.simulation.strategy.trend.EmaPullbackBuyBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("emaPullbackBuyBTC") public class EmaPullbackBuyBTC extends EmaPullbackBuyBacktestStrategy {
    @Override public String getName(){return "emaPullbackBuyBTC";}
}
