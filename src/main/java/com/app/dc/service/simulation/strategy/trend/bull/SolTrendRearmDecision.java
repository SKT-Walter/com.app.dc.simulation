package com.app.dc.service.simulation.strategy.trend.bull;

/** Immutable result from the SOL continuation rearm state. */
public final class SolTrendRearmDecision {
    public final boolean active;
    public final boolean triggered;
    public final String reason;
    public final double stopPrice;
    public final double softStopPrice;

    private SolTrendRearmDecision(boolean active,boolean triggered,String reason,
                                  double stopPrice,double softStopPrice){
        this.active=active;this.triggered=triggered;this.reason=reason;
        this.stopPrice=stopPrice;this.softStopPrice=softStopPrice;
    }
    public static SolTrendRearmDecision inactive(){return new SolTrendRearmDecision(false,false,"SOL_REARM_INACTIVE",Double.NaN,Double.NaN);}
    public static SolTrendRearmDecision waiting(String reason){return new SolTrendRearmDecision(true,false,reason,Double.NaN,Double.NaN);}
    public static SolTrendRearmDecision triggered(double stop,double soft){return new SolTrendRearmDecision(true,true,"SOL_CONTINUATION_REARM_TRIGGERED",stop,soft);}
}
