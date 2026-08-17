package com.app.dc.service.simulation.strategy.trend.bull;

import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SOL-only early launch strategy; its state never mutates the mature continuation strategy. */
@Service
public class SolBullLaunchTrendService {
    public static final String STRATEGY="solBullLaunchTrend";
    private static final int TRIGGER_VALIDITY=4,PROFIT_COOLDOWN=48,STOP_COOLDOWN=96;
    @Value("${backtest.sol-bull-launch.pullback-recovery-enabled:false}")
    private boolean pullbackRecoveryEnabled;
    @Autowired(required=false) private SolBullLaunchContextService multiTimeframe;
    @Autowired(required=false) @Qualifier("ethMultiTimeframeContextService")
    private EthMultiTimeframeContextService btcMacroContext;
    @Autowired(required=false) private SolLaunchPreparationService preparationService;
    private final Map<String,SolBullLaunchTrendState> states=new ConcurrentHashMap<String,SolBullLaunchTrendState>();

    public BullTrendSnapshot update(String symbol,String timeframe,BarSeries series,StructuralTrendSnapshot ignored,BacktestRegime regime){
        EthMultiTimeframeSnapshot context=EthMultiTimeframeSnapshot.warmup();
        if(multiTimeframe!=null&&series!=null&&series.getBarCount()>0)context=multiTimeframe.update(symbol,series.getLastBar().getEndTime().toInstant().toEpochMilli());
        return update(symbol,timeframe,series,regime,context);
    }
    public BullTrendSnapshot update(String symbol,String timeframe,BarSeries series,BacktestRegime regime,EthMultiTimeframeSnapshot context){
        if(!supports(symbol,timeframe)||series==null||series.getBarCount()<60)return BullTrendSnapshot.none(STRATEGY);
        String key=key(symbol,timeframe);SolBullLaunchTrendState s=states.get(key);if(s==null){s=new SolBullLaunchTrendState();states.put(key,s);}int end=series.getEndIndex();if(s.lastIndex>end){s=new SolBullLaunchTrendState();states.put(key,s);}if(s.lastIndex==end)return s.snapshot;s.lastIndex=end;
        double atr=BullTrendMath.atr(series,end,14);if(!Double.isFinite(atr)||atr<=0)return cache(s,end,BullTrendSnapshot.INVALIDATED,"SOL_LAUNCH_INVALID_ATR",0,false,Double.NaN,null);
        if(end<=s.cooldownUntil)return cache(s,end,BullTrendSnapshot.COOLDOWN,"SOL_LAUNCH_TRADE_COOLDOWN",0,false,Double.NaN,null);
        if(BullTrendSnapshot.RUNNING.equals(s.phase))return cache(s,end,s.phase,"SOL_LAUNCH_POSITION_RUNNING",.9,false,s.stopPrice,s.triggerType);
        if(BullTrendSnapshot.TRIGGERED.equals(s.phase)){if(!s.consumed&&end<=s.triggerUntil)return cache(s,end,s.phase,"SOL_LAUNCH_TRIGGER_VALID",1,true,s.stopPrice,s.triggerType);s.observing();}
        SolLaunchPreparationSnapshot preparation=preparationService==null
                ?SolLaunchPreparationSnapshot.warmup()
                :preparationService.update(symbol,timeframe,series,context!=null&&context.armed());
        if(context==null||EthMultiTimeframeSnapshot.WARMUP.equals(context.oneHourPhase)){s.observing();return cache(s,end,BullTrendSnapshot.WARMUP,"SOL_LAUNCH_MTF_WARMUP",0,false,Double.NaN,null);}
        if(!context.armed()){s.observing();String phase=EthMultiTimeframeSnapshot.IMPULSE.equals(context.oneHourPhase)?BullTrendSnapshot.IMPULSE:EthMultiTimeframeSnapshot.PULLBACK.equals(context.oneHourPhase)?BullTrendSnapshot.PULLBACK:BullTrendSnapshot.OBSERVING;return cache(s,end,phase,context.reason,context.readiness,false,Double.NaN,null);}
        if(s.setupId!=context.setupId)s.setup(context.setupId);
        return trigger(s,symbol,series,end,atr,regime,context,preparation);
    }

    private BullTrendSnapshot trigger(SolBullLaunchTrendState s,String symbol,BarSeries x,int end,double atr,BacktestRegime regime,EthMultiTimeframeSnapshot context,SolLaunchPreparationSnapshot preparation){
        s.tacticalBars++;double low=BullTrendMath.low(x,end),close=BullTrendMath.close(x,end),ema20=BullTrendMath.ema(x,end,20);if(close>ema20)s.aboveEmaBars++;else s.aboveEmaBars=0;if(end>=x.getBeginIndex()+2){double left=BullTrendMath.low(x,end-2),pivot=BullTrendMath.low(x,end-1);if(pivot<left&&pivot<low&&pivot>context.pullbackLow+.05*atr){s.higherLowConfirmed=true;s.higherLow=pivot;}}
        double prior8=BullTrendMath.highest(x,end-1,8),extension=(close-ema20)/atr;
        boolean bullishRegime=regime!=null&&"UP".equals(regime.trend)
                &&!"LOW".equals(regime.volatility);
        boolean recoveryRegime=regime!=null&&("UP".equals(regime.trend)||"NONE".equals(regime.trend))
                &&!"HIGH".equals(regime.volatility);
        boolean btc="BTCUSDT".equalsIgnoreCase(symbol);
        EthMultiTimeframeSnapshot macro=btc&&btcMacroContext!=null
                ?btcMacroContext.current(symbol):context;
        boolean btcMacro= !btc || (macro!=null&&EthMultiTimeframeSnapshot.FOUR_HOUR_BULL.equals(macro.fourHourTrend)
                &&macro.fourHourConfidence>=.75&&macro.fourHourClose>macro.fourHourEma20
                &&macro.fourHourEma20>macro.fourHourEma60&&macro.oneHourClose>macro.oneHourEma20
                &&macro.oneHourEma20>macro.oneHourEma60&&macro.oneHourEma20Slope>0);
        double breakoutLevel=btc?BullTrendMath.highest(x,end-1,20):prior8;
        boolean strongBreakout=s.tacticalBars>=1&&bullishRegime&&btcMacro&&BullTrendMath.bull(x,end)
                &&close>breakoutLevel&&BullTrendMath.closeLocation(x,end)>=(btc?.70:.65)
                &&BullTrendMath.volumeRatio(x,end,20)>=(btc?1.10:1.0)
                &&Math.abs(close-x.getBar(end).getOpenPrice().doubleValue())/atr>=(btc?.35:0)
                &&extension<=(btc?1.5:2.5);
        boolean pullbackRecovery=pullbackRecoveryEnabled&&s.tacticalBars>=2&&s.higherLowConfirmed&&s.aboveEmaBars>=2
                &&recoveryRegime&&BullTrendMath.bull(x,end)&&close>BullTrendMath.close(x,end-1)
                &&BullTrendMath.closeLocation(x,end)>=.58
                &&BullTrendMath.volumeRatio(x,end,20)>=.75&&extension<=2.0;
        boolean sequenceLaunch=!btc&&preparation!=null&&preparation.confirmed&&preparation.barIndex==end
                &&regime!=null&&"UP".equals(regime.trend);
        if(preparation!=null&&preparation.breakoutPending&&!sequenceLaunch)return cache(s,end,BullTrendSnapshot.ARMED,
                preparation.reason,preparation.readiness,false,Double.NaN,null);
        if(!sequenceLaunch&&!strongBreakout&&!pullbackRecovery)return cache(s,end,BullTrendSnapshot.ARMED,
                preparation!=null&&!BullTrendSnapshot.WARMUP.equals(preparation.phase)
                        &&!BullTrendSnapshot.OBSERVING.equals(preparation.phase)?preparation.reason:
                pullbackRecoveryEnabled?"SOL_LAUNCH_15M_WAITING_DUAL_TRIGGER":"SOL_LAUNCH_15M_WAITING_BREAKOUT",
                context.readiness,false,Double.NaN,null);
        double anchor=pullbackRecovery?s.higherLow:BullTrendMath.lowest(x,end,sequenceLaunch?8:4);
        double structuralStop=anchor-.5*atr,distance=close-structuralStop;
        if(distance>5*atr)return cache(s,end,BullTrendSnapshot.ARMED,"SOL_LAUNCH_STOP_TOO_WIDE",context.readiness,false,Double.NaN,null);
        s.softStopPrice=structuralStop;double disaster=Math.max(4*atr,Double.isFinite(context.oneHourAtr)?1.5*context.oneHourAtr:0);disaster=Math.min(disaster,.06*close);s.stopPrice=Math.min(structuralStop,close-disaster);s.phase=BullTrendSnapshot.TRIGGERED;s.triggerUntil=end+TRIGGER_VALIDITY;
        s.triggerType=sequenceLaunch?"SOL_COMPRESSION_ACCELERATION_LAUNCH":strongBreakout?"SOL_4H_TURN_UP_15M_BREAKOUT":"SOL_4H_TURN_UP_15M_PULLBACK_RECOVERY";
        s.consumed=false;return cache(s,end,s.phase,"SOL_BULL_LAUNCH_TRIGGERED",1,true,s.stopPrice,s.triggerType);
    }
    public BullTrendSnapshot current(String symbol,String timeframe){SolBullLaunchTrendState s=states.get(key(symbol,timeframe));return s==null?BullTrendSnapshot.none(STRATEGY):s.snapshot;}
    public double currentSoftStop(String symbol,String timeframe){SolBullLaunchTrendState s=states.get(key(symbol,timeframe));return s==null?Double.NaN:s.softStopPrice;}
    public void consume(String symbol,String timeframe,int bar){SolBullLaunchTrendState s=states.get(key(symbol,timeframe));if(s!=null&&s.lastIndex==bar&&BullTrendSnapshot.TRIGGERED.equals(s.phase)){s.consumed=true;s.phase=BullTrendSnapshot.RUNNING;if(multiTimeframe!=null)multiTimeframe.consume(symbol,s.setupId);if(preparationService!=null)preparationService.consume(symbol,timeframe);s.snapshot=new BullTrendSnapshot(STRATEGY,s.phase,"SOL_LAUNCH_SIGNAL_CONSUMED",.9,false,s.stopPrice,s.triggerType,bar);}}
    public void tradeClosed(String symbol,int exitBar,TradeRecord trade){for(Map.Entry<String,SolBullLaunchTrendState> e:states.entrySet())if(e.getKey().startsWith(symbol.toUpperCase()+"|")){SolBullLaunchTrendState s=e.getValue();s.observing();boolean loss=trade!=null&&trade.returnPct!=null&&trade.returnPct.signum()<0;s.cooldownUntil=exitBar+(loss?STOP_COOLDOWN:PROFIT_COOLDOWN);s.phase=BullTrendSnapshot.COOLDOWN;}if(multiTimeframe!=null)multiTimeframe.tradeClosed(symbol);if(preparationService!=null)preparationService.reset(symbol);}
    public void reset(String symbol){String prefix=symbol==null?"":symbol.toUpperCase()+"|";states.keySet().removeIf(k->k.startsWith(prefix));if(multiTimeframe!=null)multiTimeframe.reset(symbol);if(preparationService!=null)preparationService.reset(symbol);}
    private BullTrendSnapshot cache(SolBullLaunchTrendState s,int end,String phase,String reason,double ready,boolean action,double stop,String trigger){s.phase=phase;s.snapshot=new BullTrendSnapshot(STRATEGY,phase,reason,ready,action,stop,trigger,end);return s.snapshot;}
    private boolean supports(String symbol,String timeframe){return ("SOLUSDT".equalsIgnoreCase(symbol)||"BTCUSDT".equalsIgnoreCase(symbol))&&"15M".equalsIgnoreCase(timeframe);}private String key(String symbol,String timeframe){return (symbol==null?"":symbol.toUpperCase())+"|"+(timeframe==null?"":timeframe.toUpperCase());}
}
