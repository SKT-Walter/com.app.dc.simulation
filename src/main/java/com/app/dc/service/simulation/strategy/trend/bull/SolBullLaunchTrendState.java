package com.app.dc.service.simulation.strategy.trend.bull;

final class SolBullLaunchTrendState {
    int lastIndex=-1,tacticalBars,aboveEmaBars,triggerUntil=-1,cooldownUntil=-1;
    long setupId=-1;
    double higherLow=Double.NaN,stopPrice=Double.NaN,softStopPrice=Double.NaN;
    boolean consumed,higherLowConfirmed;
    String phase=BullTrendSnapshot.WARMUP,triggerType;
    BullTrendSnapshot snapshot=BullTrendSnapshot.none(SolBullLaunchTrendService.STRATEGY);
    void setup(long id){phase=BullTrendSnapshot.ARMED;setupId=id;tacticalBars=aboveEmaBars=0;triggerUntil=-1;higherLow=stopPrice=softStopPrice=Double.NaN;consumed=higherLowConfirmed=false;triggerType=null;}
    void observing(){phase=BullTrendSnapshot.OBSERVING;setupId=-1;tacticalBars=aboveEmaBars=0;triggerUntil=-1;higherLow=stopPrice=softStopPrice=Double.NaN;consumed=higherLowConfirmed=false;triggerType=null;}
}
