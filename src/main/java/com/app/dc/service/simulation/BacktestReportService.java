package com.app.dc.service.simulation;

import com.app.dc.service.simulation.BacktestModels.BacktestResponse;
import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
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
import java.time.ZonedDateTime;
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

    @Value("${binanceBacktestReportMaxTrades:120}")
    private int reportMaxTrades;

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

    public String writeCompareReport(BacktestResponse response) {
        if (!reportEnabled || response == null) {
            return "";
        }
        try {
            String markdown = buildCompareMarkdown(response);
            if (markdown.isEmpty()) {
                return "";
            }
            Path dir = Paths.get(reportDir);
            Files.createDirectories(dir);
            String fileName = buildCompareFileName(response);
            Path filePath = dir.resolve(fileName);
            Files.write(filePath, markdown.getBytes(StandardCharsets.UTF_8));
            return filePath.toString().replace("\\", "/");
        } catch (Exception e) {
            log.error("BacktestReportService writeCompareReport error", e);
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

    private String buildCompareFileName(BacktestResponse response) {
        String strategy = safeFilePart(response.strategyName);
        String symbol = safeFilePart(response.symbol);
        String text = safeFilePart(response.text);
        String time = LocalDateTime.now().format(FILE_TIME);
        return strategy + "_" + symbol + "_" + text + "_" + time + "_compare.md";
    }

    private String buildMarkdown(BacktestResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 回测报告").append("\n\n");
        sb.append("- 策略: ").append(s(response.strategyName)).append("\n");
        sb.append("- 品种: ").append(s(response.symbol)).append("\n");
        if (response.symbols != null && !response.symbols.isEmpty()) {
            sb.append("- 品种列表: ").append(joinSymbols(response.symbols)).append("\n");
        }
        sb.append("- 周期: ").append(s(response.text)).append("\n");
        sb.append("- 开始日期: ").append(s(response.beginDate)).append("\n");
        sb.append("- 结束日期: ").append(s(response.endDate)).append("\n");
        sb.append("- 生成时间: ").append(LocalDateTime.now()).append("\n\n");

        List<BacktestResult> results = response.results == null
                ? Collections.<BacktestResult>emptyList()
                : response.results;
        List<BacktestResult> sortedResults = new ArrayList<>(results);
        sortedResults.sort(Comparator.comparing(this::safeTotalPnl).reversed()
                .thenComparing(result -> s(result.strategyName))
                .thenComparing(result -> s(result.symbol)));
        List<BacktestResult> symbolSortedResults = new ArrayList<>(results);
        symbolSortedResults.sort(Comparator.comparing((BacktestResult result) -> s(result.symbol))
                .thenComparing(this::safeTotalPnl, Comparator.reverseOrder())
                .thenComparing(result -> s(result.strategyName)));

        sb.append("## 汇总").append("\n\n");
        List<String> summaryHeaders = new ArrayList<>();
        summaryHeaders.add("策略");
        summaryHeaders.add("品种");
        summaryHeaders.add("K线数");
        summaryHeaders.add("交易数");
        summaryHeaders.add("盈利数");
        summaryHeaders.add("亏损数");
        summaryHeaders.add("打平数");
        summaryHeaders.add("止损出场数");
        summaryHeaders.add("止损盈利数");
        summaryHeaders.add("止损亏损数");
        summaryHeaders.add("止盈出场数");
        summaryHeaders.add("止盈盈利数");
        summaryHeaders.add("止盈亏损数");
        summaryHeaders.add("胜率");
        summaryHeaders.add("总收益率%");
        summaryHeaders.add("总盈亏");
        summaryHeaders.add("最大回撤%");
        summaryHeaders.add("最终资金");

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
            row.add(n(r.winRate));
            row.add(n(r.totalReturnPct));
            row.add(n(calcTotalPnl(r.initialCapital, r.finalCapital)));
            row.add(n(r.maxDrawdownPct));
            row.add(n(r.finalCapital));
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
            row.add(n(r.winRate));
            row.add(n(r.totalReturnPct));
            row.add(n(totalPnl));
            row.add(n(r.maxDrawdownPct));
            row.add(n(r.finalCapital));
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
            row.add(n(r.winRate));
            row.add(n(r.totalReturnPct));
            row.add(n(calcTotalPnl(r.initialCapital, r.finalCapital)));
            row.add(n(r.maxDrawdownPct));
            row.add(n(r.finalCapital));
            symbolSummaryRows.add(row);
        }
        appendAlignedTable(sb, summaryHeaders, symbolSummaryRows);
        sb.append("\n");

        for (BacktestResult r : sortedResults) {
            sb.append("## 交易明细 - ").append(s(r.strategyName)).append(" - ").append(s(r.symbol)).append("\n\n");
            List<String> tradeHeaders = new ArrayList<>();
            tradeHeaders.add("序号");
            tradeHeaders.add("品种");
            tradeHeaders.add("方向");
            tradeHeaders.add("开仓时间");
            tradeHeaders.add("平仓时间");
            tradeHeaders.add("开仓K线结束");
            tradeHeaders.add("平仓K线结束");
            tradeHeaders.add("开仓价");
            tradeHeaders.add("平仓价");
            tradeHeaders.add("止损价");
            tradeHeaders.add("止盈价");
            tradeHeaders.add("持仓K线数");
            tradeHeaders.add("收益率%");
            tradeHeaders.add("盈亏");
            tradeHeaders.add("出场原因");

            List<List<String>> tradeRows = new ArrayList<>();
            List<TradeRecord> tradeList = r.tradeList == null ? Collections.<TradeRecord>emptyList() : r.tradeList;
            int max = Math.min(Math.max(reportMaxTrades, 0), tradeList.size());
            for (int idx = 0; idx < max; idx++) {
                TradeRecord t = tradeList.get(idx);
                List<String> row = new ArrayList<>();
                row.add(String.valueOf(idx + 1));
                row.add(s(r.symbol));
                row.add(translateSide(t.side));
                row.add(formatDisplayTime(t.entryTime));
                row.add(formatDisplayTime(t.exitTime));
                row.add(formatDisplayTime(t.entryBarEndTime));
                row.add(formatDisplayTime(t.exitBarEndTime));
                row.add(n(t.entryPrice));
                row.add(n(t.exitPrice));
                row.add(n(t.stopPrice));
                row.add(n(t.takePrice));
                row.add(i(t.holdBars));
                row.add(n(t.returnPct));
                row.add(n(t.pnl));
                row.add(translateExitReason(t.exitReason));
                tradeRows.add(row);
            }
            appendAlignedTable(sb, tradeHeaders, tradeRows);
            if (tradeList.size() > max) {
                sb.append("\n");
                sb.append("> 交易明细已截断：还有 ").append(tradeList.size() - max)
                        .append(" 条未展示（限制=").append(max).append("）\n");
            }
            sb.append("\n");

            if (r.rejectReasonCounts != null && !r.rejectReasonCounts.isEmpty()) {
                sb.append("### 信号过滤原因").append("\n\n");
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

    private String buildCompareMarkdown(BacktestResponse response) {
        List<BacktestResult> results = response.results == null
                ? Collections.<BacktestResult>emptyList()
                : response.results;
        Map<String, Map<String, BacktestResult>> bySymbol = new LinkedHashMap<>();
        for (BacktestResult result : results) {
            if (result == null || result.symbol == null || result.strategyName == null) {
                continue;
            }
            bySymbol.computeIfAbsent(result.symbol, key -> new LinkedHashMap<>())
                    .put(result.strategyName, result);
        }

        List<CompareRow> compareRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, BacktestResult>> entry : bySymbol.entrySet()) {
            BacktestResult base = entry.getValue().get("binanceRange");
            BacktestResult guarded = entry.getValue().get("binanceRangeGuarded");
            if (base == null || guarded == null) {
                continue;
            }
            compareRows.add(buildCompareRow(entry.getKey(), base, guarded));
        }
        if (compareRows.isEmpty()) {
            return "";
        }
        compareRows.sort(Comparator.comparing(row -> row.symbol));

        StringBuilder sb = new StringBuilder();
        sb.append("# 回测对比报告").append("\n\n");
        sb.append("- 基准策略: ").append("binanceRange").append("\n");
        sb.append("- 候选策略: ").append("binanceRangeGuarded").append("\n");
        sb.append("- 品种: ").append(s(response.symbol)).append("\n");
        if (response.symbols != null && !response.symbols.isEmpty()) {
            sb.append("- 品种列表: ").append(joinSymbols(response.symbols)).append("\n");
        }
        sb.append("- 周期: ").append(s(response.text)).append("\n");
        sb.append("- 开始日期: ").append(s(response.beginDate)).append("\n");
        sb.append("- 结束日期: ").append(s(response.endDate)).append("\n");
        sb.append("- 生成时间: ").append(LocalDateTime.now()).append("\n\n");

        sb.append("## 对比汇总").append("\n\n");
        List<String> headers = new ArrayList<>();
        headers.add("品种");
        headers.add("基准交易数");
        headers.add("候选交易数");
        headers.add("交易数差值");
        headers.add("基准止损数");
        headers.add("候选止损数");
        headers.add("止损差值");
        headers.add("基准止损亏损数");
        headers.add("候选止损亏损数");
        headers.add("止损亏损差值");
        headers.add("基准止盈数");
        headers.add("候选止盈数");
        headers.add("止盈差值");
        headers.add("基准胜率");
        headers.add("候选胜率");
        headers.add("胜率差值");
        headers.add("基准收益率%");
        headers.add("候选收益率%");
        headers.add("收益率差值");
        headers.add("基准回撤%");
        headers.add("候选回撤%");
        headers.add("回撤差值");
        headers.add("基准盈亏");
        headers.add("候选盈亏");
        headers.add("盈亏差值");

        List<List<String>> rows = new ArrayList<>();
        for (CompareRow row : compareRows) {
            List<String> cells = new ArrayList<>();
            cells.add(s(row.symbol));
            cells.add(i(row.baseTrades));
            cells.add(i(row.guardedTrades));
            cells.add(i(row.tradesDelta));
            cells.add(i(row.baseStopExitCount));
            cells.add(i(row.guardedStopExitCount));
            cells.add(i(row.stopExitDelta));
            cells.add(i(row.baseStopExitLossCount));
            cells.add(i(row.guardedStopExitLossCount));
            cells.add(i(row.stopExitLossDelta));
            cells.add(i(row.baseTakeExitCount));
            cells.add(i(row.guardedTakeExitCount));
            cells.add(i(row.takeExitDelta));
            cells.add(n(row.baseWinRate));
            cells.add(n(row.guardedWinRate));
            cells.add(n(row.winRateDelta));
            cells.add(n(row.baseReturnPct));
            cells.add(n(row.guardedReturnPct));
            cells.add(n(row.returnDelta));
            cells.add(n(row.baseDrawdownPct));
            cells.add(n(row.guardedDrawdownPct));
            cells.add(n(row.drawdownDelta));
            cells.add(n(row.basePnl));
            cells.add(n(row.guardedPnl));
            cells.add(n(row.pnlDelta));
            rows.add(cells);
        }
        appendAlignedTable(sb, headers, rows);
        sb.append("\n");
        return sb.toString();
    }

    private CompareRow buildCompareRow(String symbol, BacktestResult base, BacktestResult guarded) {
        CompareRow row = new CompareRow();
        row.symbol = symbol;
        row.baseTrades = nzInt(base.tradeCount);
        row.guardedTrades = nzInt(guarded.tradeCount);
        row.tradesDelta = row.guardedTrades - row.baseTrades;
        row.baseStopExitCount = nzInt(base.stopExitCount);
        row.guardedStopExitCount = nzInt(guarded.stopExitCount);
        row.stopExitDelta = row.guardedStopExitCount - row.baseStopExitCount;
        row.baseStopExitLossCount = nzInt(base.stopExitLossCount);
        row.guardedStopExitLossCount = nzInt(guarded.stopExitLossCount);
        row.stopExitLossDelta = row.guardedStopExitLossCount - row.baseStopExitLossCount;
        row.baseTakeExitCount = nzInt(base.takeExitCount);
        row.guardedTakeExitCount = nzInt(guarded.takeExitCount);
        row.takeExitDelta = row.guardedTakeExitCount - row.baseTakeExitCount;
        row.baseWinRate = nz(base.winRate);
        row.guardedWinRate = nz(guarded.winRate);
        row.winRateDelta = scale(row.guardedWinRate.subtract(row.baseWinRate));
        row.baseReturnPct = nz(base.totalReturnPct);
        row.guardedReturnPct = nz(guarded.totalReturnPct);
        row.returnDelta = scale(row.guardedReturnPct.subtract(row.baseReturnPct));
        row.baseDrawdownPct = nz(base.maxDrawdownPct);
        row.guardedDrawdownPct = nz(guarded.maxDrawdownPct);
        row.drawdownDelta = scale(row.guardedDrawdownPct.subtract(row.baseDrawdownPct));
        row.basePnl = nz(calcTotalPnl(base.initialCapital, base.finalCapital));
        row.guardedPnl = nz(calcTotalPnl(guarded.initialCapital, guarded.finalCapital));
        row.pnlDelta = scale(row.guardedPnl.subtract(row.basePnl));
        return row;
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

    private String n(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
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

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(6, RoundingMode.HALF_UP);
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

    /** 将交易方向转换为报告中的中文展示。 */
    private String translateSide(String side) {
        if ("BUY".equalsIgnoreCase(side)) {
            return "做多";
        }
        if ("SELL".equalsIgnoreCase(side)) {
            return "做空";
        }
        return s(side);
    }

    /** 将平仓原因转换为报告中的中文展示。 */
    private String translateExitReason(String reason) {
        if ("reverse_signal".equalsIgnoreCase(reason)) {
            return "反向信号平仓";
        }
        if ("strategy_close_signal".equalsIgnoreCase(reason)) {
            return "策略离场平仓";
        }
        if ("stop_loss".equalsIgnoreCase(reason)) {
            return "止损平仓";
        }
        if ("take_profit".equalsIgnoreCase(reason)) {
            return "止盈平仓";
        }
        if ("end_of_test".equalsIgnoreCase(reason)) {
            return "回测结束平仓";
        }
        return s(reason);
    }

    /** 将信号过滤原因转换为报告中的中文展示。 */
    private String translateRejectReason(String reason) {
        if ("unsupported_text".equalsIgnoreCase(reason)) {
            return "不支持的K线周期";
        }
        if ("not_enough_samples".equalsIgnoreCase(reason) || "not_enough_bars".equalsIgnoreCase(reason)) {
            return "样本数量不足";
        }
        if ("duplicate_bar".equalsIgnoreCase(reason)) {
            return "重复K线";
        }
        if ("long_active_no_exit".equalsIgnoreCase(reason)) {
            return "多头生命周期未满足离场";
        }
        if ("short_active_no_exit".equalsIgnoreCase(reason)) {
            return "空头生命周期未满足离场";
        }
        if ("entry_blocked_by_dif_dea_bonding".equalsIgnoreCase(reason)) {
            return "DIF/DEA粘合过滤";
        }
        if ("reverse_cross_blocked_by_dif_dea_bonding".equalsIgnoreCase(reason)) {
            return "反向交叉被DIF/DEA粘合过滤";
        }
        if ("no_cross".equalsIgnoreCase(reason)) {
            return "未出现有效交叉";
        }
        if ("invalid_atr".equalsIgnoreCase(reason)) {
            return "ATR无效";
        }
        if ("slope_filter".equalsIgnoreCase(reason)) {
            return "斜率过滤";
        }
        if ("no_channel_touch".equalsIgnoreCase(reason)) {
            return "未触碰通道";
        }
        if ("no_pending_state".equalsIgnoreCase(reason)) {
            return "无待确认状态";
        }
        if ("confirm_failed".equalsIgnoreCase(reason)) {
            return "确认失败";
        }
        if ("virtual_position_active".equalsIgnoreCase(reason)) {
            return "虚拟持仓中";
        }
        if ("cooldown".equalsIgnoreCase(reason)) {
            return "冷却中";
        }
        if ("range_filter".equalsIgnoreCase(reason)) {
            return "震荡区间过滤";
        }
        if ("invalid_range".equalsIgnoreCase(reason)) {
            return "区间无效";
        }
        if ("drift_block_buy".equalsIgnoreCase(reason)) {
            return "漂移过滤做多";
        }
        if ("drift_block_sell".equalsIgnoreCase(reason)) {
            return "漂移过滤做空";
        }
        if ("no_reversal_confirm".equalsIgnoreCase(reason)) {
            return "未反转确认";
        }
        if ("invalid_std".equalsIgnoreCase(reason)) {
            return "标准差无效";
        }
        if ("width_filter".equalsIgnoreCase(reason)) {
            return "带宽过滤";
        }
        if ("no_pullback_trigger".equalsIgnoreCase(reason)) {
            return "未触发回踩";
        }
        return s(reason);
    }

    /** 灏嗗甫鏃跺尯鐨勬椂闂存牸寮忓帇缂╀负鎶ュ憡涓殑鏈湴鏃堕棿瀛楃涓层€?*/
    private String formatDisplayTime(String value) {
        String text = s(value);
        if (text.isEmpty()) {
            return text;
        }
        try {
            return ZonedDateTime.parse(text).toLocalDateTime().toString();
        } catch (Exception ignore) {
            return text;
        }
    }

    private static class CompareRow {
        private String symbol;
        private Integer baseTrades;
        private Integer guardedTrades;
        private Integer tradesDelta;
        private Integer baseStopExitCount;
        private Integer guardedStopExitCount;
        private Integer stopExitDelta;
        private Integer baseStopExitLossCount;
        private Integer guardedStopExitLossCount;
        private Integer stopExitLossDelta;
        private Integer baseTakeExitCount;
        private Integer guardedTakeExitCount;
        private Integer takeExitDelta;
        private BigDecimal baseWinRate;
        private BigDecimal guardedWinRate;
        private BigDecimal winRateDelta;
        private BigDecimal baseReturnPct;
        private BigDecimal guardedReturnPct;
        private BigDecimal returnDelta;
        private BigDecimal baseDrawdownPct;
        private BigDecimal guardedDrawdownPct;
        private BigDecimal drawdownDelta;
        private BigDecimal basePnl;
        private BigDecimal guardedPnl;
        private BigDecimal pnlDelta;
    }
}
