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

    @Value("${binanceBacktestReportMaxTrades:0}")
    private int reportMaxTrades;

    public String writeReport(BacktestResponse response) {
        return writeReport(response, "");
    }

    /**
     * 输出带业务标签的回测报告，便于批量回测区分日期段。
     */
    public String writeReport(BacktestResponse response, String fileTag) {
        if (!reportEnabled || response == null) {
            return "";
        }
        try {
            Path dir = Paths.get(reportDir);
            Files.createDirectories(dir);
            String fileName = buildFileName(response, fileTag);
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

    private String buildFileName(BacktestResponse response, String fileTag) {
        String strategy = safeFilePart(response.strategyName);
        String symbol = safeFilePart(response.symbol);
        String text = safeFilePart(response.text);
        String tag = fileTag == null || fileTag.trim().isEmpty()
                ? ""
                : "_" + safeFilePart(fileTag.trim());
        String time = LocalDateTime.now().format(FILE_TIME);
        return strategy + "_" + symbol + "_" + text + tag + "_" + time + ".md";
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
        sb.append("- 策略名称: ").append(s(response.strategyName)).append("\n");
        sb.append("- 主符号: ").append(s(response.symbol)).append("\n");
        if (response.symbols != null && !response.symbols.isEmpty()) {
            sb.append("- 回测符号列表: ").append(joinSymbols(response.symbols)).append("\n");
        }
        sb.append("- K线周期: ").append(s(response.text)).append("\n");
        sb.append("- 开始日期: ").append(s(response.beginDate)).append("\n");
        sb.append("- 结束日期: ").append(s(response.endDate)).append("\n");
        sb.append("- 报告生成时间: ").append(LocalDateTime.now()).append("\n\n");

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
        summaryHeaders.add("符号");
        summaryHeaders.add("K线数");
        summaryHeaders.add("交易数");
        summaryHeaders.add("盈利单");
        summaryHeaders.add("亏损单");
        summaryHeaders.add("平局单");
        summaryHeaders.add("止损平仓数");
        summaryHeaders.add("止损盈利数");
        summaryHeaders.add("止损亏损数");
        summaryHeaders.add("止盈平仓数");
        summaryHeaders.add("止盈盈利数");
        summaryHeaders.add("止盈亏损数");
        summaryHeaders.add("胜率%");
        summaryHeaders.add("总收益率%");
        summaryHeaders.add("总收益");
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
            row.add(pct(r.winRate));
            row.add(pct(r.totalReturnPct));
            row.add(n(calcTotalPnl(r.initialCapital, r.finalCapital)));
            row.add(pct(r.maxDrawdownPct));
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
            row.add(pct(r.winRate));
            row.add(pct(r.totalReturnPct));
            row.add(n(totalPnl));
            row.add(pct(r.maxDrawdownPct));
            row.add(n(r.finalCapital));
            profitableRows.add(row);
        }
        appendAlignedTable(sb, summaryHeaders, profitableRows);
        sb.append("\n");

        sb.append("## 按符号汇总").append("\n\n");
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
            row.add(pct(r.winRate));
            row.add(pct(r.totalReturnPct));
            row.add(n(calcTotalPnl(r.initialCapital, r.finalCapital)));
            row.add(pct(r.maxDrawdownPct));
            row.add(n(r.finalCapital));
            symbolSummaryRows.add(row);
        }
        appendAlignedTable(sb, summaryHeaders, symbolSummaryRows);
        sb.append("\n");

        for (BacktestResult r : sortedResults) {
            appendEntryReasonStats(sb, r);
            sb.append("## 交易明细 - ").append(s(r.strategyName)).append(" - ").append(s(r.symbol)).append("\n\n");
            List<String> tradeHeaders = new ArrayList<>();
            tradeHeaders.add("序号");
            tradeHeaders.add("符号");
            tradeHeaders.add("方向");
            tradeHeaders.add("开仓时间");
            tradeHeaders.add("平仓时间");
            tradeHeaders.add("开仓K线结束时间");
            tradeHeaders.add("平仓K线结束时间");
            tradeHeaders.add("开仓价");
            tradeHeaders.add("平仓价");
            tradeHeaders.add("止损价");
            tradeHeaders.add("止盈价");
            tradeHeaders.add("持仓K线数");
            tradeHeaders.add("收益率%");
            tradeHeaders.add("收益");
            tradeHeaders.add("出场原因");
            tradeHeaders.add("入场原因");

            List<List<String>> tradeRows = new ArrayList<>();
            List<TradeRecord> tradeList = r.tradeList == null ? Collections.<TradeRecord>emptyList() : r.tradeList;
            int max = reportMaxTrades <= 0
                    ? tradeList.size()
                    : Math.min(reportMaxTrades, tradeList.size());
            for (int offset = 0; offset < max; offset++) {
                int tradeIndex = tradeList.size() - 1 - offset;
                TradeRecord t = tradeList.get(tradeIndex);
                List<String> row = new ArrayList<>();
                row.add(String.valueOf(tradeIndex + 1));
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
                row.add(pct(t.returnPct));
                row.add(n(t.pnl));
                row.add(translateExitReason(t.exitReason));
                row.add(translateEntryReason(t.entryReason));
                tradeRows.add(row);
            }
            appendAlignedTable(sb, tradeHeaders, tradeRows);
            if (tradeList.size() > max) {
                sb.append("\n");
                sb.append("> 报告已截断，省略 ").append(tradeList.size() - max)
                        .append(" 笔交易，仅展示最近 ").append(max).append(" 笔");
            }
            sb.append("\n");

            if (r.rejectReasonCounts != null && !r.rejectReasonCounts.isEmpty()) {
                sb.append("### 信号过滤原因统计").append("\n\n");
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
        sb.append("# 策略对比报告").append("\n\n");
        sb.append("- 基准策略: ").append("binanceRange").append("\n");
        sb.append("- 对比策略: ").append("binanceRangeGuarded").append("\n");
        sb.append("- 主符号: ").append(s(response.symbol)).append("\n");
        if (response.symbols != null && !response.symbols.isEmpty()) {
            sb.append("- 回测符号列表: ").append(joinSymbols(response.symbols)).append("\n");
        }
        sb.append("- K线周期: ").append(s(response.text)).append("\n");
        sb.append("- 开始日期: ").append(s(response.beginDate)).append("\n");
        sb.append("- 结束日期: ").append(s(response.endDate)).append("\n");
        sb.append("- 报告生成时间: ").append(LocalDateTime.now()).append("\n\n");

        sb.append("## 策略对比").append("\n\n");
        List<String> headers = new ArrayList<>();
        headers.add("Symbol");
        headers.add("BaseTrades");
        headers.add("CandidateTrades");
        headers.add("BaseWins");
        headers.add("CandidateWins");
        headers.add("BaseLosses");
        headers.add("CandidateLosses");
        headers.add("BaseFlats");
        headers.add("CandidateFlats");
        headers.add("BaseStopExits");
        headers.add("CandidateStopExits");
        headers.add("BaseTakeExits");
        headers.add("CandidateTakeExits");
        headers.add("BaseWinRate");
        headers.add("CandidateWinRate");
        headers.add("BaseReturn%");
        headers.add("CandidateReturn%");
        headers.add("BasePnL");
        headers.add("CandidatePnL");
        headers.add("BaseMaxDD%");
        headers.add("CandidateMaxDD%");
        headers.add("BaseFinalCapital");
        headers.add("CandidateFinalCapital");
        headers.add("PnLDiff");
        headers.add("WinRateDiff");

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
            cells.add(pct(row.baseWinRate));
            cells.add(pct(row.guardedWinRate));
            cells.add(pct(row.winRateDelta));
            cells.add(pct(row.baseReturnPct));
            cells.add(pct(row.guardedReturnPct));
            cells.add(pct(row.returnDelta));
            cells.add(pct(row.baseDrawdownPct));
            cells.add(pct(row.guardedDrawdownPct));
            cells.add(pct(row.drawdownDelta));
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

    /**
     * 闂傚倸鍊峰ù鍥敋瑜忛幑銏ゅ箛椤旇棄搴婇梺褰掑亰閸庨潧鈽夊Ο婊勬瀹曘劑顢橀垾宕囧幋闂傚倷绀佸﹢閬嶆惞鎼淬劌绐楁俊銈呮噺閸婂灝螖閿濆懎鏆為柣鎾崇箰閳规垿鎮欓懠顑胯檸闂佸憡鏌ｉ崐鏍箞閵娾晜鏅查柛娑卞灠閳敻姊洪崫鍕槵闁告挻绻堥獮蹇涘川閺夋垳绱堕梺鍛婃礀閻忔艾袙婢舵劖鈷掑ù锝夘棑娑撴煡鎮楅棃娑氱劯鐎规洘婢樿灃闁告劦浜為悾鍫曟⒑缂佹ɑ顥嗛柕鍡忓亾闂佺顑嗛幑鍥极閹邦厽鍎熼柍銉ョ－椤旀垹绱撻崒娆愵樂缂佽绻濆畷鎶芥晲婢跺﹨鎽曢梺缁樻閸嬫劕鐣垫笟鈧弻娑⑩€﹂幋婵囩亾婵炲濞€缁犳牕顫忓ú顏勭闁绘劖褰冮‖澶愭倵閸忓浜鹃梺褰掓？閼宠泛鐣垫笟鈧獮鏍庨鈧俊濂稿船椤栫偞鈷戦梻鍫熶緱濡狙呯磼閻樺啿鐏寸€殿喗濞婂畷濂稿Ψ閿旇瀚藉┑鐐舵彧缁插潡骞婇幘璺虹筏濠电姵纰嶉悡鐔搞亜閹烘垵鈧摜鏁崼鏇熺厓缂備焦蓱瀹曞本顨ラ悙宸剶闁轰礁鍊块幐濠冨緞婵犲偆妫冮梻鍌氬€峰ù鍥敋閺嶎厼鍨傞幖娣妼缁€鍐┿亜韫囧海顦﹀ù婊勫劤閳规垿鎮╁畷鍥舵殹闂佺粯鎸荤粙鎴︽箒闂佹寧绻傞悧婊冾焽閹邦喚纾奸柤鍝ユ暩閸欌偓闂佸搫鐭夌紞渚€銆侀弴銏狀潊闁虫儼锟ラ崐妤冩閹烘鏁婇柤娴嬫櫅閳潧螖閻橀潧浠掔紒鑸靛哺閻涱噣骞掑Δ鈧獮銏′繆閻愭潙鍔ゆい?     */
    private String pct(BigDecimal v) {
        return v == null ? "" : v.multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP).toPlainString();
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

    /** 闂傚倸鍊峰ù鍥敋瑜忛幑銏ゅ箛椤旇棄搴婇梺褰掑亰閸庨潧鈽夐姀鐘愁棟濠电偛妫涢崑鎾诲磽閻㈠憡鈷戦柟绋垮缁€鈧梺绋匡攻閹倸鐣烽敐鍡欑瘈闁搞儯鍔庨崢鐢告⒑鐠団€崇€婚柛娑卞枟閸犳牠姊绘担铏瑰笡闁圭顭烽幃鐑藉煛閸涱叀鎽曞┑鐐村灦閿曗晛顭囬埡鍌樹簻闁规崘娉涙禒褍霉閻撳孩鎼愰柍瑙勫灴閹瑧鈧稒顭囩粙鍥р攽閳藉棗浜濈紒璇茬墦閹即顢氶埀顒勭嵁鐎ｎ喗鏅滈柤娴嬫櫇閳笺倖淇婇悙顏勨偓鏍箹椤愩倖顫曢柡鍥ｆ嚍閸ャ劌顕遍悗娑櫱氶幏铏圭磼缂併垹骞栭柟铏姍瀹曞ジ顢旈崼鐔哄幈闁瑰吋鐣崝瀣箟閻愵剦娈介柣鎰▕濡偓闂佺硶鏅涚€氭澘鐣峰鈧、鏃€鎷呴崷顓濈胺婵犵绱曢崑鎴﹀磹閺嶎厼绠伴柣鎰靛墯閸欏繘鏌ｉ姀鐘冲暈闁稿鏅犻弻娑樜旈崘銊ゆ睏濠碘剝褰冮悧鎾诲蓟閻旂厧鍨傛い鏂垮悑濞堫厼螖閻橀潧浠︽い銊ワ躬瀵鏁嶉崟銊ヤ壕闁挎繂绨肩花濠氭煛閸℃瑥鏋庨棁澶愭煟濞嗗繑鍣介柣锝囨暩閳ь剚顔栭崰妤呭箰閹惰棄绠栭柍鍝勬媼閺佸﹪鏌ゆ慨鎰偓婵嬪煘韫囨稒鈷?*/
    private String translateSide(String side) {
        if ("BUY".equalsIgnoreCase(side)) {
            return "做多";
        }
        if ("SELL".equalsIgnoreCase(side)) {
            return "做空";
        }
        return s(side);
    }

    /** 闂傚倸鍊峰ù鍥敋瑜忛幑銏ゅ箛椤旇棄搴婇梺褰掑亰閸庨潧鈽夊Ο婊勬瀹曘劑顢欓幆褍绫嶉梻鍌欑閸熷潡骞栭锕€纾瑰┑鐘冲搸閳ь剙鎳橀幃婊堟嚍閵壯冨箥闂備浇顕栭崹搴ㄥ礃閳哄倻妲梻鍌欑閹碱偊宕愰幋锕€鐐婇柕濞у啫绠為梻鍌欑窔濞佳囨晬韫囨稑绀冮柛鎰劤婢ь垳绱掔紒妯兼创鐎殿喖鐖奸獮瀣倻閸℃﹫绱楀┑掳鍊楁慨鐑藉磻閻愬搫鍨傞柛褎顨嗛弲鏌ユ煟閹邦亣顒熼柡浣告喘閺屻劌鈽夊Ο渚紝濠碘剝褰冪紞濠傤潖缂佹ɑ濯村〒姘煎灡閺侇垳绱撻崒姘卞闁告鍟块悾鐑藉即閵忥紕鍔堕悗骞垮劚閹虫劙鎮块崨瀛樷拺闁哄倶鍎插▍鍛存煕閻旇泛宓嗛柛鈹垮灲瀵噣鍩€椤掑嫬桅闁告洦鍨扮粻濠氭偣閾忚纾柨婵嗩槹閻撶喖鏌ㄥ┑鍡樺櫣闁肩缍婇弻宥夋寠婢舵ɑ鈻堝Δ鐘靛仦閻熲晛鐣峰鈧俊鎼佸Ψ瑜滈崯鍫ユ⒒閸屾瑧顦﹂柟纰卞亰椤㈡牠宕ㄩ弶鎴犳焾闂佺粯顨呴悧蹇涖€呴悜鑺ョ叆闁哄洨鍋涢埀顒佹倐瀹曪繝骞庨懞銉у幗濠殿喗顨呭Λ妤呭几濞嗘垹纾兼い鏃傚亾閺嗩剚鎱?*/
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
        if ("launch_blocked_by_ma10_trend".equalsIgnoreCase(reason)) {
            return "补开仓被MA10趋势过滤";
        }
        if ("launch_blocked_by_ma10_trend".equalsIgnoreCase(reason)) {
            return "补开仓被MA10趋势过滤";
        }
        return s(reason);
    }

    /** 闂傚倸鍊峰ù鍥敋瑜忛幑銏ゅ箛椤旇棄搴婇梺褰掑亰閸庨潧鈽夐姀鐘电潉闂侀€炲苯澧扮紒顔界濞煎繘濡歌閻﹀牓姊洪幖鐐插妧闁告侗鍨伴獮鈧梻鍌氬€烽悞锕傛儑瑜版帒纾归柕鍫濐槸閸ㄥ倻鈧娲栧ú銊︾▔瀹ュ鐓涚€广儱鍟俊璺ㄧ磼閳锯偓閸嬫挸鈹戦悩鍨毄闁稿鐩幃妯衡攽鐎ｎ亞鍔﹀銈嗗笂閻掞妇绮堥崼銉︾厸濞撴艾娲ゅ▍宥嗐亜閵忊剝绀嬮柡浣稿暣閸┾偓妞ゆ帒瀚崐宄扳攽閻樺弶澶勯柣鎾跺枑娣囧﹪顢涘鍐ㄤ粯闂佸憡鏌ㄩ鍥╂閹烘挾鐟归柛銉戝嫮褰庨梻浣告惈閻ジ宕伴幘璇茬闁绘顕ч悞娲煕閹板吀绨介柣娑栧灲濮婃椽鎳￠妶鍛咃綁鏌涢弮鈧〃濠傜暦娴兼潙鍐€妞ゆ挾濮寸粊锕傛煙閸忚偐鏆橀柛鏂胯嫰閺侇噣姊绘笟鈧褔篓閳ь剛绱掗悩鍐茬伌鐎殿喗濞婂畷濂告偄閾忚鍟庨梻浣烘嚀椤曨參宕戦悙鍝勭畾闁告洦鍨遍悡鏇熶繆椤栨繍鍤欓柛鏂诲€濋弻宥囩磼濡纾冲Δ鐘靛仜椤戝骞冨▎鎴濇瀳婵☆垵宕垫禍娆忊攽閿涘嫬浜奸柛濠冪墵楠炴劖銈ｉ崘銊х崶闂佽澹嗘晶妤呭磹閸偁浜滈柡鍥殔娴滈箖姊虹拠鈥虫灍闁挎洏鍨介獮鍐ㄢ枎閹存柨浜炬繛鎴炵懐閻掕姤銇勯敂璇插籍婵﹥妞藉Λ鍐ㄢ槈濞嗗浚妲遍柣搴″帨閸嬫捇鏌涘☉姗堟缂?*/
    /**
     * \u5c06\u5f00\u4ed3\u539f\u56e0\u8f6c\u6362\u4e3a\u62a5\u544a\u4e2d\u7684\u4e2d\u6587\u5c55\u793a\u3002
     */
    private String translateEntryReason(String reason) {
        if ("dif_dea_cross_up".equalsIgnoreCase(reason)) {
            return "DIF/DEA\u91d1\u53c9\u5f00\u591a";
        }
        if ("dif_dea_cross_down".equalsIgnoreCase(reason)) {
            return "DIF/DEA\u6b7b\u53c9\u5f00\u7a7a";
        }
        if ("launch_entry_after_macd_expand".equalsIgnoreCase(reason)) {
            return "MACD\u653e\u5927\u540e\u8865\u5f00\u4ed3";
        }
        return s(reason);
    }

    private String translateRejectReason(String reason) {
        if ("unsupported_text".equalsIgnoreCase(reason)) {
            return "不支持的K线周期";
        }
        if ("not_enough_samples".equalsIgnoreCase(reason) || "not_enough_bars".equalsIgnoreCase(reason)) {
            return "样本不足";
        }
        if ("duplicate_bar".equalsIgnoreCase(reason)) {
            return "重复K线";
        }
        if ("long_active_no_exit".equalsIgnoreCase(reason)) {
            return "多头持仓中";
        }
        if ("short_active_no_exit".equalsIgnoreCase(reason)) {
            return "空头持仓中";
        }
        if ("entry_blocked_by_dif_dea_bonding".equalsIgnoreCase(reason)) {
            return "DIF/DEA粘合过滤";
        }
        if ("entry_blocked_by_cross_density".equalsIgnoreCase(reason)) {
            return "交叉密度过滤";
        }
        if ("entry_blocked_by_recent_launch_failure".equalsIgnoreCase(reason)) {
            return "近期趋势连续未启动，跳过一次逆势入场";
        }
        if ("launch_recovery_first_confirmed".equalsIgnoreCase(reason)) {
            return "连续失败后等待第二根确认";
        }
        if ("launch_recovery_not_confirmed".equalsIgnoreCase(reason)) {
            return "恢复期第二根未延续";
        }
        if ("launch_recovery_breakout_too_shallow".equalsIgnoreCase(reason)) {
            return "恢复期第二根真实突破强度不足";
        }
        if ("launch_recovery_blocked_by_large_bar".equalsIgnoreCase(reason)) {
            return "恢复期第二根确认K线波幅过大";
        }
        if ("weak_countertrend_first_confirmed".equalsIgnoreCase(reason)) {
            return "\u8f7b\u5ea6\u9006\u52bf\u7a81\u7834\u5931\u771f\uff0c\u7b49\u5f85\u7b2c\u4e8c\u6839\u786e\u8ba4";
        }
        if ("weak_countertrend_not_confirmed".equalsIgnoreCase(reason)) {
            return "\u8f7b\u5ea6\u9006\u52bf\u7b2c\u4e8c\u6839\u672a\u5ef6\u7eed";
        }
        if ("weak_countertrend_blocked_by_large_bar".equalsIgnoreCase(reason)) {
            return "\u8f7b\u5ea6\u9006\u52bf\u7b2c\u4e8c\u6839\u786e\u8ba4K\u7ebf\u6ce2\u5e45\u8fc7\u5927";
        }
        if ("extreme_countertrend_stop_synced".equalsIgnoreCase(reason)) {
            return "强趋势逆势硬止损状态同步";
        }
        if ("moderate_countertrend_stop_synced".equalsIgnoreCase(reason)) {
            return "中度逆MA20趋势保护止损状态同步";
        }
        if ("flat_ma20_stop_synced".equalsIgnoreCase(reason)) {
            return "MA20走平专属止损状态同步";
        }
        if ("emergency_stop_synced".equalsIgnoreCase(reason)) {
            return "\u5168\u5c401%\u707e\u96be\u6b62\u635f\u72b6\u6001\u540c\u6b65";
        }
        if ("entry_blocked_by_5m_ma20_distance_too_close".equalsIgnoreCase(reason)) {
            return "价格距离5M MA20过近，交叉趋势强度不足";
        }
        if ("entry_blocked_by_5m_ma20_overextended".equalsIgnoreCase(reason)) {
            return "价格距离5M MA20过远，避免趋势尾端追入";
        }
        if ("entry_blocked_by_steep_ma20_weak_macd".equalsIgnoreCase(reason)) {
            return "MA20趋势过陡但交叉MACD动能不足";
        }
        if ("entry_blocked_by_ma20_trend".equalsIgnoreCase(reason)) {
            return "交叉确认与MA20趋势反向";
        }
        if ("entry_blocked_by_counter_trend".equalsIgnoreCase(reason)) {
            return "最近K线反向走势过滤";
        }
        if ("entry_blocked_by_ma_counter_trend".equalsIgnoreCase(reason)) {
            return "高一级均线趋势未翻转";
        }
        if ("entry_blocked_by_large_bar_range".equalsIgnoreCase(reason)) {
            return "单根K线大波幅过滤";
        }
        if ("entry_blocked_by_whipsaw_cooldown".equalsIgnoreCase(reason)) {
            return "短持仓反复交叉冷却中";
        }
        if ("entry_pending_not_confirmed".equalsIgnoreCase(reason)) {
            return "交叉入场下一根未确认";
        }
        if ("entry_pending_breakout_too_shallow".equalsIgnoreCase(reason)) {
            return "交叉入场相对突破强度不足";
        }
        if ("entry_confirmation_overextended".equalsIgnoreCase(reason)) {
            return "交叉确认K线过度延伸";
        }
        if ("entry_confirmation_extreme_countertrend_overextended".equalsIgnoreCase(reason)) {
            return "极端逆势确认K线过度延伸";
        }
        if ("entry_confirmation_extreme_countertrend_structure_missing".equalsIgnoreCase(reason)) {
            return "极端逆势确认尚未突破MA20";
        }
        if ("early_trend_failure_long".equalsIgnoreCase(reason)) {
            return "多头突破及动能早期失效";
        }
        if ("early_trend_failure_short".equalsIgnoreCase(reason)) {
            return "空头突破及动能早期失效";
        }
        if ("trend_fifth_bar_failure_long".equalsIgnoreCase(reason)) {
            return "多头第5-7根未启动且动能衰减";
        }
        if ("trend_fifth_bar_failure_short".equalsIgnoreCase(reason)) {
            return "空头第5-7根未启动且动能衰减";
        }
        if ("trend_not_launched_long".equalsIgnoreCase(reason)) {
            return "多头入场八根仍未启动";
        }
        if ("trend_not_launched_short".equalsIgnoreCase(reason)) {
            return "空头入场八根仍未启动";
        }
        if ("trend_zero_progress_long".equalsIgnoreCase(reason)) {
            return "多头入场八根几乎无推进";
        }
        if ("trend_zero_progress_short".equalsIgnoreCase(reason)) {
            return "空头入场八根几乎无推进";
        }
        if ("trend_checkpoint_giveback_long".equalsIgnoreCase(reason)) {
            return "多头第8-12根有效浮盈全部回吐";
        }
        if ("trend_checkpoint_giveback_short".equalsIgnoreCase(reason)) {
            return "空头第8-12根有效浮盈全部回吐";
        }
        if ("early_profit_round_trip_long".equalsIgnoreCase(reason)) {
            return "多头前8根浮盈全部回吐";
        }
        if ("early_profit_round_trip_short".equalsIgnoreCase(reason)) {
            return "空头前8根浮盈全部回吐";
        }
        if ("weak_mature_profit_giveback_long".equalsIgnoreCase(reason)) {
            return "多头第9根后小趋势浮盈基本回吐";
        }
        if ("weak_mature_profit_giveback_short".equalsIgnoreCase(reason)) {
            return "空头第9根后小趋势浮盈基本回吐";
        }
        if ("mature_profit_giveback_long".equalsIgnoreCase(reason)) {
            return "多头成熟趋势浮盈衰减回吐";
        }
        if ("mature_profit_giveback_short".equalsIgnoreCase(reason)) {
            return "空头成熟趋势浮盈衰减回吐";
        }
        if ("profit_extension_started_long".equalsIgnoreCase(reason)) {
            return "多头成熟盈利弱反向延迟退出";
        }
        if ("profit_extension_started_short".equalsIgnoreCase(reason)) {
            return "空头成熟盈利弱反向延迟退出";
        }
        if ("profit_extension_reversal_confirmed_long".equalsIgnoreCase(reason)) {
            return "多头盈利延续结构反转确认";
        }
        if ("profit_extension_reversal_confirmed_short".equalsIgnoreCase(reason)) {
            return "空头盈利延续结构反转确认";
        }
        if ("profit_extension_floor_long".equalsIgnoreCase(reason)) {
            return "多头盈利延续触及利润保护线";
        }
        if ("profit_extension_floor_short".equalsIgnoreCase(reason)) {
            return "空头盈利延续触及利润保护线";
        }
        if ("reverse_pending_started".equalsIgnoreCase(reason)) {
            return "反手入场等待下一根确认";
        }
        if ("reverse_entry_blocked_by_ma20_trend".equalsIgnoreCase(reason)) {
            return "反手确认与MA20趋势反向";
        }
        if ("reverse_pending_not_confirmed".equalsIgnoreCase(reason)) {
            return "反手入场下一根未确认";
        }
        if ("reverse_pending_breakout_too_shallow".equalsIgnoreCase(reason)) {
            return "反手入场相对突破强度不足";
        }
        if ("reverse_confirmation_overextended".equalsIgnoreCase(reason)) {
            return "反手确认K线过度延伸";
        }
        if ("entry_blocked_by_macd_spike_reversal".equalsIgnoreCase(reason)) {
            return "MACD\u7a81\u53d1\u53cd\u62bd\u53cd\u6740\u8fc7\u6ee4";
        }
        if ("entry_blocked_by_surge_cross".equalsIgnoreCase(reason)) {
            return "突发拉升下杀交叉过滤";
        }
        if ("launch_blocked_by_large_bar_range".equalsIgnoreCase(reason)) {
            return "补开仓单根K线大波幅过滤";
        }
        if ("entry_blocked_by_low_macd".equalsIgnoreCase(reason)) {
            return "低MACD动能不足不开仓";
        }
        if ("launch_entry_after_macd_expand".equalsIgnoreCase(reason)) {
            return "MACD放大后补开仓";
        }
        if ("reverse_cross_blocked_by_dif_dea_bonding".equalsIgnoreCase(reason)) {
            return "反向交叉被粘合过滤";
        }
        if ("no_cross".equalsIgnoreCase(reason)) {
            return "无交叉";
        }
        if ("invalid_atr".equalsIgnoreCase(reason)) {
            return "ATR无效";
        }
        if ("slope_filter".equalsIgnoreCase(reason)) {
            return "斜率过滤";
        }
        if ("no_channel_touch".equalsIgnoreCase(reason)) {
            return "未触及通道";
        }
        if ("no_pending_state".equalsIgnoreCase(reason)) {
            return "无观察状态";
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
            return "Range Filter";
        }
        if ("invalid_range".equalsIgnoreCase(reason)) {
            return "Invalid Range";
        }
        if ("drift_block_buy".equalsIgnoreCase(reason)) {
            return "Drift Block Buy";
        }
        if ("drift_block_sell".equalsIgnoreCase(reason)) {
            return "Drift Block Sell";
        }
        if ("no_reversal_confirm".equalsIgnoreCase(reason)) {
            return "No Reversal Confirm";
        }
        if ("invalid_std".equalsIgnoreCase(reason)) {
            return "Invalid Std";
        }
        if ("width_filter".equalsIgnoreCase(reason)) {
            return "Width Filter";
        }
        if ("no_pullback_trigger".equalsIgnoreCase(reason)) {
            return "No Pullback Trigger";
        }
        if ("launch_blocked_by_ma10_trend".equalsIgnoreCase(reason)) {
            return "补开仓被MA10趋势过滤";
        }
        return s(reason);
    }

    /** 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘骞戦姀銈呯疀妞ゆ棁妫勬惔濠囨⒑瑜版帒浜伴柛搴ㄦ涧閳藉螣濠婂嫭顥堢€规洏鍔戦、娆撴⒒鐎靛摜澶勬繝纰夌磿閸嬫垿宕愰弽顓炵鐟滃繒鍒掓繝姘闁绘﹢娼ч弳妤呮倵楠炲灝鍔氭い锔垮嵆瀹曟垿鏁愭径瀣幈濠电娀娼уΛ妤咁敂椤忓牊鐓欐い鏍ㄦ皑婢э附鎱ㄦ繝鍌ょ吋鐎规洏鍔戦、姘跺幢濮橈絽浜鹃柛褎顨嗛悡娑氣偓鍏夊亾閻庯綆鍓涜ⅵ濠电姷顣介崜婵嬪箖閸屾稐绻嗛柣鎴ｆ鍞銈嗘瀹曢潧螞椤栨埃鏀介柣妯活問閺嗘粎绱掓潏銊︾鐎规洘鍨甸埥澶愬閻樼绱梻浣稿閻撳牓宕抽纰辩劷闁哄稁鍘介悡鐘测攽椤旇棄濮囬柍褜鍓氶崝娆忕暦閹达箑绠婚柡鍌樺劜椤秴鈹戦悙鍙夘棡妞ゎ厼娲畷婵嬫煥鐎ｎ剛鐦堥梺闈涢獜缂嶅棗顭囬幇鐗堢厱闁哄啠鍋撴い銊ワ躬瀹曟椽鍩€椤掍降浜滈柟鍝勭Ф鐠愪即鏌涢悢椋庣闁哄本鐩幃鈺佺暦閸パ€鎷￠梻浣烘嚀缁犲秹宕规禒瀣祦闁搞儺鍓﹂弫濠囨煠閹帒鍔滄い鏂挎喘濮婄粯鎷呯憴鍕哗闂佺瀵掗崳锝咁嚕閹绘巻鏀介柛顐ｇ箥濡粍绻涚€电孝妞ゆ垵妫濋幃锟犲礃椤旇棄浠╁┑鐐村灦瀹稿宕戦幘璇茬闁告侗鍙庡Λ婊堟⒒閸屾瑦绁扮€规洖鐏氶幈銊╂偨缁嬭法顦┑掳鍊曢幊搴ｇ玻濡ゅ懏鐓涚€广儱楠搁獮妯尖偓瑙勬尫缁舵岸鐛弽顬ュ酣顢楅埀顒佷繆婵傜鑸规い鏍仦閳锋垿姊婚崼鐔剁繁婵＄嫏鍛＜闁绘ê鍟块悘瀵糕偓娈垮枟婵炲﹤鐣锋總鍛婂亜闁惧繗顕栭崯搴ㄦ⒒娴ｇ儤鍤€妞ゆ洦鍙冨畷鎴︽倷閸濆嫮鐣鹃梺鍛婃处閸ㄩ亶鎮￠弴鐔剁箚妞ゆ牗绻嶉崵娆撴煕閺傝鈧繈寮诲鍥╃＜婵☆垵顕х壕鍐参旈悩闈涗粶妞ゆ垵顦靛顐﹀礃椤旇偐鍔﹀銈嗗笒閸婄敻宕戦幘鎰佹僵妞ゆ帒顦版晥闂備線娼уú銈団偓姘嵆閻涱噣骞掑Δ鈧獮銏′繆閻愭潙鍔ゆい銉﹁壘閳规垿鎮╅崹顐ｆ瘎婵犳鍣崣鍐ㄧ暦閹达箑绠荤紓浣诡焽閸樼數绱撻崒娆撴闁搞劌缍婂绋库槈濮樿京锛滃銈嗗姂閸ㄧ粯鏅ラ梻浣告惈閺堫剟鎯勯鐐偓渚€寮撮姀鈩冩珳闂佺硶鍓濋悷顖毼ｆ导瀛樷拻濞达絿鎳撻婊呯磼鐎ｎ偅宕岀€规洑鍗冲浠嬵敇閻愮數鏆繝寰锋澘鈧劙宕戦幘缁樼厓闁芥ê顦藉Σ鎼佹煃鐠囨煡顎楅摶锝夋煠婵劕鈧牕袙婢跺ň鏀介柨娑樺娴滃ジ鏌涙繝鍐⒌鐎规洖缍婇獮搴ㄦ嚍閵夈儮鍋撻崸妤佺叆闁哄倸鐏濋埛鏃堟煟椤撶喓鎳冩い顓℃硶閹瑰嫰宕崟鍨唲婵犵數鍋涢惌鍫熺椤忓牆钃熸繛鎴炵煯濞岊亞绱撴担闈涚仾闁伙綀鍩栫换?*/
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


    /**
     * 按入场原因输出盈利和亏损的开仓方式统计表。
     */
    private void appendEntryReasonStats(StringBuilder sb, BacktestResult result) {
        List<TradeRecord> tradeList = result.tradeList == null ? Collections.<TradeRecord>emptyList() : result.tradeList;
        Map<String, EntryReasonStat> profitStats = buildEntryReasonStats(tradeList, true);
        Map<String, EntryReasonStat> lossStats = buildEntryReasonStats(tradeList, false);

        sb.append("### \u76c8\u5229\u5f00\u4ed3\u65b9\u5f0f\u7edf\u8ba1").append("\n\n");
        appendEntryReasonStatTable(sb, profitStats);
        sb.append("\n");

        sb.append("### \u4e8f\u635f\u5f00\u4ed3\u65b9\u5f0f\u7edf\u8ba1").append("\n\n");
        appendEntryReasonStatTable(sb, lossStats);
        sb.append("\n");
    }

    /**
     * 按入场原因聚合盈利或亏损交易。
     */
    private Map<String, EntryReasonStat> buildEntryReasonStats(List<TradeRecord> tradeList, boolean profit) {
        Map<String, EntryReasonStat> stats = new LinkedHashMap<>();
        for (TradeRecord trade : tradeList) {
            BigDecimal pnl = trade.pnl == null ? BigDecimal.ZERO : trade.pnl;
            int sign = pnl.compareTo(BigDecimal.ZERO);
            if (profit ? sign <= 0 : sign >= 0) {
                continue;
            }
            String reason = translateEntryReason(trade.entryReason);
            EntryReasonStat stat = stats.computeIfAbsent(reason, EntryReasonStat::new);
            stat.count++;
            stat.totalPnl = stat.totalPnl.add(pnl);
            stat.totalHoldBars += trade.holdBars == null ? 0 : trade.holdBars;
        }
        return stats;
    }

    /**
     * 输出入场原因统计表格。
     */
    private void appendEntryReasonStatTable(StringBuilder sb, Map<String, EntryReasonStat> stats) {
        List<String> headers = new ArrayList<>();
        headers.add("\u5f00\u4ed3\u65b9\u5f0f");
        headers.add("\u7b14\u6570");
        headers.add("\u603b\u6536\u76ca");
        headers.add("\u5e73\u5747\u6536\u76ca");
        headers.add("\u5e73\u5747\u6301\u4ed3K\u7ebf\u6570");

        List<List<String>> rows = new ArrayList<>();
        for (EntryReasonStat stat : stats.values()) {
            List<String> row = new ArrayList<>();
            row.add(s(stat.reason));
            row.add(i(stat.count));
            row.add(n(stat.totalPnl));
            row.add(n(stat.averagePnl()));
            row.add(n(stat.averageHoldBars()));
            rows.add(row);
        }
        appendAlignedTable(sb, headers, rows);
    }

    /**
     * 保存单个入场原因的聚合统计结果。
     */
    private static class EntryReasonStat {
        private final String reason;
        private int count;
        private BigDecimal totalPnl = BigDecimal.ZERO;
        private int totalHoldBars;

        /**
         * 创建入场原因统计对象。
         */
        private EntryReasonStat(String reason) {
            this.reason = reason;
        }

        /**
         * 计算平均收益。
         */
        private BigDecimal averagePnl() {
            if (count <= 0) {
                return BigDecimal.ZERO;
            }
            return totalPnl.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
        }

        /**
         * 计算平均持仓K线数量。
         */
        private BigDecimal averageHoldBars() {
            if (count <= 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(totalHoldBars)
                    .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
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
