package com.app.dc.service.simulation.runtime;

import com.app.dc.service.simulation.BacktestModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * Applies conservative execution costs on top of the maker/taker fees already
 * included by the backtest engine. The result is used by qualification gates;
 * raw PnL remains available for diagnostics and comparison.
 */
@Service
public class ExecutionStressService {

    private static final BigDecimal BPS_DIVISOR = BigDecimal.valueOf(10000L);

    @Value("${strategy.backtest.execution-stress.enabled:true}")
    private boolean enabled = true;

    @Value("${strategy.backtest.execution-stress.entry-slippage-bps:2}")
    private BigDecimal entrySlippageBps = BigDecimal.valueOf(2L);

    @Value("${strategy.backtest.execution-stress.exit-slippage-bps:3}")
    private BigDecimal exitSlippageBps = BigDecimal.valueOf(3L);

    @Value("${strategy.backtest.execution-stress.spread-bps:2}")
    private BigDecimal spreadBps = BigDecimal.valueOf(2L);

    @Value("${strategy.backtest.execution-stress.latency-impact-bps:1}")
    private BigDecimal latencyImpactBps = BigDecimal.ONE;

    public BigDecimal adjustedPnl(BacktestModels.BacktestResult result) {
        BigDecimal rawPnl = result == null || result.totalPnl == null
                ? BigDecimal.ZERO
                : result.totalPnl;
        if (!enabled || result == null) {
            return scale(rawPnl);
        }
        return scale(rawPnl.subtract(additionalCost(result.tradeList)));
    }

    BigDecimal additionalCost(List<BacktestModels.TradeRecord> trades) {
        if (!enabled) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal halfSpread = positive(spreadBps).divide(BigDecimal.valueOf(2L), 10, RoundingMode.HALF_UP);
        BigDecimal entryCostBps = positive(entrySlippageBps).add(halfSpread).add(positive(latencyImpactBps));
        BigDecimal exitCostBps = positive(exitSlippageBps).add(halfSpread).add(positive(latencyImpactBps));
        for (BacktestModels.TradeRecord trade : trades == null
                ? Collections.<BacktestModels.TradeRecord>emptyList()
                : trades) {
            if (trade == null) {
                continue;
            }
            BigDecimal qty = absolute(trade.qty);
            BigDecimal entryNotional = qty.multiply(absolute(trade.entryPrice));
            BigDecimal exitNotional = qty.multiply(absolute(trade.exitPrice));
            total = total.add(entryNotional.multiply(entryCostBps).divide(BPS_DIVISOR, 10, RoundingMode.HALF_UP));
            total = total.add(exitNotional.multiply(exitCostBps).divide(BPS_DIVISOR, 10, RoundingMode.HALF_UP));
        }
        return scale(total);
    }

    void configureForTest(boolean enabled,
                          BigDecimal entrySlippageBps,
                          BigDecimal exitSlippageBps,
                          BigDecimal spreadBps,
                          BigDecimal latencyImpactBps) {
        this.enabled = enabled;
        this.entrySlippageBps = entrySlippageBps;
        this.exitSlippageBps = exitSlippageBps;
        this.spreadBps = spreadBps;
        this.latencyImpactBps = latencyImpactBps;
    }

    private BigDecimal positive(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal absolute(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(8, RoundingMode.HALF_UP);
    }
}
