package com.app.dc.service.simulation.strategy.btc.range;
import com.app.dc.service.simulation.strategy.range.BollingerMeanReversionBacktestStrategy;
import org.springframework.stereotype.Service;
@Service("bollingerMeanReversionBTC") public class BollingerMeanReversionBTC extends BollingerMeanReversionBacktestStrategy {
    @Override public String getName(){return "bollingerMeanReversionBTC";}
}
