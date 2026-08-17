package com.app.dc.service.simulation.strategy.btc.trend;
import com.app.dc.service.simulation.strategy.trend.TrendRestartBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("trendRestartBTC") public class TrendRestartBTC extends TrendRestartBacktestStrategy {
    @Override public String getName(){return "trendRestartBTC";}
}
