package com.app.dc.binance;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates monthly/daily archives and builds one validated Beijing-calendar-year dataset. */
@Slf4j
public final class BinanceAnnualKlineService {
    static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private final BinanceVisionArchiveLocator locator;
    private final BinanceVisionArchiveRepository repository;
    private final BinanceKlineCsvParser parser;
    private final BinanceAnnualJsonWriter writer;
    private final Clock clock;

    public BinanceAnnualKlineService(BinanceVisionArchiveLocator locator,
                                     BinanceVisionArchiveRepository repository,
                                     BinanceKlineCsvParser parser,
                                     BinanceAnnualJsonWriter writer,
                                     Clock clock) {
        this.locator = locator;
        this.repository = repository;
        this.parser = parser;
        this.writer = writer;
        this.clock = clock;
    }

    public DownloadResult download(String symbol, String interval, int year, Path output,
                                   boolean overwrite) throws IOException {
        String normalizedSymbol = normalizeSymbol(symbol);
        long duration = BinanceInterval.durationMillis(interval);
        int currentYear = LocalDate.now(clock.withZone(BEIJING_ZONE)).getYear();
        if (year > currentYear) throw new IllegalArgumentException("year must not be in the future: " + year);
        if (Files.exists(output.toAbsolutePath().normalize()) && !overwrite)
            throw new IOException("annual market data already exists; use --overwrite=true: "
                    + output.toAbsolutePath().normalize());

        boolean historical = year < currentYear;
        List<BinanceKline> collected = new ArrayList<BinanceKline>();
        boolean dataStarted = false;
        YearMonth firstArchiveMonth = YearMonth.of(year - 1, 12);
        YearMonth lastArchiveMonth = historical
                ? YearMonth.of(year, 12)
                : YearMonth.now(clock.withZone(BEIJING_ZONE));
        for (YearMonth month = firstArchiveMonth; !month.isAfter(lastArchiveMonth); month = month.plusMonths(1)) {
            MonthResult result = downloadMonth(normalizedSymbol, interval, month, historical, dataStarted);
            collected.addAll(result.rows);
            if (!result.rows.isEmpty()) dataStarted = true;
        }

        List<BinanceKline> annual = normalizeAndFilter(collected, year, duration, historical);
        if (annual.isEmpty())
            throw new IOException("Binance Vision returned no closed bars for "
                    + normalizedSymbol + " " + interval + " year " + year);
        writer.write(output, normalizedSymbol, interval, annual, overwrite);
        return new DownloadResult(normalizedSymbol, interval, year, output.toAbsolutePath().normalize(),
                annual.size(), annual.get(0).getOpenTime(), annual.get(annual.size() - 1).getOpenTime());
    }

    MonthResult downloadMonth(String symbol, String interval, YearMonth month,
                              boolean historical, boolean dataStarted) throws IOException {
        BinanceVisionArchiveLocator.Archive monthly = locator.monthly(symbol, interval, month);
        Path monthlyZip = repository.obtain(monthly);
        if (monthlyZip != null) {
            logArchive("monthly", monthly);
            return new MonthResult(parser.parse(monthlyZip, interval));
        }

        LocalDate yesterdayUtc = LocalDate.now(clock.withZone(ZoneId.of("UTC"))).minusDays(1);
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        if (month.getYear() < 1970 || first.isAfter(yesterdayUtc)) return new MonthResult(Collections.<BinanceKline>emptyList());
        if (last.isAfter(yesterdayUtc)) last = yesterdayUtc;

        List<BinanceKline> rows = new ArrayList<BinanceKline>();
        boolean started = dataStarted;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            BinanceVisionArchiveLocator.Archive daily = locator.daily(symbol, interval, date);
            Path dailyZip = repository.obtain(daily);
            if (dailyZip == null) {
                if (historical && started)
                    throw new IOException("missing Binance daily archive after data started: " + daily.getZipUri());
                continue;
            }
            logArchive("daily", daily);
            List<BinanceKline> dailyRows = parser.parse(dailyZip, interval);
            rows.addAll(dailyRows);
            if (!dailyRows.isEmpty()) started = true;
        }
        return new MonthResult(rows);
    }

    private void logArchive(String cadence, BinanceVisionArchiveLocator.Archive archive) {
        if (org.apache.log4j.Logger.getRootLogger().getAllAppenders().hasMoreElements())
            log.info("using Binance Vision {} archive: {}", cadence, archive.getZipUri());
    }

    List<BinanceKline> normalizeAndFilter(List<BinanceKline> input, int year,
                                         long duration, boolean historical) throws IOException {
        Collections.sort(input, new Comparator<BinanceKline>() {
            @Override public int compare(BinanceKline left, BinanceKline right) {
                return Long.compare(left.getOpenTime(), right.getOpenTime());
            }
        });
        Map<Long, BinanceKline> unique = new LinkedHashMap<Long, BinanceKline>();
        for (BinanceKline row : input) {
            BinanceKline previous = unique.get(row.getOpenTime());
            if (previous != null && !previous.sameMarketData(row))
                throw new IOException("conflicting duplicate kline at openTime " + row.getOpenTime());
            unique.put(row.getOpenTime(), row);
        }

        List<BinanceKline> annual = new ArrayList<BinanceKline>();
        boolean hadPriorYearData = false;
        for (BinanceKline row : unique.values()) {
            int rowYear = Instant.ofEpochMilli(row.getOpenTime()).atZone(BEIJING_ZONE).getYear();
            if (rowYear < year) hadPriorYearData = true;
            if (rowYear == year) annual.add(row);
        }
        for (int i = 1; i < annual.size(); i++) {
            long delta = annual.get(i).getOpenTime() - annual.get(i - 1).getOpenTime();
            if (delta != duration)
                throw new IOException("kline time gap detected between "
                        + annual.get(i - 1).getOpenTime() + " and " + annual.get(i).getOpenTime());
        }
        if (historical && !annual.isEmpty()) {
            long expectedFirst = LocalDate.of(year, 1, 1).atStartOfDay(BEIJING_ZONE)
                    .toInstant().toEpochMilli();
            long expectedLast = LocalDate.of(year + 1, 1, 1).atStartOfDay(BEIJING_ZONE)
                    .toInstant().toEpochMilli() - duration;
            if (hadPriorYearData && annual.get(0).getOpenTime() != expectedFirst)
                throw new IOException("historical year starts with a kline gap, expected openTime " + expectedFirst);
            if (annual.get(annual.size() - 1).getOpenTime() != expectedLast)
                throw new IOException("historical year ends with a kline gap, expected openTime " + expectedLast);
        }
        return annual;
    }

    private String normalizeSymbol(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9]{5,30}"))
            throw new IllegalArgumentException("invalid Binance symbol: " + symbol);
        return normalized;
    }

    static final class MonthResult {
        final List<BinanceKline> rows;
        MonthResult(List<BinanceKline> rows) { this.rows = rows; }
    }

    public static final class DownloadResult {
        public final String symbol;
        public final String interval;
        public final int year;
        public final Path output;
        public final int bars;
        public final long firstOpenTime;
        public final long lastOpenTime;

        DownloadResult(String symbol, String interval, int year, Path output,
                       int bars, long firstOpenTime, long lastOpenTime) {
            this.symbol = symbol;
            this.interval = interval;
            this.year = year;
            this.output = output;
            this.bars = bars;
            this.firstOpenTime = firstOpenTime;
            this.lastOpenTime = lastOpenTime;
        }
    }
}
