package com.app.dc.service.simulation.strategy.trend.bear;

/** Immutable current-bar output of a bearish lifecycle state machine. */
public final class BearTrendSnapshot {
    public static final String WARMUP="WARMUP",OBSERVING="OBSERVING",IMPULSE="IMPULSE",
            PULLBACK="PULLBACK",ARMED="ARMED",TRIGGERED="TRIGGERED",RUNNING="RUNNING",
            INVALIDATED="INVALIDATED",COOLDOWN="COOLDOWN";
    public final String strategyName,phase,reason,triggerType;
    public final double readiness,stopPrice;
    public final boolean actionable;
    public final int barIndex;
    public BearTrendSnapshot(String strategyName,String phase,String reason,double readiness,
                             boolean actionable,double stopPrice,String triggerType,int barIndex){
        this.strategyName=strategyName;this.phase=phase;this.reason=reason;
        this.readiness=Math.max(0,Math.min(1,Double.isFinite(readiness)?readiness:0));
        this.actionable=actionable;this.stopPrice=stopPrice;this.triggerType=triggerType;this.barIndex=barIndex;
    }
    public static BearTrendSnapshot none(String name){return new BearTrendSnapshot(name,WARMUP,
            "BEAR_TREND_WARMUP",0,false,Double.NaN,null,-1);}
    public boolean activeCandidate(){return !WARMUP.equals(phase)&&!OBSERVING.equals(phase)
            &&!INVALIDATED.equals(phase)&&!COOLDOWN.equals(phase);}
}
