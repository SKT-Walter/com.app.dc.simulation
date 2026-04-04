package com.app.dc.service.simulation;

import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.EquityContext;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BacktestMetricService {

    public EquityContext initEquityContext(double initialCapital) {
        EquityContext context = new EquityContext();
        context.equity = initialCapital;
        context.peakEquity = initialCapital;
        return context;
    }

    public void applyTrade(BacktestResult result, TradeRecord tradeRecord, EquityContext context) {
        if (tradeRecord.tradeNo == null) {
            tradeRecord.tradeNo = result.tradeList.size() + 1;
        }
        if (tradeRecord.symbol == null || tradeRecord.symbol.trim().isEmpty()) {
            tradeRecord.symbol = result.symbol;
        }
        if (tradeRecord.text == null || tradeRecord.text.trim().isEmpty()) {
            tradeRecord.text = result.text;
        }
        result.tradeList.add(tradeRecord);
        boolean stopExit = tradeRecord.exitReason != null && tradeRecord.exitReason.startsWith("stop");
        boolean takeExit = tradeRecord.exitReason != null && tradeRecord.exitReason.startsWith("take");
        if (stopExit) {
            result.stopExitCount++;
        } else if (takeExit) {
            result.takeExitCount++;
        }

        if (tradeRecord.returnPct.doubleValue() > 0) {
            result.winCount++;
            context.totalPositiveReturnPct += tradeRecord.returnPct.doubleValue();
            if (stopExit) {
                result.stopExitWinCount++;
            } else if (takeExit) {
                result.takeExitWinCount++;
            }
        } else if (tradeRecord.returnPct.doubleValue() < 0) {
            result.lossCount++;
            context.totalNegativeReturnPct += Math.abs(tradeRecord.returnPct.doubleValue());
            if (stopExit) {
                result.stopExitLossCount++;
            } else if (takeExit) {
                result.takeExitLossCount++;
            }
        } else {
            result.flatCount++;
        }

        double nextEquity = context.equity * (1.0 + tradeRecord.returnPct.doubleValue());
        tradeRecord.pnl = scale(nextEquity - context.equity);
        tradeRecord.equityAfter = scale(nextEquity);
        tradeRecord.cumulativePnl = result.initialCapital == null
                ? BigDecimal.ZERO
                : scale(nextEquity - result.initialCapital.doubleValue());
        context.equity = nextEquity;
        context.peakEquity = Math.max(context.peakEquity, context.equity);
        context.totalHoldBars += tradeRecord.holdBars == null ? 0 : tradeRecord.holdBars;
        result.entryFeeTotal = add(result.entryFeeTotal, tradeRecord.entryFee);
        result.exitFeeTotal = add(result.exitFeeTotal, tradeRecord.exitFee);
        result.totalFee = add(result.totalFee, tradeRecord.totalFee);
        double currentDrawdown = calcDrawdownPct(context.peakEquity, context.equity);
        result.maxDrawdownPct = scale(Math.max(result.maxDrawdownPct.doubleValue(), currentDrawdown));
        if (result.equityCurve != null) {
            BacktestModels.EquityPoint point = new BacktestModels.EquityPoint();
            point.time = tradeRecord.exitTime;
            point.equity = scale(nextEquity);
            point.deltaPnl = tradeRecord.pnl;
            point.cumulativePnl = tradeRecord.cumulativePnl;
            result.equityCurve.add(point);
        }
    }

    public void finishResult(BacktestResult result, EquityContext context) {
        result.finalCapital = scale(context.equity);
        double initialCapital = result.initialCapital == null ? 0.0 : result.initialCapital.doubleValue();
        result.totalPnl = scale(context.equity - initialCapital);
        result.tradeCount = result.tradeList.size();
        result.winRate = result.tradeCount == 0 ? BigDecimal.ZERO
                : scale((double) result.winCount / result.tradeCount);
        result.totalReturnPct = initialCapital == 0.0 ? BigDecimal.ZERO
                : scale((context.equity - initialCapital) / initialCapital);
        result.avgReturnPct = result.tradeCount == 0 ? BigDecimal.ZERO
                : scale(result.tradeList.stream().mapToDouble(t -> t.returnPct.doubleValue()).average().orElse(0.0));
        result.avgHoldBars = result.tradeCount == 0 ? BigDecimal.ZERO
                : scale((double) context.totalHoldBars / result.tradeCount);
        result.profitFactor = context.totalNegativeReturnPct == 0.0 ? scale(999.0)
                : scale(context.totalPositiveReturnPct / context.totalNegativeReturnPct);
    }

    public double calcDrawdownPct(double peakEquity, double currentEquity) {
        if (peakEquity <= 0) {
            return 0.0;
        }
        return (peakEquity - currentEquity) / peakEquity;
    }

    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal add(BigDecimal left, BigDecimal right) {
        return (left == null ? BigDecimal.ZERO : left).add(right == null ? BigDecimal.ZERO : right)
                .setScale(6, RoundingMode.HALF_UP);
    }
}
