package com.app.dc.service.simulation.strategy.exit;

import com.app.dc.po.Side;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

/** SOL-specific Chandelier exit. ETH owns an independent higher-timeframe lifecycle policy. */
@Service
public class BullTrendPositionExitPolicy implements StrategyPositionExitPolicy {
    public boolean supports(String name){return "solMomentumBullTrend".equalsIgnoreCase(name);}

    public PositionExitDecision evaluate(Position p,StrategyPositionExitContext c){
        if(p==null||p.side!=Side.BUY||c==null||c.series==null)return PositionExitDecision.hold();
        StructuralTrendSnapshot structural=c.structuralTrend;
        if(structural!=null&&structural.ready&&structural.isBear()){
            p.exitLifecyclePhase="SLOW_STRUCTURE_REVERSED";
            return PositionExitDecision.exit("bull_slow_structure_reversed");
        }
        BarSeries s=c.series;int end=s.getEndIndex();
        if(end<=p.entryIndex)return PositionExitDecision.hold();
        double atr=BinanceStrategyMath.atr(s,end,14),close=BinanceStrategyMath.close(s,end);
        double entryAtr=Double.isFinite(p.entryAtr)&&p.entryAtr>0?p.entryAtr
                :Double.isFinite(p.initialRiskPriceDistance)?p.initialRiskPriceDistance/2d:atr;
        if(!Double.isFinite(atr)||atr<=0||!Double.isFinite(entryAtr)||entryAtr<=0)
            return PositionExitDecision.hold();
        double favorable=(p.highestSinceEntry-p.entryPrice)/entryAtr;
        boolean sol="solMomentumBullTrend".equalsIgnoreCase(p.strategyName);
        double activation=2,matureActivation=sol?5:4;
        double multiplier=favorable>=matureActivation?(sol?3.5:3):(sol?4:3.5);
        if(favorable<activation)return PositionExitDecision.hold();
        p.trendTrailingActive=true;
        double candidate=p.highestSinceEntry-multiplier*atr;
        if(end>=s.getBeginIndex()+2){
            double left=s.getBar(end-2).getLowPrice().doubleValue();
            double pivot=s.getBar(end-1).getLowPrice().doubleValue();
            double right=s.getBar(end).getLowPrice().doubleValue();
            if(pivot<left&&pivot<right)candidate=Math.max(candidate,pivot-.5*atr);
        }
        candidate=Math.min(candidate,close-entryAtr);
        if(Double.isFinite(candidate)&&candidate>0&&(p.stopPrice==null||candidate>p.stopPrice)){
            p.stopPrice=candidate;p.stopExitReason="bull_atr_structure_trailing_exit";
            p.exitLifecyclePhase=favorable>=matureActivation?"MATURE_TRAILING":"INITIAL_TRAILING";
        }
        return PositionExitDecision.hold();
    }
}
