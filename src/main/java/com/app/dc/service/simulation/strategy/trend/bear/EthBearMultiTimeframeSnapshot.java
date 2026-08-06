package com.app.dc.service.simulation.strategy.trend.bear;

/** Closed-bar ETH 4H bearish context plus 1H impulse/pullback lifecycle. */
public final class EthBearMultiTimeframeSnapshot {
    public static final String BULL="BULL",BEAR="BEAR",TRANSITION_DOWN="TRANSITION_DOWN",NEUTRAL="NEUTRAL";
    public static final String WARMUP="WARMUP",OBSERVING="OBSERVING",IMPULSE="IMPULSE",
            PULLBACK="PULLBACK",ARMED="ARMED",RUNNING="RUNNING",COOLDOWN="COOLDOWN";
    public final String fourHourTrend,oneHourPhase,reason;
    public final double fourHourConfidence,readiness,pullbackHigh,impulseLow,oneHourAtr;
    public final int oneHourBarIndex,fourHourBarIndex;
    public final double oneHourClose,oneHourEma20,oneHourEma20Slope,oneHourEma60;
    public final double fourHourClose,fourHourEma20,fourHourEma60,fourHourAtr;
    public final boolean oneHourContinuationConfirmed;
    public final double fourHourSupportBelow;
    public final String dailyState;
    public final double dailyConfidence;
    public final boolean fastBearBreakdownActive;
    public final boolean bearCampaignActive;
    public final double campaignDrawdownPct;
    public final boolean oneHourStrongMomentum;
    public final long setupId;
    public EthBearMultiTimeframeSnapshot(String trend,double confidence,String phase,double readiness,long setupId,
            double pullbackHigh,double impulseLow,double oneHourAtr,int oneHourBarIndex,double oneHourClose,
            double oneHourEma20,double oneHourEma20Slope,double oneHourEma60,int fourHourBarIndex,
            double fourHourClose,double fourHourEma20,double fourHourEma60,double fourHourAtr,String reason){
        this(trend,confidence,phase,readiness,setupId,pullbackHigh,impulseLow,oneHourAtr,
                oneHourBarIndex,oneHourClose,oneHourEma20,oneHourEma20Slope,oneHourEma60,
                fourHourBarIndex,fourHourClose,fourHourEma20,fourHourEma60,fourHourAtr,
                false,Double.NaN,EthDailyBearContextSnapshot.WARMUP,0,reason);
    }
    public EthBearMultiTimeframeSnapshot(String trend,double confidence,String phase,double readiness,long setupId,
            double pullbackHigh,double impulseLow,double oneHourAtr,int oneHourBarIndex,double oneHourClose,
            double oneHourEma20,double oneHourEma20Slope,double oneHourEma60,int fourHourBarIndex,
            double fourHourClose,double fourHourEma20,double fourHourEma60,double fourHourAtr,
            boolean continuationConfirmed,double supportBelow,String reason){
        this(trend,confidence,phase,readiness,setupId,pullbackHigh,impulseLow,oneHourAtr,
                oneHourBarIndex,oneHourClose,oneHourEma20,oneHourEma20Slope,oneHourEma60,
                fourHourBarIndex,fourHourClose,fourHourEma20,fourHourEma60,fourHourAtr,
                continuationConfirmed,supportBelow,EthDailyBearContextSnapshot.WARMUP,0,reason);
    }
    public EthBearMultiTimeframeSnapshot(String trend,double confidence,String phase,double readiness,long setupId,
            double pullbackHigh,double impulseLow,double oneHourAtr,int oneHourBarIndex,double oneHourClose,
            double oneHourEma20,double oneHourEma20Slope,double oneHourEma60,int fourHourBarIndex,
            double fourHourClose,double fourHourEma20,double fourHourEma60,double fourHourAtr,
            boolean continuationConfirmed,double supportBelow,String dailyState,double dailyConfidence,String reason){
        this(trend,confidence,phase,readiness,setupId,pullbackHigh,impulseLow,oneHourAtr,
                oneHourBarIndex,oneHourClose,oneHourEma20,oneHourEma20Slope,oneHourEma60,
                fourHourBarIndex,fourHourClose,fourHourEma20,fourHourEma60,fourHourAtr,
                continuationConfirmed,supportBelow,dailyState,dailyConfidence,false,false,reason);
    }
    public EthBearMultiTimeframeSnapshot(String trend,double confidence,String phase,double readiness,long setupId,
            double pullbackHigh,double impulseLow,double oneHourAtr,int oneHourBarIndex,double oneHourClose,
            double oneHourEma20,double oneHourEma20Slope,double oneHourEma60,int fourHourBarIndex,
            double fourHourClose,double fourHourEma20,double fourHourEma60,double fourHourAtr,
            boolean continuationConfirmed,double supportBelow,String dailyState,double dailyConfidence,
            boolean fastBreakdownActive,boolean strongOneHourMomentum,String reason){
        this(trend,confidence,phase,readiness,setupId,pullbackHigh,impulseLow,oneHourAtr,oneHourBarIndex,
                oneHourClose,oneHourEma20,oneHourEma20Slope,oneHourEma60,fourHourBarIndex,fourHourClose,
                fourHourEma20,fourHourEma60,fourHourAtr,continuationConfirmed,supportBelow,dailyState,
                dailyConfidence,fastBreakdownActive,fastBreakdownActive,0,strongOneHourMomentum,reason);
    }
    public EthBearMultiTimeframeSnapshot(String trend,double confidence,String phase,double readiness,long setupId,
            double pullbackHigh,double impulseLow,double oneHourAtr,int oneHourBarIndex,double oneHourClose,
            double oneHourEma20,double oneHourEma20Slope,double oneHourEma60,int fourHourBarIndex,
            double fourHourClose,double fourHourEma20,double fourHourEma60,double fourHourAtr,
            boolean continuationConfirmed,double supportBelow,String dailyState,double dailyConfidence,
            boolean fastBreakdownActive,boolean bearCampaignActive,double campaignDrawdownPct,
            boolean strongOneHourMomentum,String reason){
        this.fourHourTrend=trend;this.fourHourConfidence=confidence;this.oneHourPhase=phase;
        this.readiness=Math.max(0,Math.min(1,readiness));this.setupId=setupId;
        this.pullbackHigh=pullbackHigh;this.impulseLow=impulseLow;this.oneHourAtr=oneHourAtr;
        this.oneHourBarIndex=oneHourBarIndex;this.oneHourClose=oneHourClose;this.oneHourEma20=oneHourEma20;
        this.oneHourEma20Slope=oneHourEma20Slope;this.oneHourEma60=oneHourEma60;
        this.fourHourBarIndex=fourHourBarIndex;this.fourHourClose=fourHourClose;
        this.fourHourEma20=fourHourEma20;this.fourHourEma60=fourHourEma60;this.fourHourAtr=fourHourAtr;this.reason=reason;
        this.oneHourContinuationConfirmed=continuationConfirmed;
        this.fourHourSupportBelow=supportBelow;
        this.dailyState=dailyState;this.dailyConfidence=dailyConfidence;
        this.fastBearBreakdownActive=fastBreakdownActive;
        this.bearCampaignActive=bearCampaignActive;
        this.campaignDrawdownPct=Math.max(0,campaignDrawdownPct);
        this.oneHourStrongMomentum=strongOneHourMomentum;
    }
    /** Transition is diagnostic only; execution requires a fully confirmed 4H bear. */
    public boolean directionAllowsShort(){return BEAR.equals(fourHourTrend)||bearCampaignActive;}
    public boolean armed(){return ARMED.equals(oneHourPhase);}
    public static EthBearMultiTimeframeSnapshot warmup(){return new EthBearMultiTimeframeSnapshot(NEUTRAL,0,WARMUP,0,0,
            Double.NaN,Double.NaN,Double.NaN,-1,Double.NaN,Double.NaN,Double.NaN,Double.NaN,-1,
            Double.NaN,Double.NaN,Double.NaN,Double.NaN,"ETH_BEAR_MTF_WARMUP");}
}
