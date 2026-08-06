package com.app.dc.service.simulation.strategy.trend.bull;

final class EthStructuralBullTrendState {
    int lastIndex=-1,tacticalBars,aboveEmaBars,triggerUntil=-1,cooldownUntil=-1;
    long setupId=-1;
    double tacticalLow=Double.NaN,higherLow=Double.NaN,stopPrice=Double.NaN,
            softStopPrice=Double.NaN,riskAtr=Double.NaN;
    String phase=BullTrendSnapshot.WARMUP,triggerType;
    boolean consumed,higherLowConfirmed;
    BullTrendSnapshot snapshot=BullTrendSnapshot.none(EthStructuralBullTrendService.STRATEGY);
    void setup(long id){phase=BullTrendSnapshot.ARMED;setupId=id;tacticalBars=0;aboveEmaBars=0;triggerUntil=-1;tacticalLow=higherLow=stopPrice=softStopPrice=riskAtr=Double.NaN;triggerType=null;consumed=false;higherLowConfirmed=false;}
    void observing(){phase=BullTrendSnapshot.OBSERVING;setupId=-1;tacticalBars=0;aboveEmaBars=0;triggerUntil=-1;tacticalLow=higherLow=stopPrice=softStopPrice=riskAtr=Double.NaN;triggerType=null;consumed=false;higherLowConfirmed=false;}
}
