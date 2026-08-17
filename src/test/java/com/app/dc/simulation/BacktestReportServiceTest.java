package com.app.dc.simulation;

import com.app.dc.strategy.core.StrategyRuntimeModels.StrategyRunResponse;
import com.app.dc.strategy.core.StrategyRuntimeModels.StrategyRunResult;
import com.app.dc.strategy.core.StrategyRuntimeModels.TradeRecord;
import com.app.dc.service.simulation.BacktestReportService;
import com.app.dc.strategy.core.deterministic.StrategyRoutingDecision;
import org.junit.Assert;
import org.junit.Test;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BacktestReportServiceTest {
    @Test
    public void reportUsesChineseLabelsAndTradeDescriptions() throws Exception {
        TradeRecord trade = new TradeRecord();
        trade.side = "BUY";
        trade.exitReason = "take_profit";
        trade.entryPrice = new BigDecimal("100");
        trade.exitPrice = new BigDecimal("110");
        trade.holdBars = 3;
        trade.returnPct = new BigDecimal("0.10");
        trade.pnl = new BigDecimal("1000");
        StrategyRunResult result = new StrategyRunResult();
        result.strategyName = "binanceTrend";
        result.symbol = "ETHUSDT";
        result.initialCapital = new BigDecimal("10000");
        result.finalCapital = new BigDecimal("11000");
        result.winRate = new BigDecimal("0.602996");
        result.totalReturnPct = new BigDecimal("0.849485");
        result.maxDrawdownPct = new BigDecimal("0.001983");
        result.actualBeginTime = "2026-01-21T00:00+08:00[Asia/Shanghai]";
        result.actualEndTime = "2026-01-30T23:45+08:00[Asia/Shanghai]";
        result.totalBars = 960;
        result.tradeList = Arrays.asList(trade);
        StrategyRunResponse response = new StrategyRunResponse();
        response.strategyName = "binanceTrend";
        response.symbol = "ETHUSDT";
        response.symbols = Arrays.asList("ETHUSDT");
        response.text = "15M";
        response.beginDate = "2026-01-01";
        response.endDate = "2026-01-31";
        response.results = Arrays.asList(result);
        StrategyRoutingDecision decision = new StrategyRoutingDecision();
        decision.barTime = 1L;
        decision.regime = "UP|NORMAL";
        decision.regimeConfidence = 0.825;
        decision.strategyName = "binanceTrend";
        decision.activeScore = 87.5;
        decision.reason = "ACTIVATED";
        response.routingDecisions = Arrays.asList(decision);
        response.routingStats = new com.app.dc.strategy.core.StrategyRuntimeModels.RoutingStats();
        response.routingStats.routingDecisionCount = 960;
        response.routingStats.routingReasonCounts.put("ACTIVATED", 1);
        BacktestReportService service = new BacktestReportService();
        Method method = BacktestReportService.class.getDeclaredMethod("buildMarkdown", StrategyRunResponse.class);
        method.setAccessible(true);
        String markdown = (String) method.invoke(service, response);
        Assert.assertTrue(markdown.contains("# 策略回测报告"));
        Assert.assertTrue(markdown.contains("## 回测汇总"));
        Assert.assertTrue(markdown.contains("买入/做多"));
        Assert.assertTrue(markdown.contains("触发止盈"));
        Assert.assertTrue(markdown.contains("实际行情覆盖"));
        Assert.assertTrue(markdown.contains("84.9485%"));
        Assert.assertTrue(markdown.contains("60.2996%"));
        Assert.assertTrue(markdown.contains("0.1983%"));
        Assert.assertTrue(markdown.contains("10.0000%"));
        Assert.assertTrue(markdown.contains("2026-01-21T00:00"));
        Assert.assertTrue(markdown.contains("82.5000%"));
        Assert.assertTrue(markdown.contains("87.50/100"));
        Assert.assertTrue(markdown.contains("确定性路由执行统计"));
        Assert.assertTrue(markdown.contains("路由决策次数：960"));
        Assert.assertFalse(markdown.contains("品种评分档案"));
        Assert.assertFalse(markdown.contains("评分—信号一致性审计"));
        Assert.assertFalse(markdown.contains("饥饿释放次数"));
    }

    @Test
    public void reportPrintsEveryTradeWithoutTruncation() throws Exception {
        List<TradeRecord> trades = new ArrayList<TradeRecord>();
        for (int i = 1; i <= 227; i++) {
            TradeRecord trade = new TradeRecord();
            trade.strategyName = "trade-" + i;
            trade.side = "BUY";
            trade.entryPrice = new BigDecimal("100");
            trade.exitPrice = new BigDecimal("101");
            trade.returnPct = new BigDecimal("0.01");
            trade.pnl = BigDecimal.ONE;
            trades.add(trade);
        }
        StrategyRunResult result = new StrategyRunResult();
        result.strategyName = "deterministic";
        result.symbol = "ETHUSDT";
        result.tradeList = trades;
        StrategyRunResponse response = new StrategyRunResponse();
        response.results = Arrays.asList(result);

        BacktestReportService service = new BacktestReportService();
        Method method = BacktestReportService.class.getDeclaredMethod("buildMarkdown", StrategyRunResponse.class);
        method.setAccessible(true);
        String markdown = (String) method.invoke(service, response);

        Assert.assertTrue(markdown.contains("trade-1"));
        Assert.assertTrue(markdown.contains("trade-120"));
        Assert.assertTrue(markdown.contains("trade-227"));
        Assert.assertFalse(markdown.contains("交易明细已截断"));
        Assert.assertFalse(markdown.contains("条未显示"));
    }

}
