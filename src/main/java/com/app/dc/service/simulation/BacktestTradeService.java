package com.app.dc.service.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.strategy.core.StrategyRuntimeModels.Position;
import com.app.dc.strategy.core.StrategyRuntimeModels.TradeRecord;
import com.app.dc.strategy.core.strategy.runtime.ModelRiskExitDecision;
import com.app.dc.strategy.core.strategy.runtime.StrategyEntryRiskService;
import com.app.dc.strategy.core.strategy.runtime.StrategyModelRiskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BacktestTradeService {

    @Autowired
    private StrategyModelRiskService modelRiskService = new StrategyModelRiskService();
    @Autowired
    private StrategyEntryRiskService entryRiskService = new StrategyEntryRiskService();

    public static final String INVALID_ENTRY_PRICE = StrategyEntryRiskService.INVALID_ENTRY_PRICE;
    public static final String INVALID_STOP_PRICE = StrategyEntryRiskService.INVALID_STOP_PRICE;
    public static final String INVALID_TAKE_PRICE = StrategyEntryRiskService.INVALID_TAKE_PRICE;

    /** Returns null when the signal can safely open a position, otherwise a stable rejection code. */
    public String validateOpenSignal(Signal signal, BacktestParam param) {
        return entryRiskService.prepareAndValidate(signal,
                param.fallbackStopLossPct.doubleValue(), param.fallbackTakeProfitPct.doubleValue());
    }

    public Position openPosition(Signal signal, int barIndex, Bar bar, BacktestParam param) {
        String rejection = validateOpenSignal(signal, param);
        if (rejection != null) {
            throw new IllegalArgumentException("invalid open signal: " + rejection);
        }
        Position position = new Position();
        position.side = signal.side;
        position.entryPrice = signal.price.doubleValue();
        position.entryTime = bar.getEndTime().toString();
        position.entryIndex = barIndex;
        position.stopPrice = entryRiskService.resolve(signal.stopPrice, signal.side, position.entryPrice,
                param.fallbackStopLossPct.doubleValue(), true);
        position.takePrice = entryRiskService.resolveTake(signal, param.fallbackTakeProfitPct.doubleValue());
        position.initialRiskPriceDistance=position.stopPrice==null?Double.NaN
                :Math.abs(position.entryPrice-position.stopPrice);
        position.highestSinceEntry=position.entryPrice;
        position.lowestSinceEntry=position.entryPrice;
        position.maxHoldBars = param.maxHoldBars == null ? 0 : param.maxHoldBars;
        return position;
    }

    public TradeRecord tryCloseByRisk(Position position, Bar currentBar, int currentIndex, double feeRatePct) {
        ModelRiskExitDecision decision = modelRiskService.evaluate(position, currentBar, currentIndex);
        return decision.exit ? closePosition(position, decision.exitPrice,
                currentBar.getEndTime().toString(), decision.reason, currentIndex, feeRatePct) : null;
    }

    public TradeRecord closePosition(Position position, double exitPrice, String exitTime, String exitReason,
                                     int exitIndex, double feeRatePct) {
        TradeRecord record = new TradeRecord();
        record.side = position.side == null ? "" : position.side.name();
        record.entryTime = position.entryTime;
        record.exitTime = exitTime;
        record.entryPrice = scale(position.entryPrice);
        record.exitPrice = scale(exitPrice);
        record.stopPrice = position.stopPrice == null ? null : scale(position.stopPrice);
        record.takePrice = position.takePrice == null ? null : scale(position.takePrice);
        record.holdBars = Math.max(1, exitIndex - position.entryIndex);
        record.exitReason = exitReason;
        record.strategyName = position.strategyName;
        record.regime = position.regime;
        record.returnPct = scale(calcReturnPct(position.side, position.entryPrice, exitPrice, feeRatePct));
        record.maxFavorableExcursionPct=scale(position.maxFavorableExcursionPct);
        record.maxAdverseExcursionPct=scale(position.maxAdverseExcursionPct);
        double captured=record.returnPct.doubleValue();
        // Capture ratio is meaningful only for a profitable exit. Dividing a loss
        // by a tiny positive MFE produced misleading values such as -12037%.
        record.profitCaptureRatio=position.maxFavorableExcursionPct>0&&captured>0
                ?scale(captured/position.maxFavorableExcursionPct):null;
        record.entryLifecyclePhase=position.entryLifecyclePhase;
        record.trendTriggerType=position.trendTriggerType;
        record.exitLifecyclePhase=position.exitLifecyclePhase;
        return record;
    }

    public boolean isOpposite(Side positionSide, Side signalSide) {
        return (positionSide == Side.BUY && signalSide == Side.SELL)
                || (positionSide == Side.SELL && signalSide == Side.BUY);
    }

    public double calcReturnPct(Side side, double entryPrice, double exitPrice, double feeRatePct) {
        double gross;
        if (side == Side.SELL) {
            gross = (entryPrice - exitPrice) / entryPrice;
        } else {
            gross = (exitPrice - entryPrice) / entryPrice;
        }
        double fee = feeRatePct / 100.0 * 2.0;
        return gross - fee;
    }

    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
