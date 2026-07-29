package com.app.dc.service.simulation;

import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.EquityContext;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BacktestMetricService {

    public EquityContext initEquityContext(double initialCapital) {
        return initEquityContext(initialCapital, initialCapital);
    }

    public EquityContext initEquityContext(double initialCapital, double tradeNotional) {
        EquityContext context = new EquityContext();
        context.equity = initialCapital;
        context.peakEquity = initialCapital;
        context.tradeNotional = tradeNotional;
        return context;
    }

    public void applyTrade(BacktestResult result, TradeRecord tradeRecord, EquityContext context) {
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

        double pnl = context.tradeNotional * tradeRecord.returnPct.doubleValue();
        double nextEquity = context.equity + pnl;
        tradeRecord.pnl = scale(pnl);
        context.equity = nextEquity;
        context.peakEquity = Math.max(context.peakEquity, context.equity);
        context.totalHoldBars += tradeRecord.holdBars == null ? 0 : tradeRecord.holdBars;
        BigDecimal currentDrawdown = scale(calcDrawdownPct(context.peakEquity, context.equity));
        if (result.maxDrawdownPct == null
                || currentDrawdown.compareTo(result.maxDrawdownPct) > 0) {
            result.maxDrawdownPct = currentDrawdown;
        }
    }

    public void finishResult(BacktestResult result, EquityContext context) {
        result.finalCapital = scale(context.equity);
        result.tradeCount = result.tradeList.size();
        result.winRate = result.tradeCount == 0 ? BigDecimal.ZERO
                : scale((double) result.winCount / result.tradeCount);
        double initialCapital = result.initialCapital == null ? 0.0 : result.initialCapital.doubleValue();
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
}
