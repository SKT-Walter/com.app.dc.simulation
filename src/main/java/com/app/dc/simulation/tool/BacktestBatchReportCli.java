package com.app.dc.simulation.tool;

import com.app.common.utils.JsonUtils;
import com.app.dc.pipeline.StrategyPipelineService;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BacktestBatchReportCli {

    private static final String DEFAULT_DBPOOL_CFG = "./config/DBPoolConfig.ini";
    private static final String DEFAULT_CLICKHOUSE_SOURCE = "ClickHouse1";
    private static final String DEFAULT_REPORT_DIR = "./log/backtest-batch-reports";

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        options.validate();
        DbConfig dbConfig = DbConfig.load(options.dbpoolCfg, options.dbSourceName);
        try (Connection connection = DriverManager.getConnection(dbConfig.url, dbConfig.username, dbConfig.password)) {
            BatchReport report = buildReport(connection, options.batchTag);
            writeReport(report, options);
        }
    }

    private static BatchReport buildReport(Connection connection, String batchTag) throws Exception {
        BatchReport report = new BatchReport();
        report.batchTag = batchTag;
        report.generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        report.items = loadItems(connection, batchTag);
        for (BatchItem item : report.items) {
            report.total++;
            if (item.active) {
                report.activeCount++;
            } else {
                report.offlineCount++;
            }
            if ("SUCCESS".equalsIgnoreCase(item.backtestStatus)) {
                report.backtestSuccessCount++;
            } else if ("RUNNING".equalsIgnoreCase(item.backtestStatus) || "PENDING".equalsIgnoreCase(item.backtestStatus)) {
                report.pendingCount++;
            } else if ("FAILED".equalsIgnoreCase(item.backtestStatus) || "SUSPENDED".equalsIgnoreCase(item.backtestStatus)) {
                report.failedCount++;
            }
        }
        return report;
    }

    private static List<BatchItem> loadItems(Connection connection, String batchTag) throws Exception {
        List<BatchItem> items = new ArrayList<BatchItem>();
        String sql = "select id, strategy_name, strategy_version, parent_version, scene, runtime_type, description, payload "
                + "from dc.strategy_candidate where generation_type='LIVE_BASELINE_MIGRATION' "
                + "and payload like ? order by strategy_name asc, create_time desc";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%\"batchTag\":\"" + batchTag + "\"%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BatchItem item = new BatchItem();
                    item.candidateId = rs.getString("id");
                    item.strategyName = rs.getString("strategy_name");
                    item.strategyVersion = rs.getString("strategy_version");
                    item.parentVersion = rs.getString("parent_version");
                    item.scene = rs.getString("scene");
                    item.runtimeType = rs.getString("runtime_type");
                    item.description = rs.getString("description");
                    item.payload = rs.getString("payload");
                    item.pipelineRunId = extractPipelineRunId(item.payload);
                    loadBacktest(connection, item);
                    loadActive(connection, item);
                    loadRelease(connection, item);
                    item.reason = resolveReason(item);
                    items.add(item);
                }
            }
        }
        return items;
    }

    private static void loadBacktest(Connection connection, BatchItem item) throws Exception {
        String sql = "select strategy_name, strategy_version, symbol, text, trade_count, "
                + "fit_pnl, validate_pnl, forward_pnl, total_pnl, forward_score, overfit_pass, overfit_reason, "
                + "optimization_mode, trial_count, best_param_set, best_rank, report_path, toString(run_time) as run_time "
                + "from dc.backtest_result where lower(strategy_name)=lower(?) and lower(strategy_version)=lower(?) "
                + "order by run_time desc limit 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item.strategyName);
            ps.setString(2, item.strategyVersion);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    item.backtestStatus = "PENDING";
                    return;
                }
                item.backtestStatus = "SUCCESS";
                item.symbol = rs.getString("symbol");
                item.text = rs.getString("text");
                item.tradeCount = rs.getInt("trade_count");
                item.fitPnl = rs.getDouble("fit_pnl");
                item.validatePnl = rs.getDouble("validate_pnl");
                item.forwardPnl = rs.getDouble("forward_pnl");
                item.totalPnl = rs.getDouble("total_pnl");
                item.forwardScore = rs.getDouble("forward_score");
                item.overfitPass = rs.getInt("overfit_pass");
                item.overfitReason = rs.getString("overfit_reason");
                item.optimizationMode = rs.getString("optimization_mode");
                item.trialCount = rs.getInt("trial_count");
                item.bestParamSet = rs.getString("best_param_set");
                item.bestRank = rs.getInt("best_rank");
                item.reportPath = rs.getString("report_path");
                item.runTime = rs.getString("run_time");
            }
        }
    }

    private static void loadActive(Connection connection, BatchItem item) throws Exception {
        String sql = "select toString(effective_time) as effective_time from dc.strategy_live_registry "
                + "where lower(strategy_name)=lower(?) and lower(strategy_version)=lower(?) and status='ACTIVE' "
                + "order by effective_time desc limit 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item.strategyName);
            ps.setString(2, item.strategyVersion);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    item.active = true;
                    item.activeEffectiveTime = rs.getString("effective_time");
                }
            }
        }
    }

    private static void loadRelease(Connection connection, BatchItem item) throws Exception {
        String sql = "select event_type, reason, toString(event_time) as event_time from dc.strategy_release_event "
                + "where lower(strategy_name)=lower(?) and lower(to_version)=lower(?) "
                + "order by event_time desc limit 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item.strategyName);
            ps.setString(2, item.strategyVersion);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    item.releaseEventType = rs.getString("event_type");
                    item.releaseReason = rs.getString("reason");
                    item.releaseEventTime = rs.getString("event_time");
                }
            }
        }
    }

    private static String resolveReason(BatchItem item) {
        if (item.active) {
            return "已重新上线";
        }
        if (!isBlank(item.releaseReason)) {
            return item.releaseReason;
        }
        if (!"SUCCESS".equalsIgnoreCase(item.backtestStatus)) {
            return "回测未完成";
        }
        if (item.overfitPass <= 0) {
            return isBlank(item.overfitReason) ? "未通过过拟合检查" : item.overfitReason;
        }
        if (item.validatePnl <= 0D) {
            return "validate_pnl <= 0";
        }
        if (item.forwardPnl <= 0D) {
            return "forward_pnl <= 0";
        }
        if (item.totalPnl <= 0D) {
            return "total_pnl <= 0";
        }
        if (item.forwardScore <= 0D) {
            return "forward_score <= 0";
        }
        return "未进入实盘";
    }

    private static String extractPipelineRunId(String payload) {
        if (isBlank(payload)) {
            return "";
        }
        try {
            Map<String, Object> map = JsonUtils.Deserialize(payload, Map.class);
            if (map == null) {
                return "";
            }
            Object value = map.get(StrategyPipelineService.PIPELINE_RUN_ID);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignore) {
            return "";
        }
    }

    private static void writeReport(BatchReport report, CliOptions options) throws Exception {
        java.io.File dir = new java.io.File(options.reportDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        java.io.File jsonFile = new java.io.File(dir, "backtest-batch-report-" + report.batchTag + ".json");
        java.io.File mdFile = new java.io.File(dir, "backtest-batch-report-" + report.batchTag + ".md");
        java.io.File htmlFile = new java.io.File(dir, "backtest-batch-report-" + report.batchTag + ".html");
        try (FileWriter writer = new FileWriter(jsonFile)) {
            writer.write(JsonUtils.Serializer(report.toMap()));
        }
        try (FileWriter writer = new FileWriter(mdFile)) {
            writer.write(report.toMarkdown());
        }
        try (FileWriter writer = new FileWriter(htmlFile)) {
            writer.write(report.toHtml());
        }
        System.out.println("batch report written, json=" + jsonFile.getAbsolutePath());
        System.out.println("batch report written, md=" + mdFile.getAbsolutePath());
        System.out.println("batch report written, html=" + htmlFile.getAbsolutePath());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String esc(String value) {
        return safe(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String fmt(double value) {
        return String.format(Locale.ENGLISH, "%.6f", value);
    }

    private static class CliOptions {
        private String dbpoolCfg = DEFAULT_DBPOOL_CFG;
        private String dbSourceName = DEFAULT_CLICKHOUSE_SOURCE;
        private String reportDir = DEFAULT_REPORT_DIR;
        private String batchTag;

        static CliOptions parse(String[] args) {
            CliOptions options = new CliOptions();
            if (args == null) {
                return options;
            }
            for (String arg : args) {
                if (arg == null || !arg.startsWith("--")) {
                    continue;
                }
                int idx = arg.indexOf('=');
                String name = idx > 0 ? arg.substring(2, idx) : arg.substring(2);
                String value = idx > 0 ? arg.substring(idx + 1) : "";
                if ("dbpoolCfg".equalsIgnoreCase(name)) {
                    options.dbpoolCfg = value;
                } else if ("dbSourceName".equalsIgnoreCase(name)) {
                    options.dbSourceName = value;
                } else if ("reportDir".equalsIgnoreCase(name)) {
                    options.reportDir = value;
                } else if ("batchTag".equalsIgnoreCase(name)) {
                    options.batchTag = value;
                }
            }
            return options;
        }

        void validate() {
            if (isBlank(batchTag)) {
                throw new IllegalArgumentException("--batchTag is required");
            }
        }
    }

    private static class DbConfig {
        private String url;
        private String username;
        private String password;

        static DbConfig load(String path, String sourceName) throws Exception {
            Map<String, String> values = new LinkedHashMap<String, String>();
            BufferedReader reader = new BufferedReader(new FileReader(path));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                        continue;
                    }
                    int idx = trimmed.indexOf('=');
                    values.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
                }
            } finally {
                reader.close();
            }
            DbConfig cfg = new DbConfig();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey().startsWith("CLICKHOUSE.DBSourceName_")
                        && sourceName.equalsIgnoreCase(entry.getValue())) {
                    String suffix = entry.getKey().substring("CLICKHOUSE.DBSourceName_".length());
                    cfg.url = values.get("CLICKHOUSE.DBUrl_" + suffix);
                    cfg.username = values.get("CLICKHOUSE.DBUsername_" + suffix);
                    cfg.password = values.get("CLICKHOUSE.DBPasswd_" + suffix);
                    break;
                }
            }
            if (cfg.url == null || cfg.username == null) {
                throw new IllegalArgumentException("clickhouse source not found in dbpool config: " + sourceName);
            }
            return cfg;
        }
    }

    private static class BatchReport {
        private String batchTag;
        private String generatedAt;
        private int total;
        private int activeCount;
        private int offlineCount;
        private int backtestSuccessCount;
        private int failedCount;
        private int pendingCount;
        private List<BatchItem> items = new ArrayList<BatchItem>();

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("batchTag", batchTag);
            data.put("generatedAt", generatedAt);
            data.put("total", total);
            data.put("activeCount", activeCount);
            data.put("offlineCount", offlineCount);
            data.put("backtestSuccessCount", backtestSuccessCount);
            data.put("failedCount", failedCount);
            data.put("pendingCount", pendingCount);
            List<Map<String, Object>> detail = new ArrayList<Map<String, Object>>();
            for (BatchItem item : items) {
                detail.add(item.toMap());
            }
            data.put("items", detail);
            return data;
        }

        String toMarkdown() {
            StringBuilder md = new StringBuilder();
            md.append("# 源码 Live 批量回测总报告\n\n");
            md.append("- batchTag: ").append(safe(batchTag)).append("\n");
            md.append("- generatedAt: ").append(safe(generatedAt)).append("\n");
            md.append("- total: ").append(total).append("\n");
            md.append("- activeCount: ").append(activeCount).append("\n");
            md.append("- offlineCount: ").append(offlineCount).append("\n");
            md.append("- backtestSuccessCount: ").append(backtestSuccessCount).append("\n");
            md.append("- failedCount: ").append(failedCount).append("\n");
            md.append("- pendingCount: ").append(pendingCount).append("\n\n");
            md.append("| 策略名 | 版本 | Candidate ID | Pipeline Run ID | 场景 | 状态 | 标的 | 周期 | tradeCount | validatePnl | forwardPnl | totalPnl | bestRank | 发布事件 | 原因 | 报告 |\n");
            md.append("|---|---|---|---|---|---|---|---|---:|---:|---:|---:|---:|---|---|---|\n");
            for (BatchItem item : items) {
                md.append("| ").append(safe(item.strategyName))
                        .append(" | ").append(safe(item.strategyVersion))
                        .append(" | ").append(safe(item.candidateId))
                        .append(" | ").append(safe(item.pipelineRunId))
                        .append(" | ").append(safe(item.scene))
                        .append(" | ").append(item.active ? "ACTIVE" : safe(item.backtestStatus))
                        .append(" | ").append(safe(item.symbol))
                        .append(" | ").append(safe(item.text))
                        .append(" | ").append(item.tradeCount)
                        .append(" | ").append(fmt(item.validatePnl))
                        .append(" | ").append(fmt(item.forwardPnl))
                        .append(" | ").append(fmt(item.totalPnl))
                        .append(" | ").append(item.bestRank)
                        .append(" | ").append(safe(item.releaseEventType))
                        .append(" | ").append(safe(item.reason))
                        .append(" | ").append(safe(item.reportPath))
                        .append(" |\n");
            }
            return md.toString();
        }

        String toHtml() {
            StringBuilder html = new StringBuilder();
            html.append("<html><head><meta charset=\"UTF-8\"><title>源码 Live 批量回测总报告</title>")
                    .append("<style>body{font-family:Segoe UI,Microsoft YaHei,sans-serif;margin:24px;color:#111827;}")
                    .append("table{border-collapse:collapse;width:100%;margin-top:16px;}th,td{border:1px solid #d1d5db;padding:8px 10px;font-size:13px;text-align:left;vertical-align:top;}th{background:#f3f4f6;}")
                    .append(".cards{display:flex;gap:12px;flex-wrap:wrap;margin:12px 0 20px 0;}.card{border:1px solid #d1d5db;border-radius:8px;padding:10px 14px;min-width:160px;}")
                    .append(".label{font-size:12px;color:#6b7280;}.value{font-size:22px;font-weight:700;margin-top:4px;}")
                    .append(".mono{font-family:Consolas,Menlo,monospace;font-size:12px;}</style></head><body>");
            html.append("<h1>源码 Live 批量回测总报告</h1>");
            html.append("<div>batchTag: ").append(esc(batchTag)).append(" / 生成时间: ").append(esc(generatedAt)).append("</div>");
            html.append("<div class='cards'>");
            card(html, "总策略数", total);
            card(html, "已上线", activeCount);
            card(html, "离线", offlineCount);
            card(html, "回测成功", backtestSuccessCount);
            card(html, "失败", failedCount);
            card(html, "待完成", pendingCount);
            html.append("</div>");
            html.append("<table><tr>")
                    .append("<th>策略名</th><th>版本</th><th>Candidate ID</th><th>Pipeline Run ID</th><th>场景</th><th>状态</th>")
                    .append("<th>标的</th><th>周期</th><th>tradeCount</th><th>validatePnl</th><th>forwardPnl</th><th>totalPnl</th>")
                    .append("<th>bestRank</th><th>发布事件</th><th>原因</th><th>报告</th></tr>");
            for (BatchItem item : items) {
                html.append("<tr><td>").append(esc(item.strategyName)).append("</td><td>")
                        .append(esc(item.strategyVersion)).append("</td><td class='mono'>")
                        .append(esc(item.candidateId)).append("</td><td class='mono'>")
                        .append(esc(item.pipelineRunId)).append("</td><td>")
                        .append(esc(item.scene)).append("</td><td>")
                        .append(item.active ? "ACTIVE" : esc(item.backtestStatus)).append("</td><td>")
                        .append(esc(item.symbol)).append("</td><td>")
                        .append(esc(item.text)).append("</td><td>")
                        .append(item.tradeCount).append("</td><td>")
                        .append(fmt(item.validatePnl)).append("</td><td>")
                        .append(fmt(item.forwardPnl)).append("</td><td>")
                        .append(fmt(item.totalPnl)).append("</td><td>")
                        .append(item.bestRank).append("</td><td>")
                        .append(esc(item.releaseEventType)).append("</td><td>")
                        .append(esc(item.reason)).append("</td><td class='mono'>")
                        .append(esc(item.reportPath)).append("</td></tr>");
            }
            html.append("</table></body></html>");
            return html.toString();
        }

        private void card(StringBuilder html, String title, int value) {
            html.append("<div class='card'><div class='label'>").append(esc(title))
                    .append("</div><div class='value'>").append(value).append("</div></div>");
        }
    }

    private static class BatchItem {
        private String candidateId;
        private String pipelineRunId;
        private String strategyName;
        private String strategyVersion;
        private String parentVersion;
        private String scene;
        private String runtimeType;
        private String description;
        private String payload;
        private String symbol;
        private String text;
        private int tradeCount;
        private double fitPnl;
        private double validatePnl;
        private double forwardPnl;
        private double totalPnl;
        private double forwardScore;
        private int overfitPass;
        private String overfitReason;
        private String optimizationMode;
        private int trialCount;
        private String bestParamSet;
        private int bestRank;
        private String reportPath;
        private String runTime;
        private String backtestStatus;
        private boolean active;
        private String activeEffectiveTime;
        private String releaseEventType;
        private String releaseReason;
        private String releaseEventTime;
        private String reason;

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("candidateId", candidateId);
            data.put("pipelineRunId", pipelineRunId);
            data.put("strategyName", strategyName);
            data.put("strategyVersion", strategyVersion);
            data.put("parentVersion", parentVersion);
            data.put("scene", scene);
            data.put("runtimeType", runtimeType);
            data.put("symbol", symbol);
            data.put("text", text);
            data.put("tradeCount", tradeCount);
            data.put("fitPnl", fitPnl);
            data.put("validatePnl", validatePnl);
            data.put("forwardPnl", forwardPnl);
            data.put("totalPnl", totalPnl);
            data.put("forwardScore", forwardScore);
            data.put("overfitPass", overfitPass);
            data.put("overfitReason", overfitReason);
            data.put("optimizationMode", optimizationMode);
            data.put("trialCount", trialCount);
            data.put("bestParamSet", bestParamSet);
            data.put("bestRank", bestRank);
            data.put("reportPath", reportPath);
            data.put("runTime", runTime);
            data.put("backtestStatus", backtestStatus);
            data.put("active", active);
            data.put("activeEffectiveTime", activeEffectiveTime);
            data.put("releaseEventType", releaseEventType);
            data.put("releaseReason", releaseReason);
            data.put("releaseEventTime", releaseEventTime);
            data.put("reason", reason);
            return data;
        }
    }
}
