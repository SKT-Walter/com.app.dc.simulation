package com.app.dc.service.simulation;

import com.app.common.db.ClickHouseDBUtils;
import com.app.common.utils.JsonUtils;
import com.app.dc.fix.message.MarketDataSnapshotFullRefresh;
import com.app.dc.po.TTbookOhlc;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads deterministic local snapshots first and falls back to ClickHouse when no file exists. */
@Service
@Slf4j
public class BacktestQueryService {
    private static final int MAX_CHUNK_DAYS = 20;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private ClickHouseDBUtils clickHouseDBUtils;
    @Value("${backtest.localDataDir:./config/data}")
    private String localDataDir;
    private final Map<String, String> lastSources = new ConcurrentHashMap<String, String>();

    public List<TTbookOhlc> queryOhlc(String symbol, String text, String beginDate, String endDate) {
        validateRange(beginDate, endDate);
        List<Path> localFiles = resolveLocalDataFiles(symbol, text, beginDate, endDate);
        if (!localFiles.isEmpty()) {
            List<TTbookOhlc> rows = queryLocalFiles(localFiles, symbol, text, beginDate, endDate);
            if (rows.isEmpty())
                throw new IllegalStateException("local market data has no matching closed bars: " + localFiles);
            lastSources.put(key(symbol), localSource(localFiles));
            return rows;
        }
        requireClickHouse();
        List<TTbookOhlc> result = new ArrayList<TTbookOhlc>();
        List<DateRange> ranges = splitDateRanges(beginDate, endDate, MAX_CHUNK_DAYS);
        if (ranges.isEmpty()) result.addAll(querySingleRange(symbol, text, beginDate, endDate));
        else for (DateRange r : ranges) result.addAll(querySingleRange(symbol, text, r.beginDate, r.endDate));
        if (result.isEmpty())
            throw new IllegalStateException("ClickHouse returned no market data for " + symbol + " " + text + " " + beginDate + ".." + endDate);
        lastSources.put(key(symbol), "CLICKHOUSE:dc.kline_view");
        return result;
    }

    public void forEachOhlcChunk(String symbol, String text, String beginDate, String endDate, int chunkDays, OhlcChunkConsumer consumer) throws Exception {
        if (consumer == null) throw new IllegalArgumentException("ohlc chunk consumer must not be null");
        validateRange(beginDate, endDate);
        List<Path> localFiles = resolveLocalDataFiles(symbol, text, beginDate, endDate);
        if (!localFiles.isEmpty()) {
            List<TTbookOhlc> rows = queryLocalFiles(localFiles, symbol, text, beginDate, endDate);
            if (rows.isEmpty())
                throw new IllegalStateException("local market data has no matching closed bars: " + localFiles);
            lastSources.put(key(symbol), localSource(localFiles));
            consumer.accept(beginDate, endDate, rows);
            return;
        }
        requireClickHouse();
        int safe = Math.max(1, Math.min(MAX_CHUNK_DAYS, chunkDays));
        boolean any = false;
        for (DateRange r : splitDateRanges(beginDate, endDate, safe)) {
            List<TTbookOhlc> rows = querySingleRange(symbol, text, r.beginDate, r.endDate);
            if (rows.isEmpty()) {
                log.warn("empty ClickHouse chunk, symbol:{}, text:{}, range:{}..{}", symbol, text, r.beginDate, r.endDate);
                continue;
            }
            any = true;
            consumer.accept(r.beginDate, r.endDate, rows);
        }
        if (!any)
            throw new IllegalStateException("ClickHouse returned no market data for " + symbol + " " + text + " " + beginDate + ".." + endDate);
        lastSources.put(key(symbol), "CLICKHOUSE:dc.kline_view");
    }

    public String getLastSource(String symbol) {
        return lastSources.get(key(symbol));
    }

    public Path resolveLocalDataFile(String symbol) {
        return Paths.get(localDataDir, key(symbol) + ".json").toAbsolutePath().normalize();
    }

    /**
     * The legacy symbol file has precedence. Otherwise every annual partition touched by
     * the requested Beijing-calendar date range must exist; local and ClickHouse data are
     * never mixed in one replay.
     */
    public List<Path> resolveLocalDataFiles(String symbol, String beginDate, String endDate) {
        return resolveLocalDataFiles(symbol,"15m",beginDate,endDate);
    }

    public List<Path> resolveLocalDataFiles(String symbol,String text,String beginDate,String endDate) {
        boolean primary="15m".equalsIgnoreCase(StringUtils.trimToEmpty(text));
        Path legacy = resolveLocalDataFile(symbol);
        if (primary&&Files.isRegularFile(legacy)) return Collections.singletonList(legacy);
        if (StringUtils.isBlank(beginDate) || StringUtils.isBlank(endDate))
            return Collections.emptyList();

        LocalDate begin = LocalDate.parse(beginDate.trim());
        LocalDate end = LocalDate.parse(endDate.trim());
        List<Path> expected = new ArrayList<Path>();
        int existing = 0;
        for (int year = begin.getYear(); year <= end.getYear(); year++) {
            String suffix=primary?"_"+year:"_"+StringUtils.lowerCase(text.trim())+"_"+year;
            Path annual = Paths.get(localDataDir, key(symbol) + suffix + ".json")
                    .toAbsolutePath().normalize();
            expected.add(annual);
            if (Files.isRegularFile(annual)) existing++;
        }
        if (existing == 0) return Collections.emptyList();
        if (existing != expected.size()) {
            List<Path> missing = new ArrayList<Path>();
            for (Path file : expected) if (!Files.isRegularFile(file)) missing.add(file);
            throw new IllegalStateException("annual local market data is incomplete; missing files: " + missing);
        }
        return expected;
    }

    /** Loads an auxiliary local timeframe without changing the primary replay source. */
    public List<TTbookOhlc> queryLocalOhlc(String symbol,String text,String beginDate,String endDate) {
        validateRange(beginDate,endDate);
        List<Path> files=resolveLocalDataFiles(symbol,text,beginDate,endDate);
        if(files.isEmpty())throw new IllegalStateException("local auxiliary market data not found: "+symbol+" "+text);
        List<TTbookOhlc> rows=queryLocalFiles(files,symbol,text,beginDate,endDate);
        if(rows.isEmpty())throw new IllegalStateException("local auxiliary market data has no matching closed bars: "+files);
        return rows;
    }

    private List<TTbookOhlc> queryLocalFiles(List<Path> paths, String symbol, String text,
                                             String beginDate, String endDate) {
        List<TTbookOhlc> combined = new ArrayList<TTbookOhlc>();
        for (Path path : paths) combined.addAll(queryLocalFile(path, symbol, text, beginDate, endDate));
        return normalizeRows(combined);
    }

    private String localSource(List<Path> files) {
        StringBuilder source = new StringBuilder("JSON:");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) source.append(',');
            source.append(files.get(i).toAbsolutePath().normalize());
        }
        return source.toString();
    }

    private List<TTbookOhlc> queryLocalFile(Path path, String symbol, String text, String beginDate, String endDate) {
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            List<MarketDataSnapshotFullRefresh> raw = JsonUtils.Deserialize2(json, MarketDataSnapshotFullRefresh.class);
            if (raw == null)
                throw new IllegalArgumentException("JSON root must be an array of MarketDataSnapshotFullRefresh");
            List<TTbookOhlc> out = new ArrayList<TTbookOhlc>();
            for (MarketDataSnapshotFullRefresh item : raw)
                if (matches(item, symbol, text, beginDate, endDate)) out.add(toOhlc(item));
            return normalizeRows(out);
        } catch (Exception e) {
            throw new IllegalStateException("invalid local market data file: " + path + ", " + e.getMessage(), e);
        }
    }

    private boolean matches(MarketDataSnapshotFullRefresh x, String symbol, String text, String beginDate, String endDate) {
        if (x == null || !StringUtils.equalsIgnoreCase(StringUtils.trimToEmpty(x.getSecurityID()), StringUtils.trimToEmpty(symbol)))
            return false;
        if (!StringUtils.equalsIgnoreCase(StringUtils.trimToEmpty(x.getInfo3()), StringUtils.trimToEmpty(text)))
            return false;
        if (!isClosed(x.getInfo4())) return false;
        LocalDate d = tradeDate(x.getInfo1());
        return d != null && (StringUtils.isBlank(beginDate) || !d.isBefore(LocalDate.parse(beginDate.trim()))) && (StringUtils.isBlank(endDate) || !d.isAfter(LocalDate.parse(endDate.trim())));
    }

    private TTbookOhlc toOhlc(MarketDataSnapshotFullRefresh x) {
        TTbookOhlc o = new TTbookOhlc();
        o.securityid = x.getSecurityID();
        o.text = StringUtils.upperCase(x.getInfo3());
        o.open = decimal(x.getOpenPrice());
        o.high = decimal(x.getHighPrice());
        o.low = decimal(x.getLowPrice());
        o.close = decimal(x.getClosePrice());
        o.volume = decimal(x.getVolume());
        o.starttime = formatEpoch(x.getInfo1());
        return o;
    }

    private boolean isClosed(String s) {
        String v = StringUtils.trimToEmpty(s);
        while (v.endsWith("\"") || v.endsWith("]")) v = v.substring(0, v.length() - 1).trim();
        return Boolean.parseBoolean(v);
    }

    private LocalDate tradeDate(String ts) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(StringUtils.trimToEmpty(ts))).atZone(BEIJING_ZONE).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private String formatEpoch(String ts) {
        try {
            return TIME_FORMATTER.format(Instant.ofEpochMilli(Long.parseLong(StringUtils.trimToEmpty(ts))).atZone(BEIJING_ZONE));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid epoch millis: " + ts);
        }
    }

    private BigDecimal decimal(String v) {
        if (StringUtils.isBlank(v)) throw new IllegalArgumentException("OHLCV value is blank");
        return new BigDecimal(v.trim());
    }

    private void requireClickHouse() {
        if (clickHouseDBUtils == null || StringUtils.isBlank(clickHouseDBUtils.getDbSourceName()))
            throw new IllegalStateException("local JSON not found and ClickHouse datasource is not configured");
    }

    private List<TTbookOhlc> querySingleRange(String symbol, String text, String beginDate, String endDate) {
        QueryAndArgs q = buildOhlcSql(symbol, text, beginDate, endDate);
        log.info("query ohlc sql:{}, args:{}", q.sql, q.args);
        List<TTbookOhlc> rows = clickHouseDBUtils.queryList(q.sql, q.args.toArray(), TTbookOhlc.class);
        return rows == null ? Collections.<TTbookOhlc>emptyList() : normalizeRows(rows);
    }

    /**
     * kline_view groups by fmtTime as well as startTime, so one logical bar can be returned
     * more than once. Pick a canonical version independent of ClickHouse result order and
     * query chunk size.
     */
    public List<TTbookOhlc> normalizeRows(List<TTbookOhlc> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<TTbookOhlc> sorted = new ArrayList<TTbookOhlc>();
        for (TTbookOhlc row : rows) if (row != null && StringUtils.isNotBlank(row.starttime)) sorted.add(row);
        Collections.sort(sorted, new Comparator<TTbookOhlc>() {
            @Override public int compare(TTbookOhlc left, TTbookOhlc right) {
                int time = safe(left.starttime).compareTo(safe(right.starttime));
                if (time != 0) return time;
                // Descending canonical version fields: the same input set always selects
                // the same row even when ClickHouse returns tied startTime rows differently.
                int version = safe(right.fmttime).compareTo(safe(left.fmttime));
                if (version != 0) return version;
                version = safe(right.inf1).compareTo(safe(left.inf1));
                if (version != 0) return version;
                version = decimalText(right.close).compareTo(decimalText(left.close));
                if (version != 0) return version;
                version = decimalText(right.volume).compareTo(decimalText(left.volume));
                if (version != 0) return version;
                return canonicalRow(right).compareTo(canonicalRow(left));
            }
        });
        List<TTbookOhlc> result = new ArrayList<TTbookOhlc>();
        String previous = null;
        for (TTbookOhlc row : sorted) {
            if (!safe(row.starttime).equals(previous)) {
                result.add(row);
                previous = safe(row.starttime);
            }
        }
        if (result.size() != sorted.size()) {
            log.info("normalized duplicate OHLC rows, input:{}, output:{}", sorted.size(), result.size());
        }
        return result;
    }

    private String canonicalRow(TTbookOhlc row) {
        return safe(row.endtime) + '|' + decimalText(row.open) + '|' + decimalText(row.high)
                + '|' + decimalText(row.low) + '|' + decimalText(row.close) + '|'
                + decimalText(row.turnover) + '|' + decimalText(row.volume);
    }

    private String decimalText(BigDecimal value) { return value == null ? "" : value.toPlainString(); }
    private String safe(String value) { return value == null ? "" : value; }

    public QueryAndArgs buildOhlcSql(String symbol, String text, String beginDate, String endDate) {
        StringBuilder sql = new StringBuilder("SELECT startTime AS starttime,endTime AS endtime,toDate(startTime) AS tradedate,fmtTime AS fmttime,securityID AS securityid,text,open,close,low,high,turnover,volume,inf1 FROM dc.kline_view WHERE 1=1");
        List<Object> args = new ArrayList<Object>();
        if (StringUtils.isNotBlank(symbol)) {
            sql.append(" AND securityID=?");
            args.add(symbol.trim());
        }
        if (StringUtils.isNotBlank(text)) {
            sql.append(" AND lowerUTF8(text)=lowerUTF8(?)");
            args.add(text.trim());
        }
        if (StringUtils.isNotBlank(beginDate)) {
            sql.append(" AND toDate(startTime)>=toDate(?)");
            args.add(beginDate.trim());
        }
        if (StringUtils.isNotBlank(endDate)) {
            sql.append(" AND toDate(startTime)<=toDate(?)");
            args.add(endDate.trim());
        }
        sql.append(" ORDER BY startTime ASC");
        return new QueryAndArgs(sql.toString(), args);
    }

    private void validateRange(String begin, String end) {
        try {
            if (StringUtils.isNotBlank(begin) && StringUtils.isNotBlank(end) && LocalDate.parse(end.trim()).isBefore(LocalDate.parse(begin.trim())))
                throw new IllegalArgumentException("end date must not be before begin date");
            if (StringUtils.isNotBlank(begin)) LocalDate.parse(begin.trim());
            if (StringUtils.isNotBlank(end)) LocalDate.parse(end.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("dates must use yyyy-MM-dd", e);
        }
    }

    private List<DateRange> splitDateRanges(String begin, String end, int days) {
        if (StringUtils.isBlank(begin) || StringUtils.isBlank(end)) return Collections.emptyList();
        List<DateRange> out = new ArrayList<DateRange>();
        LocalDate cursor = LocalDate.parse(begin.trim()), last = LocalDate.parse(end.trim());
        while (!cursor.isAfter(last)) {
            LocalDate e = cursor.plusDays(Math.max(1, days) - 1L);
            if (e.isAfter(last)) e = last;
            out.add(new DateRange(cursor.toString(), e.toString()));
            cursor = e.plusDays(1);
        }
        return out;
    }

    private String key(String s) {
        return StringUtils.trimToEmpty(s).toUpperCase();
    }

    public interface OhlcChunkConsumer {
        void accept(String beginDate, String endDate, List<TTbookOhlc> rows) throws Exception;
    }

    public static class QueryAndArgs {
        public final String sql;
        public final List<Object> args;

        public QueryAndArgs(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }
    }

    private static class DateRange {
        final String beginDate, endDate;

        DateRange(String b, String e) {
            beginDate = b;
            endDate = e;
        }
    }
}
