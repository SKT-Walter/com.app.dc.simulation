package com.app.dc.simulation.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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
        int inserted = 0;
        int skipped = 0;
        long cursor = startMs;
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
                    break;
                }
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
                    if (!options.dryRun) {
                        insertKline(connection, options, symbol, item);
                    }
                    existing.add(Long.valueOf(openTime));
                    inserted++;
                    if (closeTime >= endMs) {
                        nextCursor = endMs + 1L;
                    }
                }
                if (nextCursor <= cursor) {
                    break;
                }
                cursor = nextCursor;
            }
            if (options.sleepMs > 0) {
                Thread.sleep(options.sleepMs);
            }
        }
        System.out.println(symbol + " imported, inserted=" + inserted + ", skipped=" + skipped
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

    private static void insertKline(Connection connection, CliOptions options, String symbol, JSONArray item) throws Exception {
        String sql = "insert into dc.kline "
                + "(startTime,endTime,securityID,text,fmtTime,open,high,low,close,openTime,highTime,lowTime,closeTime,"
                + "inf1,type,venue,createTime,fillFlag,numTrades,turnover,volume) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        long openTime = item.getLongValue(0);
        long closeTime = item.getLongValue(6);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            Timestamp openTs = new Timestamp(openTime);
            Timestamp closeTs = new Timestamp(closeTime);
            ps.setTimestamp(1, openTs);
            ps.setTimestamp(2, closeTs);
            ps.setString(3, symbol);
            ps.setString(4, options.interval);
            ps.setString(5, FMT_TIME.format(Instant.ofEpochMilli(openTime).atZone(ZoneOffset.UTC).toLocalDateTime()));
            ps.setBigDecimal(6, new BigDecimal(item.getString(1)));
            ps.setBigDecimal(7, new BigDecimal(item.getString(2)));
            ps.setBigDecimal(8, new BigDecimal(item.getString(3)));
            ps.setBigDecimal(9, new BigDecimal(item.getString(4)));
            ps.setTimestamp(10, openTs);
            ps.setTimestamp(11, closeTs);
            ps.setTimestamp(12, closeTs);
            ps.setTimestamp(13, closeTs);
            ps.setString(14, "true");
            ps.setString(15, "kline");
            ps.setString(16, options.venue);
            ps.setTimestamp(17, Timestamp.from(Instant.now()));
            ps.setInt(18, 0);
            ps.setInt(19, item.getIntValue(8));
            ps.setBigDecimal(20, new BigDecimal(item.getString(7)));
            ps.setBigDecimal(21, new BigDecimal(item.getString(5)));
            ps.executeUpdate();
        }
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
}
