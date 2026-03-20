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

    public Position openPosition(Signal signal, int barIndex, Bar bar, BacktestParam param) {
        Position position = new Position();
        position.side = signal.side;
        position.entryPrice = signal.price.doubleValue();
        position.entryTime = bar.getEndTime().toString();
        position.entryIndex = barIndex;
        position.stopPrice = resolveRiskPrice(signal.stopPrice, signal.side, position.entryPrice,
                param.fallbackStopLossPct.doubleValue(), true);
        position.takePrice = resolveRiskPrice(signal.takerPrice, signal.side, position.entryPrice,
                param.fallbackTakeProfitPct.doubleValue(), false);
        position.maxHoldBars = param.maxHoldBars == null ? 0 : param.maxHoldBars;
        return position;
    }

    public TradeRecord tryCloseByRisk(Position position, Bar currentBar, int currentIndex, double feeRatePct) {
        double high = currentBar.getHighPrice().doubleValue();
        double low = currentBar.getLowPrice().doubleValue();
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
                        "stop_loss", currentIndex, feeRatePct);
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
                        "stop_loss", currentIndex, feeRatePct);
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
        record.returnPct = scale(calcReturnPct(position.side, position.entryPrice, exitPrice, feeRatePct));
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
