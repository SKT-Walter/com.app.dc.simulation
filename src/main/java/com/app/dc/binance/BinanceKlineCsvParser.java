package com.app.dc.binance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Parses and validates the single CSV entry in a Binance kline ZIP archive. */
public final class BinanceKlineCsvParser {
    private final Clock clock;

    public BinanceKlineCsvParser(Clock clock) {
        this.clock = clock;
    }

    public List<BinanceKline> parse(Path zipFile, String interval) throws IOException {
        long duration = BinanceInterval.durationMillis(interval);
        try (InputStream file = Files.newInputStream(zipFile);
             ZipInputStream zip = new ZipInputStream(file, StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory())
                throw new IOException("ZIP contains no CSV file: " + zipFile);
            if (!entry.getName().toLowerCase().endsWith(".csv"))
                throw new IOException("ZIP entry is not CSV: " + entry.getName());
            List<BinanceKline> rows = readCsv(zip, duration, zipFile);
            if (zip.getNextEntry() != null)
                throw new IOException("ZIP must contain exactly one CSV entry: " + zipFile);
            return rows;
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid Binance CSV in " + zipFile + ": " + e.getMessage(), e);
        }
    }

    private List<BinanceKline> readCsv(InputStream input, long duration, Path source) throws IOException {
        List<BinanceKline> rows = new ArrayList<BinanceKline>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.trim().isEmpty()) continue;
            String[] columns = line.split(",", -1);
            if (lineNumber == 1 && !isNumber(columns[0])) continue;
            if (columns.length != 12)
                throw new IOException("expected 12 CSV columns at " + source.getFileName() + ":" + lineNumber);
            BinanceKline row = row(columns, source, lineNumber);
            validate(row, duration, source, lineNumber);
            rows.add(row);
        }
        if (rows.isEmpty()) throw new IOException("CSV contains no kline rows: " + source);
        return rows;
    }

    private BinanceKline row(String[] c, Path source, int line) throws IOException {
        try {
            long openTime = epochMillis(c[0]);
            long closeTime = epochMillis(c[6]);
            return new BinanceKline(openTime, closeTime,
                    decimal(c[1]), decimal(c[2]), decimal(c[3]), decimal(c[4]), decimal(c[5]));
        } catch (RuntimeException e) {
            throw new IOException("invalid value at " + source.getFileName() + ":" + line, e);
        }
    }

    private void validate(BinanceKline k, long duration, Path source, int line) throws IOException {
        String at = source.getFileName() + ":" + line;
        if (k.getOpenTime() <= 0 || k.getCloseTime() <= k.getOpenTime())
            throw new IOException("invalid kline timestamps at " + at);
        if (k.getOpenTime() % duration != 0)
            throw new IOException("open time is not aligned to interval at " + at);
        if (k.getCloseTime() >= clock.millis())
            throw new IOException("kline is not closed at " + at);
        if (k.getOpen().signum() <= 0 || k.getHigh().signum() <= 0
                || k.getLow().signum() <= 0 || k.getClose().signum() <= 0)
            throw new IOException("OHLC prices must be positive at " + at);
        if (k.getVolume().signum() < 0)
            throw new IOException("volume must not be negative at " + at);
        if (k.getHigh().compareTo(k.getOpen()) < 0 || k.getHigh().compareTo(k.getClose()) < 0
                || k.getHigh().compareTo(k.getLow()) < 0)
            throw new IOException("high price is inconsistent at " + at);
        if (k.getLow().compareTo(k.getOpen()) > 0 || k.getLow().compareTo(k.getClose()) > 0)
            throw new IOException("low price is inconsistent at " + at);
    }

    private long epochMillis(String value) {
        long epoch = Long.parseLong(value.trim());
        while (epoch > 9_999_999_999_999L) epoch /= 1000L;
        return epoch;
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value.trim());
    }

    private boolean isNumber(String value) {
        try {
            Long.parseLong(value.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
