package com.app.dc.service.simulation.strategy.trend.bear;

final class EthStructuralBearTrendState {
    int lastIndex=-1,tacticalBars,belowEmaBars,triggerUntil=-1,cooldownUntil=-1;
    long setupId=-1;double lowerHigh=Double.NaN,stopPrice=Double.NaN,softStopPrice=Double.NaN;
    String phase=BearTrendSnapshot.WARMUP,triggerType;boolean consumed,lowerHighConfirmed;
    BearTrendSnapshot snapshot=BearTrendSnapshot.none(EthStructuralBearTrendService.STRATEGY);
    void setup(long id){phase=BearTrendSnapshot.ARMED;setupId=id;tacticalBars=belowEmaBars=0;triggerUntil=-1;
        lowerHigh=stopPrice=softStopPrice=Double.NaN;triggerType=null;consumed=lowerHighConfirmed=false;}
    void observing(){phase=BearTrendSnapshot.OBSERVING;setupId=-1;tacticalBars=belowEmaBars=0;triggerUntil=-1;
        lowerHigh=stopPrice=softStopPrice=Double.NaN;triggerType=null;consumed=lowerHighConfirmed=false;}
}
