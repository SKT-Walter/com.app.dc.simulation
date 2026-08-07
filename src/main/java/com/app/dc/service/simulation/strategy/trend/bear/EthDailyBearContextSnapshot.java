package com.app.dc.service.simulation.strategy.trend.bear;

/** Closed daily backdrop. It never emits a trade; it only qualifies 4H bearish setups. */
public final class EthDailyBearContextSnapshot {
    public static final String WARMUP="WARMUP",BULL="BULL",BULL_WEAKENING="BULL_WEAKENING",
            DISTRIBUTION_RISK="DISTRIBUTION_RISK",BEAR_RISK="BEAR_RISK",BEAR="BEAR";
    public final String state,reason;
    public final double confidence,close,ema20,ema60,atr;
    public final int barIndex;
    public EthDailyBearContextSnapshot(String state,double confidence,double close,double ema20,
                                       double ema60,double atr,int barIndex,String reason){
        this.state=state;this.confidence=confidence;this.close=close;this.ema20=ema20;
        this.ema60=ema60;this.atr=atr;this.barIndex=barIndex;this.reason=reason;
    }
    public boolean allowsNormalShort(){return BEAR.equals(state)||BEAR_RISK.equals(state)||DISTRIBUTION_RISK.equals(state);}
    public boolean allowsDefensiveShort(){return allowsNormalShort()||BULL_WEAKENING.equals(state);}
    public static EthDailyBearContextSnapshot warmup(){return new EthDailyBearContextSnapshot(
            WARMUP,0,Double.NaN,Double.NaN,Double.NaN,Double.NaN,-1,"ETH_DAILY_WARMUP");}
}
