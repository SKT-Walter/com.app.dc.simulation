package com.app.dc.service.simulation.strategy.btc.trend;
import com.app.dc.service.simulation.strategy.trend.BinanceTrendBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("binanceTrendBTC") public class BinanceTrendBTC extends BinanceTrendBacktestStrategy {
    @Override public String getName(){return "binanceTrendBTC";}
}
