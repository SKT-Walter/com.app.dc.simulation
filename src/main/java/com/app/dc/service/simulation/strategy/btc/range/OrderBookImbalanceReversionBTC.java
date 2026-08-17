package com.app.dc.service.simulation.strategy.btc.range;
import com.app.dc.service.simulation.strategy.range.OrderBookImbalanceReversionBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("orderBookImbalanceReversionBTC") public class OrderBookImbalanceReversionBTC extends OrderBookImbalanceReversionBacktestStrategy {
    @Override public String getName(){return "orderBookImbalanceReversionBTC";}
}
