package com.app.dc.service.simulation.strategy.trend;

/** Immutable lifecycle view shared by routing, scoring, signals, exits and reports. */
public final class TrendLifecycleSnapshot {
    public static final String WARMUP="WARMUP", NEUTRAL="NEUTRAL";
    public static final String DIRECTIONAL="DIRECTIONAL", IMPULSE="IMPULSE";
    public static final String PULLBACK="PULLBACK", ARMED="ARMED";
    public static final String TRIGGERED="TRIGGERED", RUNNING="RUNNING";
    public static final String INVALIDATED="INVALIDATED", NONE="NONE";
    public static final String BULL="BULL", BEAR="BEAR";

    public final String phase;
    public final String direction;
    public final String reason;
    public final double readiness;
    public final boolean actionable;
    public final double stopPrice;
    public final double triggerPrice;
    public final double atr;
    public final double retracement;
    public final int phaseBars;

    public TrendLifecycleSnapshot(String phase, String direction, String reason,
                                  double readiness, boolean actionable,
                                  double stopPrice, double triggerPrice,
                                  double atr, double retracement, int phaseBars) {
        this.phase=phase; this.direction=direction; this.reason=reason;
        this.readiness=readiness; this.actionable=actionable;
        this.stopPrice=stopPrice; this.triggerPrice=triggerPrice;
        this.atr=atr; this.retracement=retracement; this.phaseBars=phaseBars;
    }

    public static TrendLifecycleSnapshot none() {
        return new TrendLifecycleSnapshot(NEUTRAL,NONE,"TREND_LIFECYCLE_DISABLED",
                0,false,Double.NaN,Double.NaN,Double.NaN,Double.NaN,0);
    }

    public String signalSide() { return BULL.equals(direction)?"BUY":BEAR.equals(direction)?"SELL":"HOLD"; }
}
