package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeContextService;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Slow, symmetric lifecycle protection for ETH structural short positions. */
@Service
public class EthStructuralBearTrendPositionExitPolicy implements StrategyPositionExitPolicy {
    static final double BREAKEVEN_ACTIVATION_R=2.5;
    static final double BREAKEVEN_BUFFER_RATIO=.001;
    static final double FOUR_HOUR_TRAIL_ACTIVATION_R=5.0;
    static final double FOUR_HOUR_CHANDELIER_ATR=5.0;
    static final int FOUR_HOUR_REVERSAL_CONFIRMATION=3;
    static final int SOFT_INVALIDATION_CONFIRMATION=2;

    @Autowired(required=false) private EthBearMultiTimeframeContextService multiTimeframe;

    public boolean supports(String strategyName){
        return "ethStructuralBearTrend".equalsIgnoreCase(strategyName);
    }

    public PositionExitDecision evaluate(Position position,StrategyPositionExitContext context){
        if(position==null||position.side!=Side.SELL||context==null||multiTimeframe==null)
            return PositionExitDecision.hold();
        EthBearMultiTimeframeSnapshot snapshot=multiTimeframe.current(context.symbol);
        double close=context.series==null||context.series.getBarCount()==0?Double.NaN:
                context.series.getLastBar().getClosePrice().doubleValue();
        return evaluate(position,snapshot,close);
    }

    PositionExitDecision evaluate(Position position,EthBearMultiTimeframeSnapshot snapshot,double currentClose){
        if(position==null||position.side!=Side.SELL||snapshot==null
                ||snapshot.oneHourBarIndex<0||snapshot.fourHourBarIndex<0)
            return PositionExitDecision.hold();
        updateConfirmations(position,snapshot);
        if(position.ethBearFourHourBullBars>=FOUR_HOUR_REVERSAL_CONFIRMATION){
            position.exitLifecyclePhase="ETH_BEAR_4H_BULL_CONFIRMED";
            return PositionExitDecision.exit("eth_bear_4h_bull_reversal_exit");
        }
        if(position.ethBearSoftInvalidationBars>=SOFT_INVALIDATION_CONFIRMATION){
            position.exitLifecyclePhase="ETH_BEAR_1H_SOFT_INVALIDATED";
            return PositionExitDecision.exit("eth_bear_1h_soft_invalidation_exit");
        }
        double risk=position.initialRiskPriceDistance;
        if(Double.isFinite(position.entryAtr)&&position.entryAtr>0)risk=Math.min(risk,2*position.entryAtr);
        if(!Double.isFinite(risk)||risk<=0||!Double.isFinite(position.lowestSinceEntry))
            return PositionExitDecision.hold();
        double favorableR=(position.entryPrice-position.lowestSinceEntry)/risk;
        if(favorableR>=BREAKEVEN_ACTIVATION_R){
            double candidate=position.entryPrice*(1-BREAKEVEN_BUFFER_RATIO);
            if(Double.isFinite(currentClose)&&currentClose<candidate
                    &&(position.stopPrice==null||candidate<position.stopPrice)){
                position.stopPrice=candidate;position.stopExitReason="eth_bear_breakeven_protection_exit";
                position.exitLifecyclePhase="ETH_BEAR_BREAKEVEN_PROTECTED";position.trendTrailingActive=true;
            }
        }
        // A slow ETH short must keep enough room for rebounds, but it must not
        // return a mature bearish leg to breakeven. Lock only a fraction of MFE.
        double lockedR=favorableR>=10?5.0:favorableR>=6?2.5:favorableR>=4?1.0:0;
        if(lockedR>0){
            double candidate=position.entryPrice-lockedR*risk;
            if(Double.isFinite(currentClose)&&currentClose<candidate
                    &&(position.stopPrice==null||candidate<position.stopPrice)){
                position.stopPrice=candidate;position.stopExitReason="eth_bear_staged_profit_lock_exit";
                position.exitLifecyclePhase="ETH_BEAR_STAGED_PROFIT_LOCK";position.trendTrailingActive=true;
            }
        }
        if(favorableR>=FOUR_HOUR_TRAIL_ACTIVATION_R&&Double.isFinite(snapshot.fourHourAtr)
                &&snapshot.fourHourAtr>0){
            double candidate=position.lowestSinceEntry+FOUR_HOUR_CHANDELIER_ATR*snapshot.fourHourAtr;
            if(Double.isFinite(candidate)&&candidate>snapshot.fourHourClose+snapshot.fourHourAtr
                    &&(position.stopPrice==null||candidate<position.stopPrice)){
                position.stopPrice=candidate;position.stopExitReason="eth_bear_4h_chandelier_exit";
                position.exitLifecyclePhase="ETH_BEAR_4H_CHANDELIER";position.trendTrailingActive=true;
            }
        }
        return PositionExitDecision.hold();
    }

    private void updateConfirmations(Position p,EthBearMultiTimeframeSnapshot s){
        if(s.fourHourBarIndex!=p.ethBearLastFourHourIndex){
            p.ethBearLastFourHourIndex=s.fourHourBarIndex;
            p.ethBearFourHourBullBars=EthBearMultiTimeframeSnapshot.BULL.equals(s.fourHourTrend)
                    ?p.ethBearFourHourBullBars+1:0;
        }
        if(s.oneHourBarIndex!=p.ethBearLastOneHourIndex){
            p.ethBearLastOneHourIndex=s.oneHourBarIndex;
            boolean invalid=Double.isFinite(p.ethBearSoftStopPrice)&&Double.isFinite(s.oneHourClose)
                    &&Double.isFinite(s.oneHourEma20Slope)&&s.oneHourClose>p.ethBearSoftStopPrice
                    &&s.oneHourEma20Slope>0;
            p.ethBearSoftInvalidationBars=invalid?p.ethBearSoftInvalidationBars+1:0;
        }
    }

    void setMultiTimeframe(EthBearMultiTimeframeContextService value){this.multiTimeframe=value;}
}
