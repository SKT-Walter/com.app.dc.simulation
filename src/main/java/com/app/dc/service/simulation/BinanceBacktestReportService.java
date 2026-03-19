package com.app.dc.service.simulation;

import com.app.dc.service.simulation.BinanceBacktestModels.BacktestResponse;
import com.app.dc.service.simulation.BinanceBacktestModels.BacktestResult;
import com.app.dc.service.simulation.BinanceBacktestModels.TradeRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Export backtest result to markdown report.
 */
@Service
@Slf4j
public class BinanceBacktestReportService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Value("${binanceBacktestReportEnabled:true}")
    private boolean reportEnabled;

    @Value("${binanceBacktestReportDir:./src/docs}")
    private String reportDir;

    @Value("${binanceBacktestReportMaxTrades:120}")
    private int reportMaxTrades;

    /**
     * Generate markdown report and return saved file path.
     */
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
            log.error("BinanceBacktestReportService writeReport error", e);
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
        sb.append("# Binance Backtest Report").append("\n\n");
        sb.append("- strategy: ").append(s(response.strategyName)).append("\n");
        sb.append("- symbol: ").append(s(response.symbol)).append("\n");
        sb.append("- timeframe: ").append(s(response.text)).append("\n");
        sb.append("- beginDate: ").append(s(response.beginDate)).append("\n");
        sb.append("- endDate: ").append(s(response.endDate)).append("\n");
        sb.append("- generatedAt: ").append(LocalDateTime.now()).append("\n\n");

        List<BacktestResult> results = response.results == null ? Collections.<BacktestResult>emptyList() : response.results;

        sb.append("## Summary").append("\n\n");
        List<String> summaryHeaders = new ArrayList<>();
        summaryHeaders.add("strategy/策略");
        summaryHeaders.add("totalBars/K线数");
        summaryHeaders.add("trades/交易数");
        summaryHeaders.add("win/盈利笔数");
        summaryHeaders.add("loss/亏损笔数");
        summaryHeaders.add("flat/持平笔数");
        summaryHeaders.add("winRate/胜率");
        summaryHeaders.add("totalReturnPct/总收益率");
        summaryHeaders.add("totalPnl/总盈亏");
        summaryHeaders.add("maxDrawdownPct/最大回撤");
        summaryHeaders.add("finalCapital/最终资金");

        List<List<String>> summaryRows = new ArrayList<>();
        for (BacktestResult r : results) {
            List<String> row = new ArrayList<>();
            row.add(s(r.strategyName));
            row.add(i(r.totalBars));
            row.add(i(r.tradeCount));
            row.add(i(r.winCount));
            row.add(i(r.lossCount));
            row.add(i(r.flatCount));
            row.add(n(r.winRate));
            row.add(n(r.totalReturnPct));
            row.add(n(calcTotalPnl(r.initialCapital, r.finalCapital)));
            row.add(n(r.maxDrawdownPct));
            row.add(n(r.finalCapital));
            summaryRows.add(row);
        }
        appendAlignedTable(sb, summaryHeaders, summaryRows);
        sb.append("\n");

        for (BacktestResult r : results) {
            sb.append("## Trades - ").append(s(r.strategyName)).append("\n\n");
            List<String> tradeHeaders = new ArrayList<>();
            tradeHeaders.add("#/序号");
            tradeHeaders.add("side/方向");
            tradeHeaders.add("entryTime/开仓时间");
            tradeHeaders.add("exitTime/平仓时间");
            tradeHeaders.add("entryPrice/开仓价");
            tradeHeaders.add("exitPrice/平仓价");
            tradeHeaders.add("stopPrice/止损价");
            tradeHeaders.add("takePrice/止盈价");
            tradeHeaders.add("holdBars/持仓K线数");
            tradeHeaders.add("returnPct/收益率");
            tradeHeaders.add("pnl/盈亏");
            tradeHeaders.add("exitReason/平仓原因");

            List<List<String>> tradeRows = new ArrayList<>();
            List<TradeRecord> tradeList = r.tradeList == null ? Collections.<TradeRecord>emptyList() : r.tradeList;
            int max = Math.min(Math.max(reportMaxTrades, 0), tradeList.size());
            for (int idx = 0; idx < max; idx++) {
                TradeRecord t = tradeList.get(idx);
                List<String> row = new ArrayList<>();
                row.add(String.valueOf(idx + 1));
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

    private String n(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private BigDecimal calcTotalPnl(BigDecimal initialCapital, BigDecimal finalCapital) {
        if (initialCapital == null || finalCapital == null) {
            return null;
        }
        return finalCapital.subtract(initialCapital);
    }

    private String n(Double v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String i(Integer v) {
        return v == null ? "0" : String.valueOf(v);
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
}
