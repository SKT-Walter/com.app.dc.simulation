package com.app.dc.service.simulation;

import com.app.dc.service.simulation.BacktestModels.BacktestResponse;
import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.deterministic.DeterministicScoreCard;
import com.app.dc.service.simulation.deterministic.StrategyRoutingDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringJoiner;

@Service
@Slf4j
public class BacktestReportService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Value("${binanceBacktestReportEnabled:true}")
    private boolean reportEnabled;

    @Value("${binanceBacktestReportDir:./src/docs}")
    private String reportDir;


    public String writeReport(BacktestResponse response) {
        if (!reportEnabled || response == null) {
            return "";
        }
        try {
            Path dir = Paths.get(reportDir);
            Files.createDirectories(dir);
            String fileName = buildFileName(response);
            Path filePath = dir.resolve(fileName);
            Files.write(filePath, buildMarkdown(response).getBytes(StandardCharsets.UTF_8));
            return filePath.toString().replace("\\", "/");
        } catch (Exception e) {
            log.error("BacktestReportService writeReport error", e);
            return "";
        }
    }

    private String buildFileName(BacktestResponse response) {
        String strategy = safeFilePart(response.strategyName);
        String symbol = safeFilePart(response.symbol);
        String text = safeFilePart(response.text);
        String time = LocalDateTime.now().format(FILE_TIME);
        return strategy + "_" + symbol + "_" + text + "_" + time + ".md";
    }

    private String buildMarkdown(BacktestResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 策略回测报告").append("\n\n");
        sb.append("- 策略：").append(s(response.strategyName)).append("\n");
        sb.append("- 品种：").append(s(response.symbol)).append("\n");
        if (response.symbols != null && !response.symbols.isEmpty()) {
            sb.append("- 品种列表：").append(joinSymbols(response.symbols)).append("\n");
        }
        sb.append("- K线周期：").append(s(response.text)).append("\n");
        sb.append("- 请求开始日期：").append(s(response.beginDate)).append("\n");
        sb.append("- 请求结束日期：").append(s(response.endDate)).append("\n");
        sb.append("- 报告生成时间：").append(LocalDateTime.now()).append("\n\n");

        List<BacktestResult> results = response.results == null
                ? Collections.<BacktestResult>emptyList()
                : response.results;
        if (!results.isEmpty()) {
            sb.append("- 资金模型：固定名义本金，不复利\n");
            sb.append("- 每笔下单资金：").append(money(results.get(0).tradeNotional)).append("\n\n");
        }
        List<BacktestResult> sortedResults = new ArrayList<>(results);
        sortedResults.sort(Comparator.comparing(this::safeTotalPnl).reversed()
                .thenComparing(result -> s(result.strategyName))
                .thenComparing(result -> s(result.symbol)));
        List<BacktestResult> symbolSortedResults = new ArrayList<>(results);
        symbolSortedResults.sort(Comparator.comparing((BacktestResult result) -> s(result.symbol))
                .thenComparing(this::safeTotalPnl, Comparator.reverseOrder())
                .thenComparing(result -> s(result.strategyName)));

        sb.append("## 实际行情覆盖").append("\n\n");
        Map<String, BacktestResult> coverageBySymbol = new LinkedHashMap<String, BacktestResult>();
        for (BacktestResult result : results) {
            BacktestResult previous = coverageBySymbol.get(result.symbol);
            if (previous == null || nzInt(result.totalBars) > nzInt(previous.totalBars)) {
                coverageBySymbol.put(result.symbol, result);
            }
        }
        List<List<String>> coverageRows = new ArrayList<List<String>>();
        for (BacktestResult result : coverageBySymbol.values()) {
            List<String> row = new ArrayList<String>();
            row.add(s(result.symbol));
            row.add(s(result.actualBeginTime));
            row.add(s(result.actualEndTime));
            row.add(i(result.totalBars));
            coverageRows.add(row);
        }
        appendAlignedTable(sb, java.util.Arrays.asList("品种", "首根K线时间", "末根K线时间", "K线数"), coverageRows);
        sb.append("\n");

        sb.append("## 回测汇总").append("\n\n");
        List<String> summaryHeaders = new ArrayList<>();
        summaryHeaders.add("策略"); summaryHeaders.add("品种"); summaryHeaders.add("K线数"); summaryHeaders.add("交易数");
        summaryHeaders.add("盈利数"); summaryHeaders.add("亏损数"); summaryHeaders.add("持平数");
        summaryHeaders.add("止损退出次数"); summaryHeaders.add("止损退出且盈利"); summaryHeaders.add("止损退出且亏损");
        summaryHeaders.add("止盈退出次数"); summaryHeaders.add("止盈退出且盈利"); summaryHeaders.add("止盈退出且亏损");
        summaryHeaders.add("胜率"); summaryHeaders.add("总收益率"); summaryHeaders.add("总盈亏");
        summaryHeaders.add("最大回撤"); summaryHeaders.add("最终资金");

        List<List<String>> summaryRows = new ArrayList<>();
        for (BacktestResult r : sortedResults) {
            List<String> row = new ArrayList<>();
            row.add(s(r.strategyName));
            row.add(s(r.symbol));
            row.add(i(r.totalBars));
            row.add(i(r.tradeCount));
            row.add(i(r.winCount));
            row.add(i(r.lossCount));
            row.add(i(r.flatCount));
            row.add(i(r.stopExitCount));
            row.add(i(r.stopExitWinCount));
            row.add(i(r.stopExitLossCount));
            row.add(i(r.takeExitCount));
            row.add(i(r.takeExitWinCount));
            row.add(i(r.takeExitLossCount));
            row.add(percent(r.winRate));
            row.add(percent(r.totalReturnPct));
            row.add(money(calcTotalPnl(r.initialCapital, r.finalCapital)));
            row.add(percent(r.maxDrawdownPct));
            row.add(money(r.finalCapital));
            summaryRows.add(row);
        }
        appendAlignedTable(sb, summaryHeaders, summaryRows);
        sb.append("\n");

        sb.append("## 盈利策略汇总").append("\n\n");
        List<List<String>> profitableRows = new ArrayList<>();
        for (BacktestResult r : sortedResults) {
            BigDecimal totalPnl = calcTotalPnl(r.initialCapital, r.finalCapital);
            if (totalPnl == null || totalPnl.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            List<String> row = new ArrayList<>();
            row.add(s(r.strategyName));
            row.add(s(r.symbol));
            row.add(i(r.totalBars));
            row.add(i(r.tradeCount));
            row.add(i(r.winCount));
            row.add(i(r.lossCount));
            row.add(i(r.flatCount));
            row.add(i(r.stopExitCount));
            row.add(i(r.stopExitWinCount));
            row.add(i(r.stopExitLossCount));
            row.add(i(r.takeExitCount));
            row.add(i(r.takeExitWinCount));
            row.add(i(r.takeExitLossCount));
            row.add(percent(r.winRate));
            row.add(percent(r.totalReturnPct));
            row.add(money(totalPnl));
            row.add(percent(r.maxDrawdownPct));
            row.add(money(r.finalCapital));
            profitableRows.add(row);
        }
        appendAlignedTable(sb, summaryHeaders, profitableRows);
        sb.append("\n");

        sb.append("## 按品种汇总").append("\n\n");
        List<List<String>> symbolSummaryRows = new ArrayList<>();
        for (BacktestResult r : symbolSortedResults) {
            List<String> row = new ArrayList<>();
            row.add(s(r.strategyName));
            row.add(s(r.symbol));
            row.add(i(r.totalBars));
            row.add(i(r.tradeCount));
            row.add(i(r.winCount));
            row.add(i(r.lossCount));
            row.add(i(r.flatCount));
            row.add(i(r.stopExitCount));
            row.add(i(r.stopExitWinCount));
            row.add(i(r.stopExitLossCount));
            row.add(i(r.takeExitCount));
            row.add(i(r.takeExitWinCount));
            row.add(i(r.takeExitLossCount));
            row.add(percent(r.winRate));
            row.add(percent(r.totalReturnPct));
            row.add(money(calcTotalPnl(r.initialCapital, r.finalCapital)));
            row.add(percent(r.maxDrawdownPct));
            row.add(money(r.finalCapital));
            symbolSummaryRows.add(row);
        }
        appendAlignedTable(sb, summaryHeaders, symbolSummaryRows);
        sb.append("\n");

        if (response.routingStats != null) {
            BacktestModels.RoutingStats a = response.routingStats;
            sb.append("## 确定性路由执行统计\n\n");
            sb.append("- 路由决策次数：").append(a.routingDecisionCount).append("\n");
            sb.append("- Regime分布：").append(a.regimeCounts).append("\n");
            sb.append("- 路由原因统计：").append(a.routingReasonCounts).append("\n");
            sb.append("- 主策略选择统计：").append(a.selectedStrategyCounts).append("\n");
            sb.append("- 合法候选出现次数：").append(a.candidateAcceptedCounts).append("\n");
            sb.append("- 候选拒绝原因：").append(a.candidateRejectReasonCounts).append("\n");
            sb.append("- 信号统计：").append(a.signalCounts).append("\n");
            sb.append("- 策略信号统计：").append(a.strategySignalCounts).append("\n");
            sb.append("- 策略成交统计：").append(a.strategyTradeCounts).append("\n\n");
            sb.append("- 结构趋势分布：").append(a.structuralTrendCounts).append("\n");
            sb.append("- 结构趋势阶段：").append(a.structuralPhaseCounts).append("\n");
            sb.append("- 信号来源统计：").append(a.signalSourceCounts).append("\n");
            sb.append("- 结构评分修正：").append(a.structuralScoreAdjustmentCounts).append("\n");
            sb.append("- TREND_COMPRESSION阶段：")
                    .append(a.trendCompressionPhaseCounts).append("\n");
            sb.append("- TREND_COMPRESSION方向：")
                    .append(a.trendCompressionDirectionCounts).append("\n\n");
            sb.append("- 趋势生命周期阶段：").append(a.trendLifecyclePhaseCounts).append("\n");
            sb.append("- 趋势生命周期原因：").append(a.trendLifecycleReasonCounts).append("\n\n");
            sb.append("- ETH多头趋势阶段：").append(a.ethBullTrendPhaseCounts).append("\n");
            sb.append("- ETH多头趋势原因：").append(a.ethBullTrendReasonCounts).append("\n");
            sb.append("- SOL多头趋势阶段：").append(a.solBullTrendPhaseCounts).append("\n");
            sb.append("- SOL多头趋势原因：").append(a.solBullTrendReasonCounts).append("\n\n");
            sb.append("- ETH空头趋势阶段：").append(a.ethBearTrendPhaseCounts).append("\n");
            sb.append("- ETH空头趋势原因：").append(a.ethBearTrendReasonCounts).append("\n\n");
        }

        if (response.routingDecisions != null && !response.routingDecisions.isEmpty()) {
            sb.append("## Market Regime 与确定性策略路由记录").append("\n\n");
            List<String> routingHeaders = java.util.Arrays.asList(
                    "决策时间戳", "Regime", "Regime置信度", "结构趋势", "结构阶段",
                    "结构置信度", "结构分数修正", "结构修正原因",
                    "前一策略", "当前策略", "执行策略", "信号来源",
                    "当前分数", "挑战策略", "挑战分数", "分差", "确认计数",
                    "候选Top3", "路由原因");
            appendCompactTableHeader(sb, routingHeaders);
            for (StrategyRoutingDecision d : response.routingDecisions) {
                List<String> row = new ArrayList<>();
                row.add(String.valueOf(d.barTime)); row.add(s(d.regime)); row.add(percent(d.regimeConfidence));
                row.add(s(d.structuralTrend)); row.add(s(d.structuralPhase)); row.add(percent(d.structuralConfidence));
                row.add(signedScore(d.structuralScoreAdjustment)); row.add(s(d.structuralScoreReason));
                row.add(s(d.previousStrategyName)); row.add(d.strategyName == null ? "NO_TRADE" : s(d.strategyName));
                row.add(s(d.executionStrategyName)); row.add(s(d.signalSource));
                row.add(score(d.activeScore)); row.add(s(d.challengerStrategyName)); row.add(score(d.challengerScore));
                row.add(score(d.scoreGap)); row.add(String.valueOf(d.pendingCount));
                row.add(topScores(d.scoreCards)); row.add(translateRoutingReason(d.reason));
                appendCompactRow(sb, row, routingHeaders.size());
            }
            sb.append("\n");
        }

        sb.append("## 按年度、策略和方向归因\n\n");
        Map<String,List<TradeRecord>> annualAttribution=new LinkedHashMap<String,List<TradeRecord>>();
        for(BacktestResult r:results){
            if(r.tradeList==null)continue;
            for(TradeRecord t:r.tradeList){
                String year=t.entryTime!=null&&t.entryTime.length()>=4?t.entryTime.substring(0,4):"UNKNOWN";
                String key=year+"|"+s(r.symbol)+"|"+s(t.strategyName)+"|"+s(t.side);
                List<TradeRecord> values=annualAttribution.get(key);
                if(values==null){values=new ArrayList<TradeRecord>();annualAttribution.put(key,values);}
                values.add(t);
            }
        }
        List<List<String>> attributionRows=new ArrayList<List<String>>();
        for(Entry<String,List<TradeRecord>> entry:annualAttribution.entrySet()){
            String[] key=entry.getKey().split("\\|",-1);int wins=0;BigDecimal pnl=BigDecimal.ZERO;
            BigDecimal positive=BigDecimal.ZERO,negative=BigDecimal.ZERO;
            for(TradeRecord t:entry.getValue())if(t.pnl!=null){pnl=pnl.add(t.pnl);if(t.pnl.signum()>0){wins++;positive=positive.add(t.pnl);}else if(t.pnl.signum()<0)negative=negative.add(t.pnl.abs());}
            List<String> row=new ArrayList<String>();row.add(key[0]);row.add(key[1]);row.add(key[2]);row.add(translateSide(key[3]));
            row.add(String.valueOf(entry.getValue().size()));row.add(String.valueOf(wins));
            row.add(entry.getValue().isEmpty()?"0.0000%":BigDecimal.valueOf(wins*100d/entry.getValue().size()).setScale(4,RoundingMode.HALF_UP).toPlainString()+"%");
            row.add(money(pnl));row.add(negative.signum()==0?(positive.signum()>0?"INF":"0.0000"):
                    positive.divide(negative,4,RoundingMode.HALF_UP).toPlainString());attributionRows.add(row);
        }
        appendAlignedTable(sb,java.util.Arrays.asList("年度","品种","策略","方向","交易数","盈利数","胜率","净盈亏","Profit Factor"),attributionRows);
        sb.append("\n");

        for (BacktestResult r : sortedResults) {
            sb.append("## 交易明细 - ").append(s(r.strategyName)).append(" - ").append(s(r.symbol)).append("\n\n");
            List<String> tradeHeaders = new ArrayList<>();
            tradeHeaders.add("序号"); tradeHeaders.add("品种"); tradeHeaders.add("开仓策略"); tradeHeaders.add("开仓Regime"); tradeHeaders.add("方向");
            tradeHeaders.add("开仓时间"); tradeHeaders.add("平仓时间"); tradeHeaders.add("开仓价"); tradeHeaders.add("平仓价");
            tradeHeaders.add("止损价"); tradeHeaders.add("止盈价"); tradeHeaders.add("持仓K线数");
            tradeHeaders.add("收益率"); tradeHeaders.add("盈亏"); tradeHeaders.add("退出原因");

            tradeHeaders.add("最大有利波动"); tradeHeaders.add("最大不利波动");
            tradeHeaders.add("利润捕获率"); tradeHeaders.add("入场生命周期");
            tradeHeaders.add("趋势触发类型"); tradeHeaders.add("退出生命周期");

            List<List<String>> tradeRows = new ArrayList<>();
            List<TradeRecord> tradeList = r.tradeList == null ? Collections.<TradeRecord>emptyList() : r.tradeList;
            for (int idx = 0; idx < tradeList.size(); idx++) {
                TradeRecord t = tradeList.get(idx);
                List<String> row = new ArrayList<>();
                row.add(String.valueOf(idx + 1));
                row.add(s(r.symbol));
                row.add(s(t.strategyName));
                row.add(s(t.regime));
                row.add(translateSide(t.side));
                row.add(s(t.entryTime));
                row.add(s(t.exitTime));
                row.add(price(t.entryPrice));
                row.add(price(t.exitPrice));
                row.add(price(t.stopPrice));
                row.add(price(t.takePrice));
                row.add(i(t.holdBars));
                row.add(percent(t.returnPct));
                row.add(money(t.pnl));
                row.add(translateExitReason(t.exitReason));
                row.add(percent(t.maxFavorableExcursionPct));
                row.add(percent(t.maxAdverseExcursionPct));
                row.add(percent(t.profitCaptureRatio));
                row.add(s(t.entryLifecyclePhase));
                row.add(s(t.trendTriggerType));
                row.add(s(t.exitLifecyclePhase));
                tradeRows.add(row);
            }
            appendAlignedTable(sb, tradeHeaders, tradeRows);
            sb.append("\n");

            if (r.rejectReasonCounts != null && !r.rejectReasonCounts.isEmpty()) {
                sb.append("### 信号拒绝原因").append("\n\n");
                List<String> rejectHeaders = new ArrayList<>();
                rejectHeaders.add("原因");
                rejectHeaders.add("次数");
                List<List<String>> rejectRows = new ArrayList<>();
                for (Entry<String, Integer> entry : r.rejectReasonCounts.entrySet()) {
                    List<String> row = new ArrayList<>();
                    row.add(translateRejectReason(entry.getKey()));
                    row.add(i(entry.getValue()));
                    rejectRows.add(row);
                }
                appendAlignedTable(sb, rejectHeaders, rejectRows);
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private void appendAlignedTable(StringBuilder sb, List<String> headers, List<List<String>> rows) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = safeCell(headers.get(i)).length();
        }
        for (List<String> row : rows) {
            for (int i = 0; i < cols; i++) {
                String cell = i < row.size() ? safeCell(row.get(i)) : "";
                if (cell.length() > widths[i]) {
                    widths[i] = cell.length();
                }
            }
        }

        appendRow(sb, headers, widths);
        sb.append("|");
        for (int i = 0; i < cols; i++) {
            sb.append(" ").append(repeat("-", widths[i])).append(" |");
        }
        sb.append("\n");
        for (List<String> row : rows) {
            appendRow(sb, row, widths);
        }
    }

    private void appendRow(StringBuilder sb, List<String> row, int[] widths) {
        sb.append("|");
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.size() ? safeCell(row.get(i)) : "";
            sb.append(" ").append(padRight(cell, widths[i])).append(" |");
        }
        sb.append("\n");
    }

    private String s(String v) {
        return v == null ? "" : safeCell(v);
    }

    private String translateSide(String side) {
        if ("BUY".equalsIgnoreCase(side)) return "买入/做多";
        if ("SELL".equalsIgnoreCase(side)) return "卖出/做空";
        if ("NONE".equalsIgnoreCase(side)) return "无方向";
        return s(side);
    }

    private String translateExitReason(String reason) {
        if (reason == null) return "";
        switch (reason) {
            case "stop_first_same_bar": return "同根K线同时触发，按止损处理";
            case "stop_loss": return "触发止损";
            case "take_profit": return "触发止盈";
            case "max_hold_bars": return "达到最大持仓K线数";
            case "reverse_signal": return "出现反向信号";
            case "strategy_close_signal": return "策略主动平仓";
            case "trend_consensus_confirmation_exit": return "Regime、均线与慢结构共识退出";
            case "end_of_test": return "回测结束强制平仓";
            case "trend_slow_structure_reversed": return "\u6162\u7ed3\u6784\u53cd\u8f6c\u9000\u51fa";
            case "trend_lifecycle_invalidated": return "\u8d8b\u52bf\u751f\u547d\u5468\u671f\u5931\u6548\u9000\u51fa";
            case "bull_slow_structure_reversed": return "多头慢结构反转退出";
            case "bull_atr_structure_trailing_exit": return "多头ATR/结构跟踪退出";
            case "eth_4h_chandelier_exit": return "ETH四小时ATR跟踪退出";
            case "eth_4h_bear_reversal_exit": return "ETH四小时趋势反转退出";
            case "eth_1h_soft_invalidation_exit": return "ETH一小时软失效确认退出";
            case "eth_breakeven_protection_exit": return "ETH趋势保本退出";
            default: return s(reason);
        }
    }

    private String translateRejectReason(String reason) {
        if (reason == null) return "";
        switch (reason) {
            case "SYMBOL_STRATEGY_DISABLED": return "该品种已禁用此策略";
            case "SYMBOL_NOT_SUPPORTED": return "策略不支持该品种或周期";
            case "SYMBOL_SIDE_BLOCKED": return "该品种已关闭此交易方向";
            case "LIFECYCLE_TREND_POSITION_OWNED": return "生命周期趋势持仓由开仓策略管理，拒绝外部反手";
            case "not_enough_bars": return "K线数量不足";
            case "invalid_atr": return "ATR无效";
            case "invalid_std": return "标准差无效";
            case "invalid_range": return "价格区间无效";
            case "slope_filter": return "未通过斜率过滤";
            case "width_filter": return "未通过带宽过滤";
            case "range_filter": return "未通过区间过滤";
            case "no_channel_touch": return "未触及通道边界";
            case "no_pullback_trigger": return "未触发回调条件";
            case "no_reversal_confirm": return "未出现反转确认";
            case "no_pending_state": return "不存在待确认状态";
            case "confirm_failed": return "信号确认失败";
            case "virtual_position_active": return "虚拟持仓仍有效";
            case "cooldown": return "策略处于冷却期";
            case "VWAP_STOP_COOLDOWN": return "VWAP止损后冷却";
            case "BINANCE_RANGE_STOP_COOLDOWN": return "区间策略止损后冷却";
            case "RANGE_DATA_WARMUP": return "区间策略数据预热";
            case "RANGE_INVALID_REFERENCE": return "区间参考边界无效";
            case "RANGE_BOX_UNSTABLE": return "历史箱体不稳定";
            case "RANGE_BREAKOUT_INVALIDATED": return "区间突破超过容许范围";
            case "RANGE_TRIGGER_RANGE_TOO_SMALL": return "触边反转K线振幅不足";
            case "RANGE_RECOVERY_NOT_CONFIRMED": return "触边后尚未确认回归";
            case "RANGE_EDGE_NOT_TOUCHED": return "价格尚未触及区间边缘";
            case "drift_block_buy": return "趋势漂移阻止买入";
            case "drift_block_sell": return "趋势漂移阻止卖出";
            case "INVALID_ENTRY_PRICE": return "开仓价格无效";
            case "INVALID_STOP_PRICE": return "止损价格方向或数值无效";
            case "INVALID_TAKE_PRICE": return "止盈价格方向或数值无效";
            default: return s(reason);
        }
    }

    private String translateRoutingReason(String reason) {
        if (reason == null) return "";
        switch (reason) {
            case "ACTIVATED": return "连续确认完成，主策略激活";
            case "ACTIVE_RETAIN": return "当前主策略继续保持";
            case "MINIMUM_HOLD": return "当前策略处于最短保持期";
            case "LIFECYCLE_TRIGGER_PRIORITY": return "生命周期趋势信号已触发，获得优先执行权";
            case "AWAITING_ACTIVATION": return "等待首次激活连续确认";
            case "AWAITING_SWITCH": return "挑战策略等待连续确认";
            case "SWITCHED": return "挑战策略满足条件，完成切换";
            case "HARD_INVALID": return "当前策略硬失效";
            case "HARD_INVALID_AWAITING_CONFIRMATION": return "硬失效后等待新策略确认";
            case "HARD_INVALID_SWITCHED": return "硬失效后新策略激活";
            case "LOW_SCORE_PENDING": return "当前策略低于保持门槛，等待确认";
            case "LOW_SCORE_EXIT": return "连续低分，当前策略退出";
            case "LOW_SCORE_SWITCHED": return "当前策略连续低分，切换至达标策略";
            case "CHALLENGER_MARGIN_NOT_MET": return "挑战策略分差不足";
            case "NO_SCORE_ABOVE_THRESHOLD": return "最高分未达到策略门槛";
            case "NO_CANDIDATE": return "没有合法候选策略";
            default: return s(reason);
        }
    }

    private String topScores(List<DeterministicScoreCard> cards) {
        if (cards == null || cards.isEmpty()) return "";
        StringBuilder value = new StringBuilder();
        int max = Math.min(3, cards.size());
        for (int i = 0; i < max; i++) {
            if (i > 0) value.append("; ");
            DeterministicScoreCard card = cards.get(i);
            value.append(card.strategyName).append("=")
                    .append(BigDecimal.valueOf(card.score).setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append("(").append(signedScore(card.structuralAdjustment)).append(")");
        }
        return value.toString();
    }

    private void appendCompactTableHeader(StringBuilder sb, List<String> headers) {
        appendCompactRow(sb, headers, headers.size());
        sb.append("|");
        for (int i = 0; i < headers.size(); i++) sb.append(" --- |");
        sb.append("\n");
    }

    private void appendCompactRow(StringBuilder sb, List<String> row, int columns) {
        sb.append("|");
        for (int i = 0; i < columns; i++) {
            String cell = i < row.size() ? safeCell(row.get(i)) : "";
            sb.append(" ").append(cell).append(" |");
        }
        sb.append("\n");
    }

    private String percent(BigDecimal ratio) {
        if (ratio == null) return "";
        return ratio.multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String percent(double ratio) {
        if (!Double.isFinite(ratio)) return "";
        return percent(BigDecimal.valueOf(ratio));
    }

    private String score(double value) {
        if (!Double.isFinite(value)) return "";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString() + "/100";
    }

    private String signedScore(double value) {
        if (!Double.isFinite(value)) return "";
        String number = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
        return (value > 0 ? "+" : "") + number;
    }

    private String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String price(BigDecimal value) {
        return value == null ? "" : value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal calcTotalPnl(BigDecimal initialCapital, BigDecimal finalCapital) {
        if (initialCapital == null || finalCapital == null) {
            return null;
        }
        return finalCapital.subtract(initialCapital);
    }

    private BigDecimal safeTotalPnl(BacktestResult result) {
        if (result == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalPnl = calcTotalPnl(result.initialCapital, result.finalCapital);
        return totalPnl == null ? BigDecimal.ZERO : totalPnl;
    }

    private String i(Integer v) {
        return v == null ? "0" : String.valueOf(v);
    }

    private Integer nzInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeCell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private String padRight(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + repeat(" ", width - value.length());
    }

    private String repeat(String unit, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(unit.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    private String safeFilePart(String v) {
        if (v == null || v.trim().isEmpty()) {
            return "na";
        }
        return v.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String joinSymbols(List<String> symbols) {
        StringJoiner joiner = new StringJoiner(",");
        for (String symbol : symbols) {
            joiner.add(s(symbol));
        }
        return joiner.toString();
    }

}
