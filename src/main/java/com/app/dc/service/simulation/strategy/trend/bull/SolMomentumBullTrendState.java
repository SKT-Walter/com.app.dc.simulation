package com.app.dc.service.simulation.strategy.trend.bull;

final class SolMomentumBullTrendState {
    int lastIndex=-1,preparationBars,armedUntil=-1,triggerUntil=-1,cooldownUntil=-1;
    double compressionLow=Double.NaN,compressionHigh=Double.NaN,stopPrice=Double.NaN;
    String phase=BullTrendSnapshot.WARMUP,triggerType;
    boolean consumed;
    BullTrendSnapshot snapshot=BullTrendSnapshot.none(SolMomentumBullTrendService.STRATEGY);
    void observing(){phase=BullTrendSnapshot.OBSERVING;preparationBars=0;armedUntil=-1;triggerUntil=-1;compressionLow=compressionHigh=stopPrice=Double.NaN;triggerType=null;consumed=false;}
}
