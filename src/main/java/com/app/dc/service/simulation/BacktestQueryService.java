package com.app.dc.service.simulation;

import com.app.common.db.ClickHouseDBUtils;
import com.app.common.utils.JsonUtils;
import com.app.dc.fix.message.MarketDataSnapshotFullRefresh;
import com.app.dc.po.TTbookOhlc;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class BacktestQueryService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId UTC_ZONE = ZoneId.of("UTC");
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private ClickHouseDBUtils clickHouseDBUtils;

    /**
     * 查询回测K线数据，优先读取本地文件，找不到文件时再回退到ClickHouse。
     */
    public List<TTbookOhlc> queryOhlc(String symbol, String text, String beginDate, String endDate) {
        List<TTbookOhlc> localFileResult = null;//queryLocalFile(symbol, text, beginDate, endDate);
        if (localFileResult != null) {
            return localFileResult;
        }

        if (StringUtils.isBlank(clickHouseDBUtils.getDbSourceName())) {
            return Collections.emptyList();
        }

        List<DateRange> ranges = splitDateRanges(beginDate, endDate);
        if (!ranges.isEmpty()) {
            List<TTbookOhlc> result = new ArrayList<TTbookOhlc>();
            for (DateRange range : ranges) {
                List<TTbookOhlc> queryList = querySingleRange(symbol, text, range.beginDate, range.endDate);
                result.addAll(queryList);
            }
            return result;
        }
        return querySingleRange(symbol, text, beginDate, endDate);
    }

    /**
     * 优先从本地JSON文件读取K线数据，文件不存在时返回null表示继续走DB。
     */
    private List<TTbookOhlc> queryLocalFile(String symbol, String text, String beginDate, String endDate) {
        Path path = resolveLocalDataFile(symbol);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            List<MarketDataSnapshotFullRefresh> rawList = JsonUtils.Deserialize2(content, MarketDataSnapshotFullRefresh.class);
            List<TTbookOhlc> result = new ArrayList<TTbookOhlc>();
            if (rawList != null) {
                for (MarketDataSnapshotFullRefresh item : rawList) {
                    if (matchLocalSnapshot(item, symbol, text, beginDate, endDate)) {
                        result.add(toTtbookOhlc(item));
                    }
                }
            }
            result.sort(Comparator.comparing(o -> StringUtils.defaultIfBlank(readOptionalField(o, "endtime"), o.starttime)));
            log.info("BacktestQueryService load local file:{}, symbol:{}, text:{}, size:{}",
                    path, symbol, text, result.size());
            return result;
        } catch (Exception e) {
            log.error("BacktestQueryService load local file error, path:{}, symbol:{}, text:{}",
                    path, symbol, text, e);
            return Collections.emptyList();
        }
    }

    /**
     * 查询单个日期区间内的K线数据。
     */
    private List<TTbookOhlc> querySingleRange(String symbol, String text, String beginDate, String endDate) {
        QueryAndArgs qa = buildOhlcSql(symbol, text, beginDate, endDate);
        log.info("BacktestQueryService query sql:{}, args:{}", qa.sql, qa.args);
        return clickHouseDBUtils.queryList(qa.sql, qa.args.toArray(), TTbookOhlc.class);
    }

    /**
     * 构造K线查询SQL和参数。
     */
    public QueryAndArgs buildOhlcSql(String symbol, String text, String beginDate, String endDate) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ")
                .append("startTime AS starttime,")
                .append("endTime AS endtime,")
                .append("toDate(startTime) AS tradedate,")
                .append("fmtTime AS fmttime,")
                .append("securityID AS securityid,")
                .append("text,")
                .append("open,close,low,high,turnover,volume,inf1 ")
                .append("FROM dc.kline_view WHERE 1=1");

        List<Object> args = new ArrayList<Object>();
        if (StringUtils.isNotBlank(symbol)) {
            sql.append(" AND securityID=?");
            args.add(symbol.trim());
        }
        if (StringUtils.isNotBlank(text)) {
            sql.append(" AND lowerUTF8(text)=lowerUTF8(?)");
            args.add(normalizeText(text));
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

    /**
     * 标准化K线周期文本。
     */
    public String normalizeText(String text) {
        return StringUtils.isBlank(text) ? text : text.trim();
    }

    /**
     * 解析本地数据文件路径，文件名默认按symbol命名。
     */
    private Path resolveLocalDataFile(String symbol) {
        String normalizedSymbol = StringUtils.trimToEmpty(symbol).toUpperCase();
        return Paths.get("config", "data", normalizedSymbol + ".json");
    }

    /**
     * 判断本地快照是否满足品种、周期和日期区间过滤条件。
     */
    private boolean matchLocalSnapshot(MarketDataSnapshotFullRefresh item,
                                       String symbol,
                                       String text,
                                       String beginDate,
                                       String endDate) {
        if (item == null) {
            return false;
        }
        if (StringUtils.isNotBlank(symbol)
                && !StringUtils.equalsIgnoreCase(StringUtils.trimToEmpty(item.getSecurityID()), symbol.trim())) {
            return false;
        }
        if (StringUtils.isNotBlank(text)
                && !StringUtils.equalsIgnoreCase(StringUtils.trimToEmpty(item.getInfo3()), normalizeText(text))) {
            return false;
        }
        if (!isClosedBar(item.getInfo4())) {
            return false;
        }

        LocalDate tradeDate = resolveTradeDate(item.getInfo1());
        if (tradeDate == null) {
            return false;
        }
        try {
            if (StringUtils.isNotBlank(beginDate) && tradeDate.isBefore(LocalDate.parse(beginDate.trim()))) {
                return false;
            }
            if (StringUtils.isNotBlank(endDate) && tradeDate.isAfter(LocalDate.parse(endDate.trim()))) {
                return false;
            }
        } catch (DateTimeParseException e) {
            log.warn("BacktestQueryService local file date filter skip, beginDate:{}, endDate:{}", beginDate, endDate);
        }
        return true;
    }

    /**
     * 将本地行情快照映射为回测使用的TTbookOhlc结构。
     */
    private TTbookOhlc toTtbookOhlc(MarketDataSnapshotFullRefresh item) {
        TTbookOhlc ohlc = new TTbookOhlc();
        ohlc.securityid = item.getSecurityID();
        ohlc.text = StringUtils.upperCase(item.getInfo3());
        ohlc.open = decimal(item.getOpenPrice());
        ohlc.high = decimal(item.getHighPrice());
        ohlc.low = decimal(item.getLowPrice());
        ohlc.close = decimal(item.getClosePrice());
        ohlc.volume = decimal(item.getVolume());
        ohlc.starttime = formatEpochMillis(item.getInfo1());
        setOptionalField(ohlc, "endtime", formatEpochMillis(item.getInfo1()));
        setOptionalField(ohlc, "turnover", decimal(item.getTurnover()));
        setOptionalField(ohlc, "inf1", item.getInfo1());
        return ohlc;
    }

    /**
     * 将毫秒时间戳解析为交易日期。
     */
    private LocalDate resolveTradeDate(String epochMillisText) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(StringUtils.trimToEmpty(epochMillisText)))
                    .atZone(UTC_ZONE)
                    .withZoneSameInstant(BEIJING_ZONE)
                    .toLocalDate();
        } catch (Exception e) {
            log.warn("BacktestQueryService resolveTradeDate failed, epochMillis:{}", epochMillisText);
            return null;
        }
    }

    /**
     * 将毫秒时间戳格式化为TTbookOhlc兼容的时间字符串。
     */
    private String formatEpochMillis(String epochMillisText) {
        try {
            return TIME_FORMATTER.format(
                    Instant.ofEpochMilli(Long.parseLong(StringUtils.trimToEmpty(epochMillisText)))
                            .atZone(UTC_ZONE)
                            .withZoneSameInstant(BEIJING_ZONE)
                            .toLocalDateTime());
        } catch (Exception e) {
            log.warn("BacktestQueryService formatEpochMillis failed, epochMillis:{}", epochMillisText);
            return null;
        }
    }

    /**
     * 解析数值字符串，为空时返回null。
     */
    private BigDecimal decimal(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return new BigDecimal(value.trim());
    }

    /**
     * 解析本地快照里的收线标记，只回放已收线K线。
     */
    private boolean isClosedBar(String info4) {
        return Boolean.parseBoolean(StringUtils.trimToEmpty(info4));
    }

    /**
     * 通过反射填充可选字段，兼容不同版本TTbookOhlc字段差异。
     */
    private void setOptionalField(TTbookOhlc target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception ignore) {
            // ignore optional field assignment differences across TTbookOhlc versions
        }
    }

    /**
     * 读取可选字段，兼容不同版本TTbookOhlc字段差异。
     */
    private String readOptionalField(TTbookOhlc target, String fieldName) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 将日期区间拆成按天查询的小区间。
     */
    private List<DateRange> splitDateRanges(String beginDate, String endDate) {
        if (StringUtils.isBlank(beginDate) || StringUtils.isBlank(endDate)) {
            return Collections.emptyList();
        }
        try {
            LocalDate begin = LocalDate.parse(beginDate.trim());
            LocalDate end = LocalDate.parse(endDate.trim());
            if (end.isBefore(begin)) {
                return Collections.emptyList();
            }
            List<DateRange> ranges = new ArrayList<DateRange>();
            LocalDate cursor = begin;
            while (!cursor.isAfter(end)) {
                String value = cursor.toString();
                ranges.add(new DateRange(value, value));
                cursor = cursor.plusDays(1);
            }
            return ranges;
        } catch (DateTimeParseException e) {
            log.warn("BacktestQueryService split date range skip, beginDate:{}, endDate:{}", beginDate, endDate);
            return Collections.emptyList();
        }
    }

    /**
     * SQL和参数的简单封装。
     */
    public static class QueryAndArgs {
        public final String sql;
        public final List<Object> args;

        /**
         * 创建SQL和参数对象。
         */
        public QueryAndArgs(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }
    }

    /**
     * 查询日期区间。
     */
    private static class DateRange {
        private final String beginDate;
        private final String endDate;

        /**
         * 创建单次查询日期区间。
         */
        private DateRange(String beginDate, String endDate) {
            this.beginDate = beginDate;
            this.endDate = endDate;
        }
    }
}
