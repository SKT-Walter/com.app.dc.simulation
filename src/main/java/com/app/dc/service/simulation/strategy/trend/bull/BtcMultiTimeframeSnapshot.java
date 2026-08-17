package com.app.dc.service.simulation.strategy.trend.bull;

/** Immutable BTC 4H campaign and 1H pullback/recovery snapshot. */
public final class BtcMultiTimeframeSnapshot {
    public static final String FOUR_HOUR_BULL="BULL",FOUR_HOUR_TRANSITION_UP="TRANSITION_UP",
            FOUR_HOUR_NEUTRAL="NEUTRAL",FOUR_HOUR_BEAR="BEAR";
    public static final String WARMUP="WARMUP",OBSERVING="OBSERVING",IMPULSE="IMPULSE",
            PULLBACK="PULLBACK",ARMED="ARMED",RUNNING="RUNNING",COOLDOWN="COOLDOWN";

    public final String fourHourTrend,oneHourPhase,reason;
    public final double fourHourConfidence,readiness,pullbackLow,impulseHigh,oneHourAtr,
            oneHourClose,oneHourEma20,oneHourEma20Slope,oneHourEma60,fourHourClose,
            fourHourEma20,fourHourEma60,fourHourAtr,recoveryLevel;
    public final long setupId;
    public final int oneHourBarIndex,fourHourBarIndex;
    public final boolean oneHourRecoveryConfirmed;

    public BtcMultiTimeframeSnapshot(String fourHourTrend,double fourHourConfidence,
                                     String oneHourPhase,double readiness,long setupId,
                                     double pullbackLow,double impulseHigh,double oneHourAtr,
                                     int oneHourBarIndex,double oneHourClose,double oneHourEma20,
                                     double oneHourEma20Slope,double oneHourEma60,
                                     int fourHourBarIndex,double fourHourClose,double fourHourEma20,
                                     double fourHourEma60,double fourHourAtr,double recoveryLevel,
                                     boolean recoveryConfirmed,String reason){
        this.fourHourTrend=fourHourTrend;this.fourHourConfidence=fourHourConfidence;
        this.oneHourPhase=oneHourPhase;this.readiness=Math.max(0,Math.min(1,readiness));
        this.setupId=setupId;this.pullbackLow=pullbackLow;this.impulseHigh=impulseHigh;
        this.oneHourAtr=oneHourAtr;this.oneHourBarIndex=oneHourBarIndex;
        this.oneHourClose=oneHourClose;this.oneHourEma20=oneHourEma20;
        this.oneHourEma20Slope=oneHourEma20Slope;this.oneHourEma60=oneHourEma60;
        this.fourHourBarIndex=fourHourBarIndex;this.fourHourClose=fourHourClose;
        this.fourHourEma20=fourHourEma20;this.fourHourEma60=fourHourEma60;
        this.fourHourAtr=fourHourAtr;this.recoveryLevel=recoveryLevel;
        this.oneHourRecoveryConfirmed=recoveryConfirmed;this.reason=reason;
    }

    public boolean directionAllowsLong(){return FOUR_HOUR_BULL.equals(fourHourTrend)
            ||FOUR_HOUR_TRANSITION_UP.equals(fourHourTrend);}
    public boolean armed(){return ARMED.equals(oneHourPhase)&&oneHourRecoveryConfirmed;}
    public static BtcMultiTimeframeSnapshot warmup(){return new BtcMultiTimeframeSnapshot(
            FOUR_HOUR_NEUTRAL,0,WARMUP,0,0,Double.NaN,Double.NaN,Double.NaN,-1,
            Double.NaN,Double.NaN,Double.NaN,Double.NaN,-1,Double.NaN,Double.NaN,
            Double.NaN,Double.NaN,Double.NaN,false,"BTC_MTF_WARMUP");}
}
