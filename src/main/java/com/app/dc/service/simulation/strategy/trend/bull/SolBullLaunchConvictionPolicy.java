package com.app.dc.service.simulation.strategy.trend.bull;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Confirms that a SOL 4H turn-up is more than a single rebound bar.
 * All inputs are derived from closed 4H bars.
 */
@Service
public class SolBullLaunchConvictionPolicy {
    @Value("${backtest.sol-bull-launch.minimum-4h-ema-separation-atr:-0.5}")
    private double minimumEmaSeparationAtr=-0.5;
    @Value("${backtest.sol-bull-launch.minimum-4h-close-above-ema60-atr:0.5}")
    private double minimumCloseAboveEma60Atr=0.5;
    @Value("${backtest.sol-bull-launch.minimum-4h-roc3-pct:1.0}")
    private double minimumRoc3Pct=1.0;

    public boolean allows(double close,double ema20,double ema60,double atr,double roc3Pct){
        if(!finite(close)||!finite(ema20)||!finite(ema60)||!finite(atr)||atr<=0||!finite(roc3Pct))return false;
        double emaSeparationAtr=(ema20-ema60)/atr;
        double closeAboveEma60Atr=(close-ema60)/atr;
        return emaSeparationAtr>=minimumEmaSeparationAtr
                &&(closeAboveEma60Atr>=minimumCloseAboveEma60Atr||roc3Pct>=minimumRoc3Pct);
    }

    private boolean finite(double value){return Double.isFinite(value);}
}
