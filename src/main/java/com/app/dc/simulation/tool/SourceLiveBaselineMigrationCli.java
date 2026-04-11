package com.app.dc.simulation.tool;

import com.app.common.utils.JsonUtils;
import com.app.dc.pipeline.StrategyPipelineService;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SourceLiveBaselineMigrationCli {

    private static final String DEFAULT_DBPOOL_CFG =
            System.getProperty("dbpool.cfg", "./config/DBPoolConfig.ini");
    private static final String DEFAULT_CLICKHOUSE_SOURCE = "ClickHouse1";
    private static final String DEFAULT_REPORT_DIR = "./log/live-baseline-migration";
    private static final DateTimeFormatter FILE_TAG = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String GENERATION_TYPE = "LIVE_BASELINE_MIGRATION";

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        options.validate();
        DbConfig dbConfig = DbConfig.load(options.dbpoolCfg, options.dbSourceName);
        try (Connection connection = DriverManager.getConnection(dbConfig.url, dbConfig.username, dbConfig.password)) {
            BatchSummary summary;
            if ("OFFLINE".equalsIgnoreCase(options.mode)) {
                summary = executeOffline(connection, options);
            } else if ("SEED_OFFLINE".equalsIgnoreCase(options.mode)) {
                summary = executeSeed(connection, options);
                if (summary.failed <= 0) {
                    BatchSummary offline = executeOffline(connection, options);
                    summary.offlined = offline.offlined;
                    summary.offlineSkipped = offline.offlineSkipped;
                    summary.items.addAll(offline.items);
                }
            } else {
                summary = executeSeed(connection, options);
            }
            writeSummary(summary, options);
        }
    }

    private static BatchSummary executeSeed(Connection connection, CliOptions options) throws Exception {
        BatchSummary summary = new BatchSummary();
        summary.mode = "SEED";
        summary.batchTag = options.batchTag;
        List<LiveRow> rows = loadLatestSourceLiveRows(connection, options.strategyNames);
        summary.total = rows.size();
        for (LiveRow row : rows) {
            ItemSummary item = new ItemSummary();
            item.strategyName = row.strategyName;
            item.baselineVersion = row.strategyVersion;
            item.entryClass = row.entryClass;
            try {
                String parametersJson = SourceLiveParameterCatalog.parametersJson(row.strategyName);
                if (parametersJson == null) {
                    item.status = "SKIPPED";
                    item.reason = "parameter catalog missing";
                    summary.skipped++;
                    summary.items.add(item);
                    continue;
                }
                BacktestScope scope = resolveBacktestScope(connection, row);
                if (scope == null || isBlank(scope.symbols) || isBlank(scope.text)) {
                    item.status = "SKIPPED";
                    item.reason = "backtest scope missing";
                    summary.skipped++;
                    summary.items.add(item);
                    continue;
                }
                String newVersion = buildCandidateVersion(row.strategyVersion, options.batchTag);
                String candidateId = buildCandidateId(row.strategyName, newVersion, options.batchTag);
                String taskId = buildTaskId(row.strategyName, newVersion, options.batchTag);
                String payload = buildCandidatePayload(row, scope, options);
                if (!options.dryRun) {
                    insertCandidate(connection, candidateId, row, newVersion, parametersJson, payload);
                    insertTask(connection, taskId, row, newVersion, scope, payload, options);
                }
                item.status = "SEEDED";
                item.strategyVersion = newVersion;
                item.taskId = taskId;
                item.symbols = scope.symbols;
                item.text = scope.text;
                summary.seeded++;
            } catch (Exception e) {
                item.status = "FAILED";
                item.reason = oneLine(e.getMessage());
                summary.failed++;
            }
            summary.items.add(item);
        }
        return summary;
    }

    private static BatchSummary executeOffline(Connection connection, CliOptions options) throws Exception {
        BatchSummary summary = new BatchSummary();
        summary.mode = "OFFLINE";
        summary.batchTag = options.batchTag;
        List<LiveRow> rows = loadLatestSourceLiveRows(connection, options.strategyNames);
        summary.total = rows.size();
        if (!options.dryRun && !rows.isEmpty()) {
            bulkOffline(connection, rows);
        }
        for (LiveRow row : rows) {
            ItemSummary item = new ItemSummary();
            item.strategyName = row.strategyName;
            item.baselineVersion = row.strategyVersion;
            item.entryClass = row.entryClass;
            item.status = "OFFLINED";
            summary.offlined++;
            summary.items.add(item);
        }
        return summary;
    }

    private static List<LiveRow> loadLatestSourceLiveRows(Connection connection, Set<String> strategyNames) throws Exception {
        StringBuilder sql = new StringBuilder();
        sql.append("select ")
                .append("strategy_name, strategy_version, category, scene, runtime_type, symbol_scope, text_scope, ")
                .append("artifact_uri, entry_class, parameters_json, status, effective_time, payload, description ")
                .append("from dc.strategy_live_registry ")
                .append("where lower(entry_class) like 'com.app.dc.signal.live.%' ");
        List<Object> args = new ArrayList<Object>();
        if (strategyNames != null && !strategyNames.isEmpty()) {
            sql.append("and lower(strategy_name) in (");
            int idx = 0;
            for (String name : strategyNames) {
                if (idx > 0) {
                    sql.append(",");
                }
                sql.append("?");
                args.add(name.toLowerCase(Locale.ENGLISH));
                idx++;
            }
            sql.append(") ");
        }
        sql.append("order by strategy_name asc, effective_time desc");
        Map<String, LiveRow> latest = new LinkedHashMap<String, LiveRow>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LiveRow row = new LiveRow();
                    row.strategyName = rs.getString("strategy_name");
                    row.strategyVersion = rs.getString("strategy_version");
                    row.category = rs.getString("category");
                    row.scene = rs.getString("scene");
                    row.runtimeType = rs.getString("runtime_type");
                    row.symbolScope = rs.getString("symbol_scope");
                    row.textScope = rs.getString("text_scope");
                    row.artifactUri = rs.getString("artifact_uri");
                    row.entryClass = rs.getString("entry_class");
                    row.parametersJson = rs.getString("parameters_json");
                    row.status = rs.getString("status");
                    row.effectiveTime = rs.getString("effective_time");
                    row.payload = rs.getString("payload");
                    row.description = rs.getString("description");
                    if (!latest.containsKey(row.strategyName)) {
                        latest.put(row.strategyName, row);
                    }
                }
            }
        }
        return new ArrayList<LiveRow>(latest.values());
    }

    private static BacktestScope resolveBacktestScope(Connection connection, LiveRow row) throws Exception {
        BacktestScope scope = new BacktestScope();
        scope.symbols = normalizeScope(row.symbolScope);
        scope.text = normalizeSingle(row.textScope);
        if (isBlank(scope.text) || "*".equals(scope.text)) {
            scope.text = normalizeSingle(SourceLiveParameterCatalog.defaultText(row.strategyName));
        }
        if (isBlank(scope.symbols) || "*".equals(scope.symbols) || isBlank(scope.text) || "*".equals(scope.text)) {
            LatestBacktestRow latest = loadLatestBacktestRow(connection, row.strategyName, row.strategyVersion);
            if (latest != null) {
                if (isBlank(scope.symbols) || "*".equals(scope.symbols)) {
                    scope.symbols = normalizeScope(latest.symbol);
                }
                if (isBlank(scope.text) || "*".equals(scope.text)) {
                    scope.text = normalizeSingle(latest.text);
                }
            }
        }
        if (isBlank(scope.symbols) || "*".equals(scope.symbols)) {
            scope.symbols = "";
        }
        if (isBlank(scope.text) || "*".equals(scope.text)) {
            scope.text = "";
        }
        return scope;
    }

    private static LatestBacktestRow loadLatestBacktestRow(Connection connection, String strategyName, String strategyVersion) throws Exception {
        String sql = "select symbol, text, run_time from backtest_result "
                + "where lower(strategy_name)=lower(?) and lower(strategy_version)=lower(?) "
                + "order by run_time desc limit 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, strategyName);
            ps.setString(2, strategyVersion);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                LatestBacktestRow row = new LatestBacktestRow();
                row.symbol = rs.getString("symbol");
                row.text = rs.getString("text");
                row.runTime = rs.getString("run_time");
                return row;
            }
        }
    }

    private static void insertCandidate(Connection connection,
                                        String candidateId,
                                        LiveRow row,
                                        String newVersion,
                                        String parametersJson,
                                        String payload) throws Exception {
        String sql = "insert into dc.strategy_candidate "
                + "(id, strategy_name, strategy_version, parent_version, category, scene, runtime_type, "
                + "source_ref, generation_type, logic_summary, java_source_path, artifact_uri, entry_class, "
                + "checksum, description, parameters_json, compile_status, status, create_time, payload) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, candidateId);
            ps.setString(2, safe(row.strategyName));
            ps.setString(3, safe(newVersion));
            ps.setString(4, safe(row.strategyVersion));
            ps.setString(5, safe(defaultIfBlank(row.category, "live")));
            ps.setString(6, safe(row.scene));
            ps.setString(7, safe(defaultIfBlank(row.runtimeType, "CLASSPATH")));
            ps.setString(8, "live:" + safe(row.strategyName) + "@" + safe(row.strategyVersion));
            ps.setString(9, GENERATION_TYPE);
            ps.setString(10, "baseline migration from active source live strategy");
            ps.setString(11, "");
            ps.setString(12, safe(row.artifactUri));
            ps.setString(13, safe(row.entryClass));
            ps.setString(14, "");
            ps.setString(15, safe(row.description));
            ps.setString(16, safe(parametersJson));
            ps.setString(17, "READY");
            ps.setString(18, "READY");
            ps.setTimestamp(19, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(20, safe(payload));
            ps.executeUpdate();
        }
    }

    private static void insertTask(Connection connection,
                                   String taskId,
                                   LiveRow row,
                                   String newVersion,
                                   BacktestScope scope,
                                   String payload,
                                   CliOptions options) throws Exception {
        String sql = "insert into dc.strategy_backtest_task "
                + "(id, strategy_name, strategy_version, baseline_version, runtime_type, task_type, "
                + "fit_window_days, validate_window_days, forward_window_days, priority, status, create_time, update_time, payload) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        Map<String, Object> taskPayload = new LinkedHashMap<String, Object>();
        taskPayload.put("strategyName", row.strategyName);
        taskPayload.put("strategyVersion", newVersion);
        taskPayload.put("baselineVersion", row.strategyVersion);
        taskPayload.put("runtimeType", defaultIfBlank(row.runtimeType, "CLASSPATH"));
        taskPayload.put("scene", row.scene);
        taskPayload.put("sourceType", GENERATION_TYPE);
        taskPayload.put("sourceRef", "live:" + safe(row.strategyName) + "@" + safe(row.strategyVersion));
        taskPayload.put("generationType", GENERATION_TYPE);
        taskPayload.put(StrategyPipelineService.PIPELINE_RUN_ID, buildPipelineRunId(row, options));
        taskPayload.put("strategyPayload", payload);
        taskPayload.put("symbols", scope.symbols);
        taskPayload.put("text", scope.text);
        taskPayload.put("ignoreSentimentGuard", Boolean.TRUE);
        taskPayload.put("allowMissingStageAnalysis", Boolean.TRUE);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, safe(row.strategyName));
            ps.setString(3, safe(newVersion));
            ps.setString(4, safe(row.strategyVersion));
            ps.setString(5, safe(defaultIfBlank(row.runtimeType, "CLASSPATH")));
            ps.setString(6, "FULL");
            ps.setInt(7, options.fitWindowDays);
            ps.setInt(8, options.validateWindowDays);
            ps.setInt(9, options.forwardWindowDays);
            ps.setInt(10, options.priority);
            ps.setString(11, "PENDING");
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            ps.setTimestamp(12, now);
            ps.setTimestamp(13, now);
            ps.setString(14, JsonUtils.Serializer(taskPayload));
            ps.executeUpdate();
        }
    }

    private static void bulkOffline(Connection connection, List<LiveRow> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLE dc.strategy_live_registry UPDATE status='OFFLINE', retire_time=toDateTime('")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()))
                .append("') WHERE status='ACTIVE' and (retire_time is null or retire_time > now()) and lower(strategy_name) in (");
        int idx = 0;
        for (LiveRow row : rows) {
            if (idx > 0) {
                sql.append(",");
            }
            sql.append("'");
            sql.append(escape(row.strategyName == null ? "" : row.strategyName.toLowerCase(Locale.ENGLISH)));
            sql.append("'");
            idx++;
        }
        sql.append(")");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            ps.executeUpdate();
        }
    }

    private static String buildCandidatePayload(LiveRow row, BacktestScope scope, CliOptions options) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("source", "source_live_baseline_migration");
        payload.put("generationType", GENERATION_TYPE);
        payload.put("strategyName", row.strategyName);
        payload.put("baselineVersion", row.strategyVersion);
        payload.put("scene", row.scene);
        payload.put("runtimeType", row.runtimeType);
        payload.put("artifactUri", row.artifactUri);
        payload.put("entryClass", row.entryClass);
        payload.put("description", row.description);
        payload.put("symbolScope", scope.symbols);
        payload.put("textScope", scope.text);
        payload.put("batchTag", options.batchTag);
        payload.put("liveRegistryPayload", row.payload);
        payload.put("liveParametersJson", row.parametersJson);
        payload.put(StrategyPipelineService.PIPELINE_RUN_ID, buildPipelineRunId(row, options));
        return JsonUtils.Serializer(payload);
    }

    private static String buildPipelineRunId(LiveRow row, CliOptions options) {
        return "pipe_LIVE_BASELINE_MIGRATION_"
                + slug(safe(row.strategyName))
                + "_"
                + slug(safe(row.strategyVersion))
                + "_"
                + slug(safe(options.batchTag));
    }

    private static String buildCandidateVersion(String baselineVersion, String batchTag) {
        String base = isBlank(baselineVersion) ? "baseline" : baselineVersion.trim();
        return base + ".opt." + batchTag.replace("_", "");
    }

    private static String buildCandidateId(String strategyName, String version, String batchTag) {
        return "cand_livebaseline_" + slug(strategyName) + "_" + slug(version) + "_" + slug(batchTag);
    }

    private static String buildTaskId(String strategyName, String version, String batchTag) {
        return "bt_livebaseline_" + slug(strategyName) + "_" + slug(version) + "_" + slug(batchTag);
    }

    private static void writeSummary(BatchSummary summary, CliOptions options) throws IOException {
        java.io.File dir = new java.io.File(options.reportDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String fileTag = summary.batchTag == null ? FILE_TAG.format(LocalDateTime.now()) : summary.batchTag;
        java.io.File jsonFile = new java.io.File(dir, "live-baseline-migration-" + fileTag + ".json");
        java.io.File mdFile = new java.io.File(dir, "live-baseline-migration-" + fileTag + ".md");
        try (FileWriter writer = new FileWriter(jsonFile)) {
            writer.write(JsonUtils.Serializer(summary.toMap()));
        }
        try (FileWriter writer = new FileWriter(mdFile)) {
            writer.write(summary.toMarkdown());
        }
        System.out.println("migration summary written, json=" + jsonFile.getAbsolutePath());
        System.out.println("migration summary written, md=" + mdFile.getAbsolutePath());
        System.out.println("mode=" + summary.mode + ", total=" + summary.total + ", seeded=" + summary.seeded
                + ", skipped=" + summary.skipped + ", failed=" + summary.failed + ", offlined=" + summary.offlined);
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws Exception {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }

    private static String normalizeScope(String raw) {
        if (isBlank(raw)) {
            return "";
        }
        String[] parts = raw.split("[,|\\s]+");
        Set<String> result = new LinkedHashSet<String>();
        for (String part : parts) {
            String token = normalizeSingle(part);
            if (!isBlank(token) && !"*".equals(token)) {
                result.add(token);
            }
        }
        if (result.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : result) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(item);
        }
        return sb.toString();
    }

    private static String normalizeSingle(String raw) {
        if (isBlank(raw)) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ENGLISH);
    }

    private static String slug(String raw) {
        if (isBlank(raw)) {
            return "na";
        }
        return raw.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim());
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "''");
    }

    private static class CliOptions {
        private String mode = "SEED";
        private String dbpoolCfg = DEFAULT_DBPOOL_CFG;
        private String dbSourceName = DEFAULT_CLICKHOUSE_SOURCE;
        private String reportDir = DEFAULT_REPORT_DIR;
        private String batchTag = FILE_TAG.format(LocalDateTime.now());
        private Set<String> strategyNames = new LinkedHashSet<String>();
        private int fitWindowDays = 120;
        private int validateWindowDays = 30;
        private int forwardWindowDays = 14;
        private int priority = 3;
        private boolean dryRun = false;

        static CliOptions parse(String[] args) {
            CliOptions options = new CliOptions();
            if (args == null) {
                return options;
            }
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    continue;
                }
                String name = arg.substring(2);
                String value = i + 1 < args.length ? args[i + 1] : "";
                if (value.startsWith("--")) {
                    value = "";
                } else {
                    i++;
                }
                options.apply(name, value);
            }
            return options;
        }

        private void apply(String name, String value) {
            if ("mode".equalsIgnoreCase(name)) {
                mode = defaultIfBlank(value, "SEED").toUpperCase(Locale.ENGLISH);
                return;
            }
            if ("dbpool-cfg".equalsIgnoreCase(name)) {
                dbpoolCfg = defaultIfBlank(value, dbpoolCfg);
                return;
            }
            if ("db-source".equalsIgnoreCase(name)) {
                dbSourceName = defaultIfBlank(value, dbSourceName);
                return;
            }
            if ("report-dir".equalsIgnoreCase(name)) {
                reportDir = defaultIfBlank(value, reportDir);
                return;
            }
            if ("batch-tag".equalsIgnoreCase(name)) {
                batchTag = defaultIfBlank(value, batchTag);
                return;
            }
            if ("strategy-names".equalsIgnoreCase(name)) {
                strategyNames.clear();
                for (String part : value.split(",")) {
                    if (!isBlank(part)) {
                        strategyNames.add(part.trim());
                    }
                }
                return;
            }
            if ("fit-window-days".equalsIgnoreCase(name)) {
                fitWindowDays = Integer.parseInt(value.trim());
                return;
            }
            if ("validate-window-days".equalsIgnoreCase(name)) {
                validateWindowDays = Integer.parseInt(value.trim());
                return;
            }
            if ("forward-window-days".equalsIgnoreCase(name)) {
                forwardWindowDays = Integer.parseInt(value.trim());
                return;
            }
            if ("priority".equalsIgnoreCase(name)) {
                priority = Integer.parseInt(value.trim());
                return;
            }
            if ("dry-run".equalsIgnoreCase(name)) {
                dryRun = Boolean.parseBoolean(defaultIfBlank(value, "true"));
            }
        }

        void validate() {
            if (!Arrays.asList("SEED", "OFFLINE", "SEED_OFFLINE").contains(mode)) {
                throw new IllegalArgumentException("--mode must be SEED, OFFLINE or SEED_OFFLINE");
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

    private static class LiveRow {
        private String strategyName;
        private String strategyVersion;
        private String category;
        private String scene;
        private String runtimeType;
        private String symbolScope;
        private String textScope;
        private String artifactUri;
        private String entryClass;
        private String parametersJson;
        private String status;
        private String effectiveTime;
        private String payload;
        private String description;
    }

    private static class LatestBacktestRow {
        private String symbol;
        private String text;
        private String runTime;
    }

    private static class BacktestScope {
        private String symbols;
        private String text;
    }

    private static class BatchSummary {
        private String mode;
        private String batchTag;
        private int total;
        private int seeded;
        private int skipped;
        private int failed;
        private int offlined;
        private int offlineSkipped;
        private final List<ItemSummary> items = new ArrayList<ItemSummary>();

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("mode", mode);
            data.put("batchTag", batchTag);
            data.put("total", Integer.valueOf(total));
            data.put("seeded", Integer.valueOf(seeded));
            data.put("skipped", Integer.valueOf(skipped));
            data.put("failed", Integer.valueOf(failed));
            data.put("offlined", Integer.valueOf(offlined));
            data.put("offlineSkipped", Integer.valueOf(offlineSkipped));
            List<Map<String, Object>> detail = new ArrayList<Map<String, Object>>();
            for (ItemSummary item : items) {
                detail.add(item.toMap());
            }
            data.put("items", detail);
            return data;
        }

        String toMarkdown() {
            StringBuilder md = new StringBuilder();
            md.append("# Live Baseline Migration Summary\n\n");
            md.append("- mode: ").append(safe(mode)).append("\n");
            md.append("- batchTag: ").append(safe(batchTag)).append("\n");
            md.append("- total: ").append(total).append("\n");
            md.append("- seeded: ").append(seeded).append("\n");
            md.append("- skipped: ").append(skipped).append("\n");
            md.append("- failed: ").append(failed).append("\n");
            md.append("- offlined: ").append(offlined).append("\n\n");
            md.append("| strategy | baselineVersion | newVersion | status | text | symbols | taskId | reason |\n");
            md.append("|---|---|---|---|---|---|---|---|\n");
            for (ItemSummary item : items) {
                md.append("| ").append(safe(item.strategyName))
                        .append(" | ").append(safe(item.baselineVersion))
                        .append(" | ").append(safe(item.strategyVersion))
                        .append(" | ").append(safe(item.status))
                        .append(" | ").append(safe(item.text))
                        .append(" | ").append(safe(item.symbols))
                        .append(" | ").append(safe(item.taskId))
                        .append(" | ").append(safe(item.reason))
                        .append(" |\n");
            }
            return md.toString();
        }
    }

    private static class ItemSummary {
        private String strategyName;
        private String baselineVersion;
        private String strategyVersion;
        private String status;
        private String text;
        private String symbols;
        private String taskId;
        private String entryClass;
        private String reason;

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("strategyName", strategyName);
            data.put("baselineVersion", baselineVersion);
            data.put("strategyVersion", strategyVersion);
            data.put("status", status);
            data.put("text", text);
            data.put("symbols", symbols);
            data.put("taskId", taskId);
            data.put("entryClass", entryClass);
            data.put("reason", reason);
            return data;
        }
    }
}
