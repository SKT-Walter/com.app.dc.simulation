package com.app.dc.service.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BacktestTradeService {

    public static final String INVALID_ENTRY_PRICE = "INVALID_ENTRY_PRICE";
    public static final String INVALID_STOP_PRICE = "INVALID_STOP_PRICE";
    public static final String INVALID_TAKE_PRICE = "INVALID_TAKE_PRICE";

    /** Returns null when the signal can safely open a position, otherwise a stable rejection code. */
    public String validateOpenSignal(Signal signal, BacktestParam param) {
        if (signal == null || (signal.side != Side.BUY && signal.side != Side.SELL) || signal.price == null
                || signal.price.compareTo(BigDecimal.ZERO) <= 0) {
            return INVALID_ENTRY_PRICE;
        }
        if (signal.stopPrice != null && signal.stopPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return INVALID_STOP_PRICE;
        }
        if (signal.takerPrice != null && signal.takerPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return INVALID_TAKE_PRICE;
        }

        double entryPrice = signal.price.doubleValue();
        Double resolvedStop = resolveRiskPrice(signal.stopPrice, signal.side, entryPrice,
                param.fallbackStopLossPct.doubleValue(), true);
        Double resolvedTake = resolveRiskPrice(signal.takerPrice, signal.side, entryPrice,
                fallbackTakeProfitPct(signal,param), false);

        if (!Double.isFinite(entryPrice) || entryPrice <= 0.0) {
            return INVALID_ENTRY_PRICE;
        }
        if (resolvedStop != null && (!Double.isFinite(resolvedStop)
                || (signal.side == Side.BUY ? resolvedStop >= entryPrice : resolvedStop <= entryPrice))) {
            return INVALID_STOP_PRICE;
        }
        if (resolvedTake != null && (!Double.isFinite(resolvedTake)
                || (signal.side == Side.BUY ? resolvedTake <= entryPrice : resolvedTake >= entryPrice))) {
            return INVALID_TAKE_PRICE;
        }
        return null;
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
        position.stopPrice = resolveRiskPrice(signal.stopPrice, signal.side, position.entryPrice,
                param.fallbackStopLossPct.doubleValue(), true);
        position.takePrice = resolveRiskPrice(signal.takerPrice, signal.side, position.entryPrice,
                fallbackTakeProfitPct(signal,param), false);
        position.initialRiskPriceDistance=position.stopPrice==null?Double.NaN
                :Math.abs(position.entryPrice-position.stopPrice);
        position.highestSinceEntry=position.entryPrice;
        position.lowestSinceEntry=position.entryPrice;
        position.maxHoldBars = param.maxHoldBars == null ? 0 : param.maxHoldBars;
        return position;
    }

    public TradeRecord tryCloseByRisk(Position position, Bar currentBar, int currentIndex, double feeRatePct) {
        double high = currentBar.getHighPrice().doubleValue();
        double low = currentBar.getLowPrice().doubleValue();
        position.highestSinceEntry=Double.isFinite(position.highestSinceEntry)
                ?Math.max(position.highestSinceEntry,high):high;
        position.lowestSinceEntry=Double.isFinite(position.lowestSinceEntry)
                ?Math.min(position.lowestSinceEntry,low):low;
        double favorable=position.side==Side.BUY?(high-position.entryPrice)/position.entryPrice
                :(position.entryPrice-low)/position.entryPrice;
        double adverse=position.side==Side.BUY?(position.entryPrice-low)/position.entryPrice
                :(high-position.entryPrice)/position.entryPrice;
        position.maxFavorableExcursionPct=Math.max(position.maxFavorableExcursionPct,favorable);
        position.maxAdverseExcursionPct=Math.max(position.maxAdverseExcursionPct,adverse);
        position.currentHoldBars++;

        if (position.side == Side.BUY) {
            boolean hitStop = position.stopPrice != null && low <= position.stopPrice;
            boolean hitTake = position.takePrice != null && high >= position.takePrice;
            if (hitStop && hitTake) {
                return closePosition(position, position.stopPrice, currentBar.getEndTime().toString(),
                        "stop_first_same_bar", currentIndex, feeRatePct);
            }
            if (hitStop) {
                return closePosition(position, position.stopPrice, currentBar.getEndTime().toString(),
                        position.stopExitReason==null?"stop_loss":position.stopExitReason, currentIndex, feeRatePct);
            }
            if (hitTake) {
                return closePosition(position, position.takePrice, currentBar.getEndTime().toString(),
                        "take_profit", currentIndex, feeRatePct);
            }
        } else if (position.side == Side.SELL) {
            boolean hitStop = position.stopPrice != null && high >= position.stopPrice;
            boolean hitTake = position.takePrice != null && low <= position.takePrice;
            if (hitStop && hitTake) {
                return closePosition(position, position.stopPrice, currentBar.getEndTime().toString(),
                        "stop_first_same_bar", currentIndex, feeRatePct);
            }
            if (hitStop) {
                return closePosition(position, position.stopPrice, currentBar.getEndTime().toString(),
                        position.stopExitReason==null?"stop_loss":position.stopExitReason, currentIndex, feeRatePct);
            }
            if (hitTake) {
                return closePosition(position, position.takePrice, currentBar.getEndTime().toString(),
                        "take_profit", currentIndex, feeRatePct);
            }
        }

        if (position.maxHoldBars > 0 && position.currentHoldBars >= position.maxHoldBars) {
            return closePosition(position, currentBar.getClosePrice().doubleValue(), currentBar.getEndTime().toString(),
                    "max_hold_bars", currentIndex, feeRatePct);
        }
        return null;
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
        record.profitCaptureRatio=scale(position.maxFavorableExcursionPct>0
                ?captured/position.maxFavorableExcursionPct:0);
        record.entryLifecyclePhase=position.entryLifecyclePhase;
        record.trendTriggerType=position.trendTriggerType;
        record.exitLifecyclePhase=position.exitLifecyclePhase;
        return record;
    }

    public boolean isOpposite(Side positionSide, Side signalSide) {
        return (positionSide == Side.BUY && signalSide == Side.SELL)
                || (positionSide == Side.SELL && signalSide == Side.BUY);
    }

    public Double resolveRiskPrice(BigDecimal strategyPrice, Side side, double entryPrice,
                                   double fallbackPct, boolean stopLoss) {
        if (strategyPrice != null && strategyPrice.compareTo(BigDecimal.ZERO) > 0) {
            return strategyPrice.doubleValue();
        }
        if (fallbackPct <= 0.0) {
            return null;
        }

        double ratio = fallbackPct / 100.0;
        if (side == Side.BUY) {
            return stopLoss ? entryPrice * (1.0 - ratio) : entryPrice * (1.0 + ratio);
        }
        return stopLoss ? entryPrice * (1.0 + ratio) : entryPrice * (1.0 - ratio);
    }

    private double fallbackTakeProfitPct(Signal signal,BacktestParam param){
        if(signal!=null&&signal.remark!=null
                &&(signal.remark.startsWith("CONTINUATION_BREAKOUT")
                ||signal.remark.startsWith("NO_FIXED_TAKE_PROFIT")))return 0;
        return param.fallbackTakeProfitPct.doubleValue();
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
