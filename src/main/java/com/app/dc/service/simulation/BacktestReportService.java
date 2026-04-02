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
        String strategy = safeFilePart(strategyLabel(response.strategyName, response.strategyVersion));
        String symbol = safeFilePart(response.symbol);
        String text = safeFilePart(response.text);
        String time = LocalDateTime.now().format(FILE_TIME);
        return strategy + "_" + symbol + "_" + text + "_" + time + ".md";
    }

    private String buildCompareFileName(BacktestResponse response) {
        String strategy = safeFilePart(strategyLabel(response.strategyName, response.strategyVersion));
        String symbol = safeFilePart(response.symbol);
        String text = safeFilePart(response.text);
        String time = LocalDateTime.now().format(FILE_TIME);
        return strategy + "_" + symbol + "_" + text + "_" + time + "_compare.md";
    }

    private String buildMarkdown(BacktestResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Backtest Report").append("\n\n");
        sb.append("- strategy: ").append(strategyLabel(response.strategyName, response.strategyVersion)).append("\n");
        sb.append("- symbol: ").append(s(response.symbol)).append("\n");
        if (response.symbols != null && !response.symbols.isEmpty()) {
            sb.append("- symbols: ").append(joinSymbols(response.symbols)).append("\n");
        }
        sb.append("- timeframe: ").append(s(response.text)).append("\n");
        sb.append("- beginDate: ").append(s(response.beginDate)).append("\n");
        sb.append("- endDate: ").append(s(response.endDate)).append("\n");
        sb.append("- generatedAt: ").append(LocalDateTime.now()).append("\n\n");

        List<BacktestResult> results = response.results == null
                ? Collections.<BacktestResult>emptyList()
                : response.results;
        List<BacktestResult> sortedResults = new ArrayList<>(results);
        sortedResults.sort(Comparator.comparing(this::safeTotalPnl).reversed()
                .thenComparing(result -> strategyLabel(result.strategyName, result.strategyVersion))
                .thenComparing(result -> s(result.symbol)));
        List<BacktestResult> symbolSortedResults = new ArrayList<>(results);
        symbolSortedResults.sort(Comparator.comparing((BacktestResult result) -> s(result.symbol))
                .thenComparing(this::safeTotalPnl, Comparator.reverseOrder())
                .thenComparing(result -> strategyLabel(result.strategyName, result.strategyVersion)));

        sb.append("## Summary").append("\n\n");
        List<String> summaryHeaders = new ArrayList<>();
        summaryHeaders.add("strategy/strategy");
        summaryHeaders.add("symbol/symbol");
        summaryHeaders.add("totalBars/bars");
        summaryHeaders.add("trades/trades");
        summaryHeaders.add("win/win");
        summaryHeaders.add("loss/loss");
        summaryHeaders.add("flat/flat");
        summaryHeaders.add("stopExit/stopExit");
        summaryHeaders.add("stopExitWin/stopExitWin");
        summaryHeaders.add("stopExitLoss/stopExitLoss");
        summaryHeaders.add("takeExit/takeExit");
        summaryHeaders.add("takeExitWin/takeExitWin");
        summaryHeaders.add("takeExitLoss/takeExitLoss");
        summaryHeaders.add("winRate/winRate");
        summaryHeaders.add("totalReturnPct/totalReturnPct");
        summaryHeaders.add("totalPnl/totalPnl");
        summaryHeaders.add("maxDrawdownPct/maxDrawdownPct");
        summaryHeaders.add("finalCapital/finalCapital");

        List<List<String>> summaryRows = new ArrayList<>();
        for (BacktestResult r : sortedResults) {
            List<String> row = new ArrayList<>();
            row.add(strategyLabel(r.strategyName, r.strategyVersion));
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

        sb.append("## Profitable Summary").append("\n\n");
        List<List<String>> profitableRows = new ArrayList<>();
        for (BacktestResult r : sortedResults) {
            BigDecimal totalPnl = calcTotalPnl(r.initialCapital, r.finalCapital);
            if (totalPnl == null || totalPnl.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            List<String> row = new ArrayList<>();
            row.add(strategyLabel(r.strategyName, r.strategyVersion));
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

        sb.append("## Summary By Symbol").append("\n\n");
        List<List<String>> symbolSummaryRows = new ArrayList<>();
        for (BacktestResult r : symbolSortedResults) {
            List<String> row = new ArrayList<>();
            row.add(strategyLabel(r.strategyName, r.strategyVersion));
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
            sb.append("## Trades - ").append(strategyLabel(r.strategyName, r.strategyVersion))
                    .append(" - ").append(s(r.symbol)).append("\n\n");
            List<String> tradeHeaders = new ArrayList<>();
            tradeHeaders.add("#/index");
            tradeHeaders.add("symbol/symbol");
            tradeHeaders.add("side/side");
            tradeHeaders.add("entryTime/entryTime");
            tradeHeaders.add("exitTime/exitTime");
            tradeHeaders.add("entryPrice/entryPrice");
            tradeHeaders.add("exitPrice/exitPrice");
            tradeHeaders.add("stopPrice/stopPrice");
            tradeHeaders.add("takePrice/takePrice");
            tradeHeaders.add("holdBars/holdBars");
            tradeHeaders.add("returnPct/returnPct");
            tradeHeaders.add("pnl/pnl");
            tradeHeaders.add("exitReason/exitReason");

            List<List<String>> tradeRows = new ArrayList<>();
            List<TradeRecord> tradeList = r.tradeList == null ? Collections.<TradeRecord>emptyList() : r.tradeList;
            int max = Math.min(Math.max(reportMaxTrades, 0), tradeList.size());
            for (int idx = 0; idx < max; idx++) {
                TradeRecord t = tradeList.get(idx);
                List<String> row = new ArrayList<>();
                row.add(String.valueOf(idx + 1));
                row.add(s(r.symbol));
                row.add(s(t.side));
                row.add(s(t.entryTime));
                row.add(s(t.exitTime));
                row.add(n(t.entryPrice));
                row.add(n(t.exitPrice));
                row.add(n(t.stopPrice));
                row.add(n(t.takePrice));
                row.add(i(t.holdBars));
                row.add(n(t.returnPct));
                row.add(n(t.pnl));
                row.add(s(t.exitReason));
                tradeRows.add(row);
            }
            appendAlignedTable(sb, tradeHeaders, tradeRows);
            if (tradeList.size() > max) {
                sb.append("\n");
                sb.append("> trade rows truncated: ").append(tradeList.size() - max)
                        .append(" not shown (limit=").append(max).append(")\n");
            }
            sb.append("\n");

            if (r.rejectReasonCounts != null && !r.rejectReasonCounts.isEmpty()) {
                sb.append("### Reject Reasons").append("\n\n");
                List<String> rejectHeaders = new ArrayList<>();
                rejectHeaders.add("reason/reason");
                rejectHeaders.add("count/count");
                List<List<String>> rejectRows = new ArrayList<>();
                for (Entry<String, Integer> entry : r.rejectReasonCounts.entrySet()) {
                    List<String> row = new ArrayList<>();
                    row.add(s(entry.getKey()));
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
                    .put(s(result.strategyVersion), result);
        }

        String baseVersion = s(response.baselineVersion);
        String candidateVersion = s(response.strategyVersion);
        if (baseVersion.isEmpty() || candidateVersion.isEmpty()) {
            return "";
        }
        List<CompareRow> compareRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, BacktestResult>> entry : bySymbol.entrySet()) {
            BacktestResult base = entry.getValue().get(baseVersion);
            BacktestResult candidate = entry.getValue().get(candidateVersion);
            if (base == null || candidate == null) {
                continue;
            }
            compareRows.add(buildCompareRow(entry.getKey(), base, candidate));
        }
        if (compareRows.isEmpty()) {
            return "";
        }
        compareRows.sort(Comparator.comparing(row -> row.symbol));

        StringBuilder sb = new StringBuilder();
        sb.append("# Backtest Compare Report").append("\n\n");
        sb.append("- baseStrategy: ").append(strategyLabel(response.strategyName, baseVersion)).append("\n");
        sb.append("- candidateStrategy: ").append(strategyLabel(response.strategyName, candidateVersion)).append("\n");
        sb.append("- symbol: ").append(s(response.symbol)).append("\n");
        if (response.symbols != null && !response.symbols.isEmpty()) {
            sb.append("- symbols: ").append(joinSymbols(response.symbols)).append("\n");
        }
        sb.append("- timeframe: ").append(s(response.text)).append("\n");
        sb.append("- beginDate: ").append(s(response.beginDate)).append("\n");
        sb.append("- endDate: ").append(s(response.endDate)).append("\n");
        sb.append("- generatedAt: ").append(LocalDateTime.now()).append("\n\n");

        sb.append("## Compare Summary").append("\n\n");
        List<String> headers = new ArrayList<>();
        headers.add("symbol/symbol");
        headers.add("baseTrades");
        headers.add("guardedTrades");
        headers.add("tradesDelta");
        headers.add("baseStopExit");
        headers.add("guardedStopExit");
        headers.add("stopExitDelta");
        headers.add("baseStopExitLoss");
        headers.add("guardedStopExitLoss");
        headers.add("stopExitLossDelta");
        headers.add("baseTakeExit");
        headers.add("guardedTakeExit");
        headers.add("takeExitDelta");
        headers.add("baseWinRate");
        headers.add("guardedWinRate");
        headers.add("winRateDelta");
        headers.add("baseReturnPct");
        headers.add("guardedReturnPct");
        headers.add("returnDelta");
        headers.add("baseDrawdown");
        headers.add("guardedDrawdown");
        headers.add("drawdownDelta");
        headers.add("basePnl");
        headers.add("guardedPnl");
        headers.add("pnlDelta");

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

    private CompareRow buildCompareRow(String symbol, BacktestResult base, BacktestResult candidate) {
        CompareRow row = new CompareRow();
        row.symbol = symbol;
        row.baseTrades = nzInt(base.tradeCount);
        row.guardedTrades = nzInt(candidate.tradeCount);
        row.tradesDelta = row.guardedTrades - row.baseTrades;
        row.baseStopExitCount = nzInt(base.stopExitCount);
        row.guardedStopExitCount = nzInt(candidate.stopExitCount);
        row.stopExitDelta = row.guardedStopExitCount - row.baseStopExitCount;
        row.baseStopExitLossCount = nzInt(base.stopExitLossCount);
        row.guardedStopExitLossCount = nzInt(candidate.stopExitLossCount);
        row.stopExitLossDelta = row.guardedStopExitLossCount - row.baseStopExitLossCount;
        row.baseTakeExitCount = nzInt(base.takeExitCount);
        row.guardedTakeExitCount = nzInt(candidate.takeExitCount);
        row.takeExitDelta = row.guardedTakeExitCount - row.baseTakeExitCount;
        row.baseWinRate = nz(base.winRate);
        row.guardedWinRate = nz(candidate.winRate);
        row.winRateDelta = scale(row.guardedWinRate.subtract(row.baseWinRate));
        row.baseReturnPct = nz(base.totalReturnPct);
        row.guardedReturnPct = nz(candidate.totalReturnPct);
        row.returnDelta = scale(row.guardedReturnPct.subtract(row.baseReturnPct));
        row.baseDrawdownPct = nz(base.maxDrawdownPct);
        row.guardedDrawdownPct = nz(candidate.maxDrawdownPct);
        row.drawdownDelta = scale(row.guardedDrawdownPct.subtract(row.baseDrawdownPct));
        row.basePnl = nz(calcTotalPnl(base.initialCapital, base.finalCapital));
        row.guardedPnl = nz(calcTotalPnl(candidate.initialCapital, candidate.finalCapital));
        row.pnlDelta = scale(row.guardedPnl.subtract(row.basePnl));
        return row;
    }

    private String strategyLabel(String strategyName, String strategyVersion) {
        String name = s(strategyName);
        String version = s(strategyVersion);
        if (name.isEmpty()) {
            return version;
        }
        if (version.isEmpty()) {
            return name;
        }
        return name + "@" + version;
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
