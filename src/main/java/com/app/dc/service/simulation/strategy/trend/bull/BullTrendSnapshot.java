package com.app.dc.service.simulation.strategy.trend.bull;

/** Immutable, current-bar snapshot produced by one symbol-specific bull state machine. */
public final class BullTrendSnapshot {
    public static final String WARMUP="WARMUP", OBSERVING="OBSERVING",
            IMPULSE="IMPULSE", PULLBACK="PULLBACK", PREPARING="PREPARING",
            ARMED="ARMED", RECOVERING="RECOVERING", TRIGGERED="TRIGGERED", RUNNING="RUNNING",
            INVALIDATED="INVALIDATED", COOLDOWN="COOLDOWN";

    public final String strategyName;
    public final String phase;
    public final String reason;
    public final double readiness;
    public final boolean actionable;
    public final double stopPrice;
    public final String triggerType;
    public final int barIndex;

    public BullTrendSnapshot(String strategyName,String phase,String reason,double readiness,
                             boolean actionable,double stopPrice,String triggerType,int barIndex){
        this.strategyName=strategyName;this.phase=phase;this.reason=reason;
        this.readiness=Math.max(0,Math.min(1,Double.isFinite(readiness)?readiness:0));
        this.actionable=actionable;this.stopPrice=stopPrice;
        this.triggerType=triggerType;this.barIndex=barIndex;
    }

    public static BullTrendSnapshot none(String strategyName){
        return new BullTrendSnapshot(strategyName,WARMUP,"BULL_TREND_WARMUP",0,
                false,Double.NaN,null,-1);
    }

    public boolean activeCandidate(){
        return !WARMUP.equals(phase)&&!OBSERVING.equals(phase)
                &&!INVALIDATED.equals(phase)&&!COOLDOWN.equals(phase);
    }
}
