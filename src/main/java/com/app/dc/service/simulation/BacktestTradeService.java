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
        double entryFeeRatePct = param == null || param.entryMakerFeeRatePct == null
                ? 0.0d
                : param.entryMakerFeeRatePct.doubleValue();
        return openPosition(signal, barIndex, bar, param, currentEquity, entryFeeRatePct);
    }

    public Position openPosition(Signal signal, int barIndex, Bar bar, BacktestParam param,
                                 double currentEquity, double entryFeeRatePct) {
        Position position = new Position();
        position.side = signal.side;
        position.entryPrice = resolveEntryPrice(signal, bar);
        position.signalPrice = resolveSignalPrice(signal, position.entryPrice);
        position.entryTime = bar.getEndTime().toString();
        position.signalTime = bar.getEndTime().toString();
        position.entryIndex = barIndex;
        position.entryCapital = currentEquity;
        position.entryFeeRatePct = entryFeeRatePct;
        position.qty = position.entryPrice <= 0.0 ? 0.0 : currentEquity / position.entryPrice;
        position.stopPrice = signal.stopPrice == null || signal.stopPrice.compareTo(BigDecimal.ZERO) <= 0
                ? null
                : signal.stopPrice.doubleValue();
        position.takePrice = signal.takerPrice == null || signal.takerPrice.compareTo(BigDecimal.ZERO) <= 0
                ? null
                : signal.takerPrice.doubleValue();
        position.trailingFirstStepPct = positiveOrNull(signal.firstStep);
        position.trailingStepPct = positiveOrNull(signal.step);
        position.fallbackTriggerProfitPct = positiveOrNull(signal.underTriggerProfitPrice);
        position.fallbackTakeProfitPct = positiveOrNull(signal.underTakerProfitPrice);
        position.maxHoldBars = param.maxHoldBars == null ? 0 : param.maxHoldBars;
        return position;
    }

    public Position tryOpenLimitPosition(Signal signal, int barIndex, Bar bar,
                                         BacktestParam param, double currentEquity) {
        if (signal == null || bar == null || isMarketOrder(signal)) {
            return null;
        }
        double limitPrice = signal.price == null ? Double.NaN : signal.price.doubleValue();
        if (!isFinitePositive(limitPrice)) {
            return null;
        }
        double low = bar.getLowPrice().doubleValue();
        double high = bar.getHighPrice().doubleValue();
        if (!Double.isFinite(low) || !Double.isFinite(high)) {
            return null;
        }
        if (signal.side == Side.BUY && low > limitPrice) {
            return null;
        }
        if (signal.side == Side.SELL && high < limitPrice) {
            return null;
        }
        if (signal.side != Side.BUY && signal.side != Side.SELL) {
            return null;
        }
        double makerFeeRatePct = param == null || param.entryMakerFeeRatePct == null
                ? 0.0d
                : param.entryMakerFeeRatePct.doubleValue();
        return openPosition(signal, barIndex, bar, param, currentEquity, makerFeeRatePct);
    }

    public boolean isMarketOrder(Signal signal) {
        return signal != null
                && ("MARKET".equalsIgnoreCase(signal.orderType) || signal.type == 2);
    }

    private double resolveEntryPrice(Signal signal, Bar bar) {
        double signalPrice = signal == null || signal.price == null ? Double.NaN : signal.price.doubleValue();
        if (isFinitePositive(signalPrice)) {
            return signalPrice;
        }
        double closePrice = bar == null || bar.getClosePrice() == null ? Double.NaN : bar.getClosePrice().doubleValue();
        if (isFinitePositive(closePrice)) {
            return closePrice;
        }
        throw new IllegalArgumentException("entry price unavailable: signal/bar close are not positive finite values");
    }

    private double resolveSignalPrice(Signal signal, double fallbackPrice) {
        double signalPrice = signal == null || signal.price == null ? Double.NaN : signal.price.doubleValue();
        return Double.isFinite(signalPrice) ? signalPrice : fallbackPrice;
    }

    private boolean isFinitePositive(double value) {
        return Double.isFinite(value) && value > 0.0d;
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
        applyTrailingProtection(position, currentBar);
        return null;
    }

    private Double positiveOrNull(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return value.doubleValue();
    }

    private void applyTrailingProtection(Position position, Bar currentBar) {
        if (position == null || currentBar == null) {
            return;
        }
        Double nextStop = calculateTrailingStop(position, currentBar.getClosePrice().doubleValue());
        if (nextStop == null) {
            return;
        }
        if (position.stopPrice == null) {
            position.stopPrice = nextStop;
            return;
        }
        if (position.side == Side.BUY) {
            if (nextStop > position.stopPrice) {
                position.stopPrice = nextStop;
            }
        } else if (position.side == Side.SELL) {
            if (nextStop < position.stopPrice) {
                position.stopPrice = nextStop;
            }
        }
    }

    private Double calculateTrailingStop(Position position, double currentClose) {
        if (position.entryPrice <= 0.0d || position.side == null) {
            return null;
        }
        double profitPct;
        if (position.side == Side.SELL) {
            profitPct = ((position.entryPrice - currentClose) / position.entryPrice) * 100.0d;
        } else {
            profitPct = ((currentClose - position.entryPrice) / position.entryPrice) * 100.0d;
        }
        if (!Double.isFinite(profitPct) || profitPct <= 0.0d) {
            return null;
        }
        Double fallbackPct = position.fallbackTakeProfitPct;
        if (position.trailingFirstStepPct != null && position.trailingStepPct != null
                && profitPct > position.trailingFirstStepPct) {
            int steps = (int) Math.floor((profitPct - position.trailingFirstStepPct) / position.trailingStepPct);
            double stopPct = position.trailingFirstStepPct
                    + position.trailingStepPct * (((double) steps) - 1.0d);
            return buildProtectedStop(position, stopPct);
        }
        if (position.fallbackTriggerProfitPct != null && fallbackPct != null
                && profitPct >= position.fallbackTriggerProfitPct) {
            return buildProtectedStop(position, fallbackPct);
        }
        return null;
    }

    private Double buildProtectedStop(Position position, double stopPct) {
        if (position.side == Side.BUY) {
            return position.entryPrice * (1.0d + stopPct / 100.0d);
        }
        if (position.side == Side.SELL) {
            return position.entryPrice * (1.0d - stopPct / 100.0d);
        }
        return null;
    }

    public TradeRecord closePosition(Position position, double exitPrice, String exitTime, String exitReason,
                                     int exitIndex, double entryMakerFeeRatePct, double exitTakerFeeRatePct) {
        double actualEntryFeeRatePct = position.entryFeeRatePct == null
                ? entryMakerFeeRatePct
                : position.entryFeeRatePct.doubleValue();
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
        record.entryFeeRatePct = scale(actualEntryFeeRatePct);
        record.exitFeeRatePct = scale(exitTakerFeeRatePct);
        record.entryFee = scale(position.entryCapital * actualEntryFeeRatePct / 100.0d);
        record.exitFee = scale(position.entryCapital * exitTakerFeeRatePct / 100.0d);
        record.totalFee = scale(record.entryFee.doubleValue() + record.exitFee.doubleValue());
        record.grossReturnPct = scale(calcGrossReturnPct(position.side, position.entryPrice, exitPrice));
        record.returnPct = scale(calcReturnPct(position.side, position.entryPrice, exitPrice,
                actualEntryFeeRatePct, exitTakerFeeRatePct));
        return record;
    }

    public boolean isOpposite(Side positionSide, Side signalSide) {
        return (positionSide == Side.BUY && signalSide == Side.SELL)
                || (positionSide == Side.SELL && signalSide == Side.BUY);
    }

    public double calcGrossReturnPct(Side side, double entryPrice, double exitPrice) {
        if (!isFinitePositive(entryPrice) || !Double.isFinite(exitPrice)) {
            throw new IllegalArgumentException("invalid trade prices, entryPrice=" + entryPrice + ", exitPrice=" + exitPrice);
        }
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
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite trade value: " + value);
        }
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
