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

    public Position openPosition(Signal signal, int barIndex, Bar bar, BacktestParam param, double currentEquity) {
        Position position = new Position();
        position.side = signal.side;
        position.entryPrice = signal.price.doubleValue();
        position.signalPrice = signal.price == null ? position.entryPrice : signal.price.doubleValue();
        position.entryTime = bar.getEndTime().toString();
        position.signalTime = bar.getEndTime().toString();
        position.entryIndex = barIndex;
        position.entryCapital = currentEquity;
        position.qty = position.entryPrice == 0.0 ? 0.0 : currentEquity / position.entryPrice;
        position.stopPrice = signal.stopPrice == null || signal.stopPrice.compareTo(BigDecimal.ZERO) <= 0
                ? null
                : signal.stopPrice.doubleValue();
        position.takePrice = signal.takerPrice == null || signal.takerPrice.compareTo(BigDecimal.ZERO) <= 0
                ? null
                : signal.takerPrice.doubleValue();
        position.maxHoldBars = param.maxHoldBars == null ? 0 : param.maxHoldBars;
        return position;
    }

    public TradeRecord tryCloseByRisk(Position position, Bar currentBar, int currentIndex,
                                      double entryMakerFeeRatePct, double exitTakerFeeRatePct) {
        double high = currentBar.getHighPrice().doubleValue();
        double low = currentBar.getLowPrice().doubleValue();
        position.currentHoldBars++;

        if (position.side == Side.BUY) {
            boolean hitStop = position.stopPrice != null && low <= position.stopPrice;
            boolean hitTake = position.takePrice != null && high >= position.takePrice;
            if (hitStop && hitTake) {
                return closePosition(position, position.stopPrice, currentBar.getEndTime().toString(),
                        "stop_first_same_bar", currentIndex, entryMakerFeeRatePct, exitTakerFeeRatePct);
            }
            if (hitStop) {
                return closePosition(position, position.stopPrice, currentBar.getEndTime().toString(),
                        "stop_loss", currentIndex, entryMakerFeeRatePct, exitTakerFeeRatePct);
            }
            if (hitTake) {
                return closePosition(position, position.takePrice, currentBar.getEndTime().toString(),
                        "take_profit", currentIndex, entryMakerFeeRatePct, exitTakerFeeRatePct);
            }
        } else if (position.side == Side.SELL) {
            boolean hitStop = position.stopPrice != null && high >= position.stopPrice;
            boolean hitTake = position.takePrice != null && low <= position.takePrice;
            if (hitStop && hitTake) {
                return closePosition(position, position.stopPrice, currentBar.getEndTime().toString(),
                        "stop_first_same_bar", currentIndex, entryMakerFeeRatePct, exitTakerFeeRatePct);
            }
            if (hitStop) {
                return closePosition(position, position.stopPrice, currentBar.getEndTime().toString(),
                        "stop_loss", currentIndex, entryMakerFeeRatePct, exitTakerFeeRatePct);
            }
            if (hitTake) {
                return closePosition(position, position.takePrice, currentBar.getEndTime().toString(),
                        "take_profit", currentIndex, entryMakerFeeRatePct, exitTakerFeeRatePct);
            }
        }

        if (position.maxHoldBars > 0 && position.currentHoldBars >= position.maxHoldBars) {
            return closePosition(position, currentBar.getClosePrice().doubleValue(), currentBar.getEndTime().toString(),
                    "max_hold_bars", currentIndex, entryMakerFeeRatePct, exitTakerFeeRatePct);
        }
        return null;
    }

    public TradeRecord closePosition(Position position, double exitPrice, String exitTime, String exitReason,
                                     int exitIndex, double entryMakerFeeRatePct, double exitTakerFeeRatePct) {
        TradeRecord record = new TradeRecord();
        record.side = position.side == null ? "" : position.side.name();
        record.signalTime = position.signalTime;
        record.signalPrice = scale(position.signalPrice);
        record.entryTime = position.entryTime;
        record.exitTime = exitTime;
        record.entryPrice = scale(position.entryPrice);
        record.exitPrice = scale(exitPrice);
        record.stopPrice = position.stopPrice == null ? null : scale(position.stopPrice);
        record.takePrice = position.takePrice == null ? null : scale(position.takePrice);
        record.qty = scale(position.qty);
        record.holdBars = Math.max(1, exitIndex - position.entryIndex);
        record.entryReason = "signal_entry";
        record.exitReason = exitReason;
        record.entryFeeRatePct = scale(entryMakerFeeRatePct);
        record.exitFeeRatePct = scale(exitTakerFeeRatePct);
        record.entryFee = scale(position.entryCapital * entryMakerFeeRatePct / 100.0d);
        record.exitFee = scale(position.entryCapital * exitTakerFeeRatePct / 100.0d);
        record.totalFee = scale(record.entryFee.doubleValue() + record.exitFee.doubleValue());
        record.grossReturnPct = scale(calcGrossReturnPct(position.side, position.entryPrice, exitPrice));
        record.returnPct = scale(calcReturnPct(position.side, position.entryPrice, exitPrice,
                entryMakerFeeRatePct, exitTakerFeeRatePct));
        return record;
    }

    public boolean isOpposite(Side positionSide, Side signalSide) {
        return (positionSide == Side.BUY && signalSide == Side.SELL)
                || (positionSide == Side.SELL && signalSide == Side.BUY);
    }

    public double calcGrossReturnPct(Side side, double entryPrice, double exitPrice) {
        if (side == Side.SELL) {
            return (entryPrice - exitPrice) / entryPrice;
        }
        return (exitPrice - entryPrice) / entryPrice;
    }

    public double calcReturnPct(Side side, double entryPrice, double exitPrice,
                                double entryMakerFeeRatePct, double exitTakerFeeRatePct) {
        double gross = calcGrossReturnPct(side, entryPrice, exitPrice);
        double fee = (entryMakerFeeRatePct + exitTakerFeeRatePct) / 100.0d;
        return gross - fee;
    }

    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
