package com.app.dc.service.simulation.strategy.trend.bull;

/** Immutable ETH 4H context plus 1H lifecycle snapshot, based on closed bars only. */
public final class EthMultiTimeframeSnapshot {
    public static final String FOUR_HOUR_BULL="BULL",FOUR_HOUR_TRANSITION_UP="TRANSITION_UP",
            FOUR_HOUR_NEUTRAL="NEUTRAL",FOUR_HOUR_BEAR="BEAR";
    public static final String WARMUP="WARMUP",OBSERVING="OBSERVING",IMPULSE="IMPULSE",
            PULLBACK="PULLBACK",ARMED="ARMED",RUNNING="RUNNING",COOLDOWN="COOLDOWN";

    public final String fourHourTrend;
    public final double fourHourConfidence;
    public final String oneHourPhase;
    public final double readiness;
    public final long setupId;
    public final double pullbackLow;
    public final double impulseHigh;
    public final double oneHourAtr;
    public final int oneHourBarIndex;
    public final double oneHourClose;
    public final double oneHourEma20;
    public final double oneHourEma20Slope;
    public final double oneHourEma60;
    public final double oneHourSwingLow;
    public final int oneHourSwingLowIndex;
    public final int fourHourBarIndex;
    public final double fourHourClose;
    public final double fourHourEma20;
    public final double fourHourEma60;
    public final double fourHourAtr;
    public final String reason;

    public EthMultiTimeframeSnapshot(String fourHourTrend,double fourHourConfidence,
                                     String oneHourPhase,double readiness,long setupId,
                                     double pullbackLow,double impulseHigh,double oneHourAtr,String reason){
        this.fourHourTrend=fourHourTrend;this.fourHourConfidence=fourHourConfidence;
        this.oneHourPhase=oneHourPhase;this.readiness=Math.max(0,Math.min(1,readiness));
        this.setupId=setupId;this.pullbackLow=pullbackLow;this.impulseHigh=impulseHigh;this.oneHourAtr=oneHourAtr;
        this.oneHourBarIndex=-1;this.oneHourClose=Double.NaN;this.oneHourEma20=Double.NaN;this.oneHourEma20Slope=Double.NaN;
        this.oneHourEma60=Double.NaN;this.oneHourSwingLow=Double.NaN;this.oneHourSwingLowIndex=-1;
        this.fourHourBarIndex=-1;this.fourHourClose=Double.NaN;this.fourHourEma20=Double.NaN;
        this.fourHourEma60=Double.NaN;this.fourHourAtr=Double.NaN;
        this.reason=reason;
    }

    public EthMultiTimeframeSnapshot(String fourHourTrend,double fourHourConfidence,
                                     String oneHourPhase,double readiness,long setupId,
                                     double pullbackLow,double impulseHigh,double oneHourAtr,
                                     int oneHourBarIndex,double oneHourClose,double oneHourEma20,double oneHourEma20Slope,
                                     double oneHourEma60,double oneHourSwingLow,int oneHourSwingLowIndex,
                                     int fourHourBarIndex,double fourHourClose,double fourHourEma20,
                                     double fourHourEma60,double fourHourAtr,String reason){
        this.fourHourTrend=fourHourTrend;this.fourHourConfidence=fourHourConfidence;
        this.oneHourPhase=oneHourPhase;this.readiness=Math.max(0,Math.min(1,readiness));
        this.setupId=setupId;this.pullbackLow=pullbackLow;this.impulseHigh=impulseHigh;
        this.oneHourAtr=oneHourAtr;this.oneHourBarIndex=oneHourBarIndex;
        this.oneHourClose=oneHourClose;this.oneHourEma20=oneHourEma20;this.oneHourEma20Slope=oneHourEma20Slope;this.oneHourEma60=oneHourEma60;
        this.oneHourSwingLow=oneHourSwingLow;this.oneHourSwingLowIndex=oneHourSwingLowIndex;
        this.fourHourBarIndex=fourHourBarIndex;this.fourHourClose=fourHourClose;
        this.fourHourEma20=fourHourEma20;this.fourHourEma60=fourHourEma60;this.fourHourAtr=fourHourAtr;
        this.reason=reason;
    }

    public boolean directionAllowsLong(){return FOUR_HOUR_BULL.equals(fourHourTrend)
            ||FOUR_HOUR_TRANSITION_UP.equals(fourHourTrend);}
    public boolean armed(){return ARMED.equals(oneHourPhase);}
    public static EthMultiTimeframeSnapshot warmup(){return new EthMultiTimeframeSnapshot(
            FOUR_HOUR_NEUTRAL,0,WARMUP,0,0,Double.NaN,Double.NaN,Double.NaN,"ETH_MTF_WARMUP");}
}
