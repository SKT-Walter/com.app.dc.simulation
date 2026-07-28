package com.app.dc.binance;

import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;

/** Builds official Binance Vision USD-M futures kline archive locations. */
public final class BinanceVisionArchiveLocator {
    private final String baseUrl;

    public BinanceVisionArchiveLocator(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Binance Vision base URL must not be blank");
        this.baseUrl = normalized;
    }

    public Archive monthly(String symbol, String interval, YearMonth month) {
        String file = symbol + "-" + interval + "-" + month + ".zip";
        return archive("monthly", symbol, interval, file);
    }

    public Archive daily(String symbol, String interval, LocalDate date) {
        String file = symbol + "-" + interval + "-" + date + ".zip";
        return archive("daily", symbol, interval, file);
    }

    private Archive archive(String cadence, String symbol, String interval, String file) {
        String relative = "data/futures/um/" + cadence + "/klines/" + symbol + "/" + interval + "/" + file;
        return new Archive(URI.create(baseUrl + "/" + relative), file, relative);
    }

    public static final class Archive {
        private final URI zipUri;
        private final String fileName;
        private final String relativePath;

        Archive(URI zipUri, String fileName, String relativePath) {
            this.zipUri = zipUri;
            this.fileName = fileName;
            this.relativePath = relativePath;
        }

        public URI getZipUri() { return zipUri; }
        public URI getChecksumUri() { return URI.create(zipUri.toString() + ".CHECKSUM"); }
        public String getFileName() { return fileName; }
        public String getRelativePath() { return relativePath; }
    }
}
