package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.trend.bull.EthMultiTimeframeSnapshot;
import com.app.dc.service.simulation.strategy.trend.bull.SolMultiTimeframeContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * SOL campaign exit: entry is timed on 15m, but ownership and termination are
 * governed by closed 1H/4H structure so ordinary intraday pullbacks are held.
 */
@Service
public class BullTrendPositionExitPolicy implements StrategyPositionExitPolicy {
    static final double BREAKEVEN_ACTIVATION_R=3.0;
    static final double BREAKEVEN_BUFFER_RATIO=.001;
    static final double FOUR_HOUR_TRAIL_ACTIVATION_R=6.0;
    static final double FOUR_HOUR_TRAIL_ACTIVATION_PCT=.20;
    static final double FOUR_HOUR_CHANDELIER_ATR=6.0;
    static final double PROFIT_LOCK_R=3.0;
    static final double PROFIT_LOCK_PCT=.08;
    static final double FOUR_HOUR_CORE_EMA_ATR=1.25;
    static final int FOUR_HOUR_REVERSAL_CONFIRMATION=2;
    static final int ONE_HOUR_INVALIDATION_CONFIRMATION=2;

    @Autowired(required=false) private SolMultiTimeframeContextService multiTimeframe;

    public boolean supports(String name){return "solMomentumBullTrend".equalsIgnoreCase(name)
            ||"solBullLaunchTrend".equalsIgnoreCase(name);}

    public PositionExitDecision evaluate(Position p,StrategyPositionExitContext c){
        if(p==null||p.side!=Side.BUY||c==null||multiTimeframe==null)
            return PositionExitDecision.hold();
        EthMultiTimeframeSnapshot snapshot=multiTimeframe.current(c.symbol);
        double close=c.series==null||c.series.getBarCount()==0?Double.NaN:
                c.series.getLastBar().getClosePrice().doubleValue();
        return evaluate(p,snapshot,close);
    }

    public PositionExitDecision evaluate(Position p,EthMultiTimeframeSnapshot s,double currentClose){
        if(p==null||p.side!=Side.BUY||s==null||s.oneHourBarIndex<0||s.fourHourBarIndex<0)
            return PositionExitDecision.hold();
        updateConfirmations(p,s);
        if(p.solFourHourBearBars>=FOUR_HOUR_REVERSAL_CONFIRMATION){
            p.exitLifecyclePhase="SOL_4H_BEAR_CONFIRMED";
            return PositionExitDecision.exit("sol_4h_bear_reversal_exit");
        }
        double risk=p.initialRiskPriceDistance;
        if(Double.isFinite(p.entryAtr)&&p.entryAtr>0)risk=Math.min(risk,2.5*p.entryAtr);
        if(!Double.isFinite(risk)||risk<=0||!Double.isFinite(p.highestSinceEntry))
            return PositionExitDecision.hold();
        double favorableR=(p.highestSinceEntry-p.entryPrice)/risk;
        double favorablePct=(p.highestSinceEntry-p.entryPrice)/p.entryPrice;
        boolean profitLock=favorableR>=PROFIT_LOCK_R&&favorablePct>=PROFIT_LOCK_PCT;
        if(!profitLock&&p.solSoftInvalidationBars>=ONE_HOUR_INVALIDATION_CONFIRMATION){
            p.exitLifecyclePhase="SOL_1H_STRUCTURE_INVALIDATED";
            return PositionExitDecision.exit("sol_1h_structure_invalidation_exit");
        }
        if(favorableR>=BREAKEVEN_ACTIVATION_R){
            double candidate=p.entryPrice*(1+BREAKEVEN_BUFFER_RATIO);
            if(Double.isFinite(currentClose)&&currentClose>candidate
                    &&(p.stopPrice==null||candidate>p.stopPrice)){
                p.stopPrice=candidate;p.stopExitReason="sol_breakeven_protection_exit";
                p.exitLifecyclePhase="SOL_BREAKEVEN_PROTECTED";p.trendTrailingActive=true;
            }
        }
        // Once a SOL position has proved itself, a normal 1H pullback no longer
        // owns the exit. Promote it to a 4H campaign core and trail below the
        // 4H EMA20 with enough ATR room for SOL's ordinary intraday volatility.
        if(profitLock&&Double.isFinite(s.fourHourEma20)
                &&Double.isFinite(s.fourHourAtr)&&s.fourHourAtr>0){
            double candidate=s.fourHourEma20-FOUR_HOUR_CORE_EMA_ATR*s.fourHourAtr;
            if(Double.isFinite(candidate)&&candidate>0
                    &&candidate<s.fourHourClose-.75*s.fourHourAtr
                    &&(p.stopPrice==null||candidate>p.stopPrice)){
                p.stopPrice=candidate;p.stopExitReason="sol_4h_core_trailing_exit";
                p.exitLifecyclePhase="SOL_4H_CORE_HOLD";p.trendTrailingActive=true;
            }else if(p.solSoftInvalidationBars>0){
                p.exitLifecyclePhase="SOL_4H_CORE_HOLD";
            }
        }
        if((favorableR>=FOUR_HOUR_TRAIL_ACTIVATION_R
                ||favorablePct>=FOUR_HOUR_TRAIL_ACTIVATION_PCT)
                &&Double.isFinite(s.fourHourAtr)&&s.fourHourAtr>0){
            double candidate=p.highestSinceEntry-FOUR_HOUR_CHANDELIER_ATR*s.fourHourAtr;
            if(Double.isFinite(candidate)&&candidate>0
                    &&candidate<s.fourHourClose-.75*s.fourHourAtr
                    &&(p.stopPrice==null||candidate>p.stopPrice)){
                p.stopPrice=candidate;p.stopExitReason="sol_4h_chandelier_exit";
                p.exitLifecyclePhase="SOL_4H_CHANDELIER";p.trendTrailingActive=true;
            }
        }
        return PositionExitDecision.hold();
    }

    private void updateConfirmations(Position p,EthMultiTimeframeSnapshot s){
        if(s.fourHourBarIndex!=p.solLastFourHourIndex){
            p.solLastFourHourIndex=s.fourHourBarIndex;
            p.solFourHourBearBars=EthMultiTimeframeSnapshot.FOUR_HOUR_BEAR.equals(s.fourHourTrend)
                    ?p.solFourHourBearBars+1:0;
        }
        if(s.oneHourBarIndex!=p.solLastOneHourIndex){
            p.solLastOneHourIndex=s.oneHourBarIndex;
            boolean belowEntryStructure=Double.isFinite(p.solSoftStopPrice)
                    &&Double.isFinite(s.oneHourClose)&&s.oneHourClose<p.solSoftStopPrice;
            boolean belowTrendStructure=Double.isFinite(s.oneHourClose)
                    &&Double.isFinite(s.oneHourEma60)&&s.oneHourClose<s.oneHourEma60
                    &&Double.isFinite(s.oneHourEma20Slope)&&s.oneHourEma20Slope<0;
            p.solSoftInvalidationBars=(belowEntryStructure||belowTrendStructure)
                    ?p.solSoftInvalidationBars+1:0;
        }
    }

    void setMultiTimeframe(SolMultiTimeframeContextService value){this.multiTimeframe=value;}
}
