package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.trend.bull.BtcMultiTimeframeContextService;
import com.app.dc.service.simulation.strategy.trend.bull.BtcMultiTimeframeSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** BTC-owned position lifecycle; no ETH exit state or labels are reused. */
@Service
public class BtcStructuralBullTrendPositionExitPolicy implements StrategyPositionExitPolicy {
    static final int FOUR_HOUR_REVERSAL_CONFIRMATION=3,SOFT_INVALIDATION_CONFIRMATION=2;
    static final double EARLY_PROTECTION_MINIMUM_RETURN=.0125,EARLY_PROTECTION_MINIMUM_R=.75;
    static final double FOUR_HOUR_TRAIL_ACTIVATION_R=5.0,FOUR_HOUR_CHANDELIER_ATR=4.5;
    @Autowired private BtcMultiTimeframeContextService multiTimeframe;

    public boolean supports(String strategyName){return "btcStructuralBullTrend".equalsIgnoreCase(strategyName);}
    public PositionExitDecision evaluate(Position position,StrategyPositionExitContext context){
        if(position==null||position.side!=Side.BUY||context==null)return PositionExitDecision.hold();
        double close=context.series==null||context.series.getBarCount()==0?Double.NaN:
                context.series.getLastBar().getClosePrice().doubleValue();
        return evaluate(position,multiTimeframe.current(context.symbol),close);
    }

    PositionExitDecision evaluate(Position position,BtcMultiTimeframeSnapshot snapshot,double currentClose){
        if(position==null||snapshot==null||snapshot.oneHourBarIndex<0||snapshot.fourHourBarIndex<0)
            return PositionExitDecision.hold();
        updateConfirmations(position,snapshot);
        if(position.btcFourHourBearBars>=FOUR_HOUR_REVERSAL_CONFIRMATION){
            position.exitLifecyclePhase="BTC_4H_BEAR_CONFIRMED";
            return PositionExitDecision.exit("btc_4h_bear_reversal_exit");
        }
        if(position.btcSoftInvalidationBars>=SOFT_INVALIDATION_CONFIRMATION){
            position.exitLifecyclePhase="BTC_1H_SOFT_INVALIDATED";
            return PositionExitDecision.exit("btc_1h_soft_invalidation_exit");
        }
        double risk=position.initialRiskPriceDistance;
        if(!Double.isFinite(risk)||risk<=0||!Double.isFinite(position.highestSinceEntry))return PositionExitDecision.hold();
        double favorable=position.highestSinceEntry-position.entryPrice;
        double favorableR=favorable/risk,returnRatio=favorable/position.entryPrice;
        if(favorableR>=EARLY_PROTECTION_MINIMUM_R&&returnRatio>=EARLY_PROTECTION_MINIMUM_RETURN){
            // 0.10% gross lock leaves roughly +0.02% after the default round-trip fee.
            double candidate=position.entryPrice*(1+.001);
            if(Double.isFinite(currentClose)&&currentClose>candidate
                    &&(position.stopPrice==null||candidate>position.stopPrice)){
                position.stopPrice=candidate;position.stopExitReason="btc_early_profit_protection_exit";
                position.exitLifecyclePhase="BTC_EARLY_PROFIT_PROTECTED";position.trendTrailingActive=true;
            }
        }
        if(favorableR>=FOUR_HOUR_TRAIL_ACTIVATION_R&&Double.isFinite(snapshot.fourHourAtr)
                &&snapshot.fourHourAtr>0){
            double candidate=position.highestSinceEntry-FOUR_HOUR_CHANDELIER_ATR*snapshot.fourHourAtr;
            if(Double.isFinite(candidate)&&candidate>0&&candidate<snapshot.fourHourClose-snapshot.fourHourAtr
                    &&(position.stopPrice==null||candidate>position.stopPrice)){
                position.stopPrice=candidate;position.stopExitReason="btc_4h_chandelier_exit";
                position.exitLifecyclePhase="BTC_4H_CHANDELIER";position.trendTrailingActive=true;
            }
        }
        return PositionExitDecision.hold();
    }

    private void updateConfirmations(Position p,BtcMultiTimeframeSnapshot s){
        if(s.fourHourBarIndex!=p.btcLastFourHourIndex){p.btcLastFourHourIndex=s.fourHourBarIndex;
            p.btcFourHourBearBars=BtcMultiTimeframeSnapshot.FOUR_HOUR_BEAR.equals(s.fourHourTrend)
                    ?p.btcFourHourBearBars+1:0;}
        if(s.oneHourBarIndex!=p.btcLastOneHourIndex){p.btcLastOneHourIndex=s.oneHourBarIndex;
            boolean invalid=Double.isFinite(p.btcSoftStopPrice)&&Double.isFinite(s.oneHourClose)
                    &&Double.isFinite(s.oneHourEma20Slope)&&s.oneHourClose<p.btcSoftStopPrice
                    &&s.oneHourEma20Slope<0;
            p.btcSoftInvalidationBars=invalid?p.btcSoftInvalidationBars+1:0;}
    }
}
