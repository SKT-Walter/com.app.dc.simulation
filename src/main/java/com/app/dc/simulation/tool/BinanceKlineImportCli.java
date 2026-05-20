package com.app.dc.simulation.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.annotation.JSONField;
import com.app.common.db.ClickHouseDBUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class BinanceKlineImportCli {

    private static final String DEFAULT_DBPOOL_CFG = "./config/DBPoolConfig.ini";
    private static final String DEFAULT_CLICKHOUSE_SOURCE = "ClickHouse1";
    private static final String DEFAULT_VENUE = "BNFutures";
    private static final String DEFAULT_MODE = "gap-fill";
    private static final String DEFAULT_BASE_URL = "https://fapi.binance.com";
    private static final DateTimeFormatter FMT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_TIME_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int INSERT_BATCH_SIZE = 10000;

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        options.validate();
        DbConfig dbConfig = DbConfig.load(options.dbpoolCfg, options.dbSourceName);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        try (Connection connection = DriverManager.getConnection(dbConfig.url, dbConfig.username, dbConfig.password)) {
            for (String symbol : options.symbols) {
                importSymbol(client, connection, options, symbol);
            }
        }
    }

    private static void importSymbol(OkHttpClient client, Connection connection,
                                     CliOptions options, String symbol) throws Exception {
        long startMs = options.startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long endMs = options.endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 1L;
        long stepMs = intervalMillis(options.interval);
        Set<Long> existing = DEFAULT_MODE.equalsIgnoreCase(options.mode)
                ? loadExistingStartTimes(connection, symbol, options.interval, options.venue, startMs, endMs)
                : new HashSet<Long>();
        if (DEFAULT_MODE.equalsIgnoreCase(options.mode)
                && isRangeAlreadyComplete(existing, startMs, endMs, stepMs)) {
            System.out.println(symbol + " skipped, existing range complete, interval=" + options.interval
                    + ", mode=" + options.mode + ", dryRun=" + options.dryRun);
            return;
        }
        int inserted = 0;
        int skipped = 0;
        int batches = 0;
        int fetchedRows = 0;
        long cursor = startMs;
        System.out.println(symbol + " import start, interval=" + options.interval
                + ", venue=" + options.venue
                + ", startDate=" + options.startDate
                + ", endDate=" + options.endDate
                + ", mode=" + options.mode
                + ", dryRun=" + options.dryRun
                + ", existingCount=" + existing.size());
        while (cursor <= endMs) {
            String url = DEFAULT_BASE_URL + "/fapi/v1/klines?symbol=" + symbol
                    + "&interval=" + options.interval
                    + "&startTime=" + cursor
                    + "&endTime=" + endMs
                    + "&limit=" + options.limitPerCall;
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("binance request failed, symbol=" + symbol + ", code=" + response.code());
                }
                String body = response.body() == null ? "[]" : response.body().string();
                JSONArray rows = JSON.parseArray(body);
                if (rows == null || rows.isEmpty()) {
                    System.out.println(symbol + " import no-more-rows, interval=" + options.interval
                            + ", cursor=" + Instant.ofEpochMilli(cursor).atZone(ZoneOffset.UTC).toLocalDateTime()
                            + ", fetchedRows=" + fetchedRows
                            + ", inserted=" + inserted
                            + ", skipped=" + skipped);
                    break;
                }
                batches++;
                fetchedRows += rows.size();
                Map<String, List<ImportKline>> groupedPending = new LinkedHashMap<String, List<ImportKline>>();
                long nextCursor = cursor;
                for (int i = 0; i < rows.size(); i++) {
                    JSONArray item = rows.getJSONArray(i);
                    long openTime = item.getLongValue(0);
                    long closeTime = item.getLongValue(6);
                    nextCursor = Math.max(nextCursor, openTime + stepMs);
                    if (DEFAULT_MODE.equalsIgnoreCase(options.mode) && existing.contains(Long.valueOf(openTime))) {
                        skipped++;
                        continue;
                    }
                    ImportKline kline = toKline(options, symbol, item);
                    appendPending(groupedPending, partitionKey(openTime), kline);
                    if (closeTime >= endMs) {
                        nextCursor = endMs + 1L;
                    }
                }
                if (!options.dryRun && !groupedPending.isEmpty()) {
                    insertKlines(groupedPending);
                }
                for (List<ImportKline> group : groupedPending.values()) {
                    for (ImportKline row : group) {
                        existing.add(Long.valueOf(parseEpochMillis(row.startTime)));
                    }
                }
                inserted += countRows(groupedPending);
                if (nextCursor <= cursor) {
                    break;
                }
                System.out.println(symbol + " import batch " + batches
                        + ", interval=" + options.interval
                        + ", fetched=" + rows.size()
                        + ", insertedTotal=" + inserted
                        + ", skippedTotal=" + skipped
                        + ", nextCursor=" + Instant.ofEpochMilli(nextCursor).atZone(ZoneOffset.UTC).toLocalDateTime());
                cursor = nextCursor;
            }
            if (options.sleepMs > 0) {
                Thread.sleep(options.sleepMs);
            }
        }
        System.out.println(symbol + " imported, batches=" + batches + ", fetched=" + fetchedRows
                + ", inserted=" + inserted + ", skipped=" + skipped
                + ", mode=" + options.mode + ", dryRun=" + options.dryRun);
    }

    private static Set<Long> loadExistingStartTimes(Connection connection, String symbol, String interval,
                                                    String venue, long startMs, long endMs) throws Exception {
        Set<Long> existing = new HashSet<Long>();
        String sql = "select toUnixTimestamp64Milli(startTime) as start_time_ms "
                + "from dc.kline where securityID=? and lowerUTF8(text)=lowerUTF8(?) and venue=? "
                + "and startTime>=? and startTime<=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, interval);
            ps.setString(3, venue);
            ps.setTimestamp(4, new Timestamp(startMs));
            ps.setTimestamp(5, new Timestamp(endMs));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existing.add(Long.valueOf(rs.getLong(1)));
                }
            }
        }
        return existing;
    }

    private static boolean isRangeAlreadyComplete(Set<Long> existing, long startMs, long endMs, long stepMs) {
        if (existing == null || existing.isEmpty()) {
            return false;
        }
        for (long ts = startMs; ts <= endMs; ts += stepMs) {
            if (!existing.contains(Long.valueOf(ts))) {
                return false;
            }
            if (Long.MAX_VALUE - stepMs < ts) {
                break;
            }
        }
        return true;
    }

    private static void insertKlines(Map<String, List<ImportKline>> groupedRows) throws Exception {
        for (List<ImportKline> partitionRows : groupedRows.values()) {
            if (partitionRows == null || partitionRows.isEmpty()) {
                continue;
            }
            List<ImportKline> batch = new ArrayList<ImportKline>(Math.min(partitionRows.size(), INSERT_BATCH_SIZE));
            for (ImportKline row : partitionRows) {
                batch.add(row);
                if (batch.size() >= INSERT_BATCH_SIZE) {
                    ClickHouseDBUtils.insertList(batch, "kline");
                    batch = new ArrayList<ImportKline>(Math.min(partitionRows.size(), INSERT_BATCH_SIZE));
                }
            }
            if (!batch.isEmpty()) {
                ClickHouseDBUtils.insertList(batch, "kline");
            }
        }
    }

    private static ImportKline toKline(CliOptions options, String symbol, JSONArray item) {
        long openTime = item.getLongValue(0);
        long closeTime = item.getLongValue(6);
        LocalDateTime openDateTime = Instant.ofEpochMilli(openTime).atZone(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime closeDateTime = Instant.ofEpochMilli(closeTime).atZone(ZoneOffset.UTC).toLocalDateTime();
        String startTime = FMT_TIME_MILLIS.format(openDateTime);
        String endTime = FMT_TIME_MILLIS.format(closeDateTime);
        ImportKline row = new ImportKline();
        row.startTime = startTime;
        row.endTime = endTime;
        row.securityID = symbol;
        row.text = options.interval;
        row.fmtTime = FMT_TIME.format(openDateTime);
        row.open = new BigDecimal(item.getString(1));
        row.high = new BigDecimal(item.getString(2));
        row.low = new BigDecimal(item.getString(3));
        row.close = new BigDecimal(item.getString(4));
        row.openTime = startTime;
        row.highTime = endTime;
        row.lowTime = endTime;
        row.closeTime = endTime;
        row.inf1 = "true";
        row.inf2 = "";
        row.inf3 = "";
        row.inf4 = "";
        row.type = "kline";
        row.venue = options.venue;
        row.createTime = FMT_TIME_MILLIS.format(LocalDateTime.now(ZoneOffset.UTC));
        row.fillFlag = 0;
        row.numTrades = item.getIntValue(8);
        row.turnover = new BigDecimal(item.getString(7));
        row.volume = new BigDecimal(item.getString(5));
        return row;
    }

    private static void appendPending(Map<String, List<ImportKline>> groupedPending, String key, ImportKline kline) {
        List<ImportKline> group = groupedPending.get(key);
        if (group == null) {
            group = new ArrayList<ImportKline>();
            groupedPending.put(key, group);
        }
        group.add(kline);
    }

    private static int countRows(Map<String, List<ImportKline>> groupedPending) {
        int total = 0;
        for (List<ImportKline> rows : groupedPending.values()) {
            total += rows == null ? 0 : rows.size();
        }
        return total;
    }

    private static String partitionKey(long openTime) {
        return FMT_TIME.format(Instant.ofEpochMilli(openTime).atZone(ZoneOffset.UTC).toLocalDateTime()).substring(0, 7);
    }

    private static long parseEpochMillis(String value) {
        return LocalDateTime.parse(value, FMT_TIME_MILLIS).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static long intervalMillis(String interval) {
        String value = interval == null ? "" : interval.trim().toLowerCase();
        if ("1m".equals(value)) {
            return 60_000L;
        }
        if ("5m".equals(value)) {
            return 5L * 60_000L;
        }
        if ("15m".equals(value)) {
            return 15L * 60_000L;
        }
        if ("30m".equals(value)) {
            return 30L * 60_000L;
        }
        if ("1h".equals(value)) {
            return 60L * 60_000L;
        }
        if ("4h".equals(value)) {
            return 4L * 60L * 60_000L;
        }
        if ("1d".equals(value)) {
            return 24L * 60L * 60_000L;
        }
        throw new IllegalArgumentException("unsupported interval: " + interval);
    }

    private static class CliOptions {
        private final List<String> symbols = new ArrayList<String>();
        private String interval;
        private LocalDate startDate;
        private LocalDate endDate;
        private Integer yearsBack;
        private String venue = DEFAULT_VENUE;
        private String mode = DEFAULT_MODE;
        private int limitPerCall = 1500;
        private long sleepMs = 250L;
        private boolean dryRun = false;
        private String dbpoolCfg = DEFAULT_DBPOOL_CFG;
        private String dbSourceName = DEFAULT_CLICKHOUSE_SOURCE;

        static CliOptions parse(String[] args) {
            CliOptions options = new CliOptions();
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
            if (options.startDate == null || options.endDate == null) {
                int years = options.yearsBack == null ? 0 : options.yearsBack.intValue();
                if (years > 0) {
                    options.endDate = LocalDate.now(ZoneOffset.UTC);
                    options.startDate = options.endDate.minusYears(years);
                }
            }
            return options;
        }

        void validate() {
            if (symbols.isEmpty()) {
                throw new IllegalArgumentException("--symbols is required");
            }
            if (interval == null || interval.trim().isEmpty()) {
                throw new IllegalArgumentException("--interval is required");
            }
            if (startDate == null || endDate == null) {
                throw new IllegalArgumentException("either --start-date/--end-date or --years-back is required");
            }
            if (endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("end-date must be >= start-date");
            }
            if (limitPerCall <= 0 || limitPerCall > 1500) {
                throw new IllegalArgumentException("limit-per-call must be between 1 and 1500");
            }
        }

        private void apply(String name, String value) {
            if ("symbols".equalsIgnoreCase(name)) {
                for (String token : value.split(",")) {
                    String symbol = token == null ? "" : token.trim().toUpperCase();
                    if (!symbol.isEmpty()) {
                        symbols.add(symbol);
                    }
                }
                return;
            }
            if ("interval".equalsIgnoreCase(name)) {
                interval = normalizeInterval(value);
                return;
            }
            if ("start-date".equalsIgnoreCase(name)) {
                startDate = LocalDate.parse(value.trim());
                return;
            }
            if ("end-date".equalsIgnoreCase(name)) {
                endDate = LocalDate.parse(value.trim());
                return;
            }
            if ("years-back".equalsIgnoreCase(name)) {
                yearsBack = Integer.valueOf(value.trim());
                return;
            }
            if ("venue".equalsIgnoreCase(name)) {
                venue = value.trim();
                return;
            }
            if ("mode".equalsIgnoreCase(name)) {
                mode = value.trim();
                return;
            }
            if ("limit-per-call".equalsIgnoreCase(name)) {
                limitPerCall = Integer.parseInt(value.trim());
                return;
            }
            if ("sleep-ms".equalsIgnoreCase(name)) {
                sleepMs = Long.parseLong(value.trim());
                return;
            }
            if ("dry-run".equalsIgnoreCase(name)) {
                dryRun = Boolean.parseBoolean(value.trim());
                return;
            }
            if ("dbpool-cfg".equalsIgnoreCase(name)) {
                dbpoolCfg = value.trim();
                return;
            }
            if ("db-source".equalsIgnoreCase(name)) {
                dbSourceName = value.trim();
            }
        }
    }

    private static String normalizeInterval(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
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

    public static class ImportKline {
        @JSONField(name = "startTime", serialize = true)
        public String startTime;

        @JSONField(name = "endTime", serialize = true)
        public String endTime;

        @JSONField(name = "securityID", serialize = true)
        public String securityID;

        @JSONField(name = "text", serialize = true)
        public String text;

        @JSONField(name = "fmtTime", serialize = true)
        public String fmtTime;

        @JSONField(name = "open", serialize = true)
        public BigDecimal open;

        @JSONField(name = "high", serialize = true)
        public BigDecimal high;

        @JSONField(name = "low", serialize = true)
        public BigDecimal low;

        @JSONField(name = "close", serialize = true)
        public BigDecimal close;

        @JSONField(name = "openTime", serialize = true)
        public String openTime;

        @JSONField(name = "highTime", serialize = true)
        public String highTime;

        @JSONField(name = "lowTime", serialize = true)
        public String lowTime;

        @JSONField(name = "closeTime", serialize = true)
        public String closeTime;

        @JSONField(name = "inf1", serialize = true)
        public String inf1;

        @JSONField(name = "inf2", serialize = true)
        public String inf2;

        @JSONField(name = "inf3", serialize = true)
        public String inf3;

        @JSONField(name = "inf4", serialize = true)
        public String inf4;

        @JSONField(name = "type", serialize = true)
        public String type;

        @JSONField(name = "venue", serialize = true)
        public String venue;

        @JSONField(name = "createTime", serialize = true)
        public String createTime;

        @JSONField(name = "fillFlag", serialize = true)
        public int fillFlag;

        @JSONField(name = "numTrades", serialize = true)
        public int numTrades;

        @JSONField(name = "turnover", serialize = true)
        public BigDecimal turnover;

        @JSONField(name = "volume", serialize = true)
        public BigDecimal volume;
    }

}
