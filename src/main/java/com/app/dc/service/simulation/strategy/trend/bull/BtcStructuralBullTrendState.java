package com.app.dc.service.simulation.strategy.trend.bull;

final class BtcStructuralBullTrendState {
    int lastIndex=-1,triggerUntil=-1,cooldownUntil=-1;
    long setupId=-1;
    double stopPrice=Double.NaN,softStopPrice=Double.NaN;
    String phase=BullTrendSnapshot.WARMUP,triggerType;
    boolean consumed;
    BullTrendSnapshot snapshot=BullTrendSnapshot.none(BtcStructuralBullTrendService.STRATEGY);
    void setup(long id){phase=BullTrendSnapshot.ARMED;setupId=id;triggerUntil=-1;
        stopPrice=softStopPrice=Double.NaN;triggerType=null;consumed=false;}
    void observing(){phase=BullTrendSnapshot.OBSERVING;setupId=-1;triggerUntil=-1;
        stopPrice=softStopPrice=Double.NaN;triggerType=null;consumed=false;}
}
