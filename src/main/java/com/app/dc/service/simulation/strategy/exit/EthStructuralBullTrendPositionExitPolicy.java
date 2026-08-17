package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.trend.bull.EthMultiTimeframeContextService;
import com.app.dc.service.simulation.strategy.trend.bull.EthMultiTimeframeSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * ETH structural bull position lifecycle.
 *
 * Entry timing belongs to 15m, while protection and trend termination only advance
 * on newly closed 1H/4H bars. This deliberately ignores ordinary 15m pullbacks.
 */
@Service
public class EthStructuralBullTrendPositionExitPolicy implements StrategyPositionExitPolicy {
    static final double BREAKEVEN_ACTIVATION_R=3.0;
    static final double BREAKEVEN_BUFFER_RATIO=.001;
    static final double FOUR_HOUR_TRAIL_ACTIVATION_R=8.0;
    static final double FOUR_HOUR_CHANDELIER_ATR=5.5;
    static final int FOUR_HOUR_REVERSAL_CONFIRMATION=3;
    static final int SOFT_INVALIDATION_CONFIRMATION=2;

    @Autowired(required=false) @Qualifier("ethMultiTimeframeContextService")
    private EthMultiTimeframeContextService multiTimeframe;

    public boolean supports(String strategyName){
        return "ethStructuralBullTrend".equalsIgnoreCase(strategyName)
                ||"btcBullLaunchTrend".equalsIgnoreCase(strategyName);
    }

    public PositionExitDecision evaluate(Position position,StrategyPositionExitContext context){
        if(position==null||position.side!=Side.BUY||context==null||multiTimeframe==null)
            return PositionExitDecision.hold();
        EthMultiTimeframeSnapshot snapshot=multiTimeframe.current(context.symbol);
        double close=context.series==null||context.series.getBarCount()==0?Double.NaN:
                context.series.getLastBar().getClosePrice().doubleValue();
        return evaluate(position,snapshot,close);
    }

    public PositionExitDecision evaluate(Position position,EthMultiTimeframeSnapshot snapshot){
        return evaluate(position,snapshot,snapshot==null?Double.NaN:snapshot.oneHourClose);
    }

    private PositionExitDecision evaluate(Position position,EthMultiTimeframeSnapshot snapshot,double currentClose){
        if(position==null||position.side!=Side.BUY||snapshot==null
                ||snapshot.oneHourBarIndex<0||snapshot.fourHourBarIndex<0)
            return PositionExitDecision.hold();
        updateConfirmations(position,snapshot);

        if(position.ethFourHourBearBars>=FOUR_HOUR_REVERSAL_CONFIRMATION){
            position.exitLifecyclePhase="ETH_4H_BEAR_CONFIRMED";
            return PositionExitDecision.exit("eth_4h_bear_reversal_exit");
        }
        if(position.ethSoftInvalidationBars>=SOFT_INVALIDATION_CONFIRMATION){
            position.exitLifecyclePhase="ETH_1H_SOFT_INVALIDATED";
            return PositionExitDecision.exit("eth_1h_soft_invalidation_exit");
        }
        // The disaster stop is intentionally wide enough for a one-to-two day ETH
        // pullback. It must not postpone lifecycle protection by inflating the R unit,
        // so maturity uses at most the original 2 ATR15 structural risk budget.
        double risk=position.initialRiskPriceDistance;
        if(Double.isFinite(position.entryAtr)&&position.entryAtr>0)
            risk=Math.min(risk,2*position.entryAtr);
        if(!Double.isFinite(risk)||risk<=0||!Double.isFinite(position.highestSinceEntry))
            return PositionExitDecision.hold();
        double favorableR=(position.highestSinceEntry-position.entryPrice)/risk;
        if(favorableR>=BREAKEVEN_ACTIVATION_R){
            double candidate=position.entryPrice*(1+BREAKEVEN_BUFFER_RATIO);
            if(Double.isFinite(currentClose)&&currentClose>candidate
                    &&(position.stopPrice==null||candidate>position.stopPrice)){
                position.stopPrice=candidate;position.stopExitReason="eth_breakeven_protection_exit";
                position.exitLifecyclePhase="ETH_BREAKEVEN_PROTECTED";position.trendTrailingActive=true;
            }
        }
        if(favorableR>=FOUR_HOUR_TRAIL_ACTIVATION_R&&Double.isFinite(snapshot.fourHourAtr)
                &&snapshot.fourHourAtr>0){
            double candidate=position.highestSinceEntry-FOUR_HOUR_CHANDELIER_ATR*snapshot.fourHourAtr;
            if(Double.isFinite(candidate)&&candidate>0&&candidate<snapshot.fourHourClose-snapshot.fourHourAtr
                    &&(position.stopPrice==null||candidate>position.stopPrice)){
                position.stopPrice=candidate;position.stopExitReason="eth_4h_chandelier_exit";
                position.exitLifecyclePhase="ETH_4H_CHANDELIER";position.trendTrailingActive=true;
            }
        }
        return PositionExitDecision.hold();
    }

    private void updateConfirmations(Position p,EthMultiTimeframeSnapshot s){
        if(s.fourHourBarIndex!=p.ethLastFourHourIndex){
            p.ethLastFourHourIndex=s.fourHourBarIndex;
            p.ethFourHourBearBars=EthMultiTimeframeSnapshot.FOUR_HOUR_BEAR.equals(s.fourHourTrend)
                    ?p.ethFourHourBearBars+1:0;
        }
        if(s.oneHourBarIndex!=p.ethLastOneHourIndex){
            p.ethLastOneHourIndex=s.oneHourBarIndex;
            boolean invalid=Double.isFinite(p.ethSoftStopPrice)&&Double.isFinite(s.oneHourClose)
                    &&Double.isFinite(s.oneHourEma20Slope)&&s.oneHourClose<p.ethSoftStopPrice
                    &&s.oneHourEma20Slope<0;
            p.ethSoftInvalidationBars=invalid?p.ethSoftInvalidationBars+1:0;
        }
    }

    void setMultiTimeframe(EthMultiTimeframeContextService value){this.multiTimeframe=value;}
}
