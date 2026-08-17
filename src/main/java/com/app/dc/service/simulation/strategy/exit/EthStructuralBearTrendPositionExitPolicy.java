package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeContextService;
import com.app.dc.service.simulation.strategy.trend.bear.EthBearMultiTimeframeSnapshot;
import com.app.dc.service.simulation.strategy.trend.bear.EthDailyBearContextSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.app.dc.service.simulation.strategy.SymbolStrategyNames;

/** Slow, symmetric lifecycle protection for ETH structural short positions. */
@Service
public class EthStructuralBearTrendPositionExitPolicy implements StrategyPositionExitPolicy {
    static final double BREAKEVEN_ACTIVATION_R=2.5;
    static final double BREAKEVEN_BUFFER_RATIO=.001;
    static final int FOUR_HOUR_REVERSAL_CONFIRMATION=3;
    static final int SOFT_INVALIDATION_CONFIRMATION=8;

    @Autowired(required=false) private EthBearMultiTimeframeContextService multiTimeframe;

    public boolean supports(String strategyName){
        return "ethStructuralBearTrend".equalsIgnoreCase(strategyName)
                ||"solStructuralBearTrend".equalsIgnoreCase(strategyName)
                ||"btcStructuralBearTrend".equalsIgnoreCase(strategyName);
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
        int reversalBars=fourHourReversalBars(snapshot.dailyState);
        boolean dailyBull=EthDailyBearContextSnapshot.BULL.equals(snapshot.dailyState);
        boolean dailyConfirmedBear=EthDailyBearContextSnapshot.BEAR.equals(snapshot.dailyState);
        boolean brokeFourHourStructure=!snapshot.bearCampaignActive&&!dailyConfirmedBear&&Double.isFinite(snapshot.fourHourClose)
                &&Double.isFinite(snapshot.fourHourEma60)&&snapshot.fourHourClose>snapshot.fourHourEma60;
        boolean confirmedFourHourReversal=!snapshot.bearCampaignActive&&!dailyConfirmedBear
                &&position.ethBearFourHourBullBars>=reversalBars;
        if(dailyBull||confirmedFourHourReversal||brokeFourHourStructure){
            position.exitLifecyclePhase="ETH_BEAR_4H_BULL_CONFIRMED";
            return PositionExitDecision.exit("eth_bear_4h_bull_reversal_exit");
        }
        if(position.ethBearSoftInvalidationBars>=SOFT_INVALIDATION_CONFIRMATION){
            position.exitLifecyclePhase="ETH_BEAR_1H_SOFT_INVALIDATED";
            return PositionExitDecision.exit("eth_bear_1h_soft_invalidation_exit");
        }
        double risk=position.initialRiskPriceDistance;
        if(!Double.isFinite(risk)||risk<=0||!Double.isFinite(position.lowestSinceEntry))
            return PositionExitDecision.hold();
        double favorableR=(position.entryPrice-position.lowestSinceEntry)/risk;
        double mfe=position.maxFavorableExcursionPct;
        // Do not strangle a multi-month leg after its first ordinary rebound.
        // Protection begins only after a meaningful wave has developed and
        // tightens progressively as the campaign matures.
        boolean sol="solStructuralBearTrend".equalsIgnoreCase(SymbolStrategyNames.baseName(position.strategyName));
        double capture=sol?(mfe>=.08?.90:0):(mfe>=.30?.92:mfe>=.15?.701:mfe>=.08?.50:0);
        if(capture>0){
            double candidate=position.entryPrice*(1-mfe*capture);
            if(Double.isFinite(currentClose)&&currentClose<candidate
                    &&(position.stopPrice==null||candidate<position.stopPrice)){
                position.stopPrice=candidate;
                boolean mature=mfe>=.30;
                position.stopExitReason=sol?"sol_bear_fast_mfe_capture_exit":
                        mature?"eth_bear_mfe_capture_exit":"eth_bear_staged_profit_lock_exit";
                position.exitLifecyclePhase=sol?"SOL_BEAR_FAST_MFE_CAPTURE":
                        mature?"ETH_BEAR_MFE_CAPTURE":"ETH_BEAR_STAGED_PROFIT_LOCK";
                position.trendTrailingActive=true;
            }
        }
        if(favorableR>=BREAKEVEN_ACTIVATION_R){
            double candidate=position.entryPrice*(1-BREAKEVEN_BUFFER_RATIO);
            if(Double.isFinite(currentClose)&&currentClose<candidate
                    &&(position.stopPrice==null||candidate<position.stopPrice)){
                position.stopPrice=candidate;position.stopExitReason=sol?"sol_bear_breakeven_protection_exit":"eth_bear_breakeven_protection_exit";
                position.exitLifecyclePhase=sol?"SOL_BEAR_BREAKEVEN_PROTECTED":"ETH_BEAR_BREAKEVEN_PROTECTED";position.trendTrailingActive=true;
            }
        }
        return PositionExitDecision.hold();
    }

    private int fourHourReversalBars(String dailyState){
        if(EthDailyBearContextSnapshot.BEAR.equals(dailyState))return 6;
        if(EthDailyBearContextSnapshot.BEAR_RISK.equals(dailyState))return 4;
        return FOUR_HOUR_REVERSAL_CONFIRMATION;
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
