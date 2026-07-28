package com.app.dc.binance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Binance fixed-duration kline intervals supported by the archive downloader. */
public final class BinanceInterval {
    private static final Map<String, Long> DURATIONS;

    static {
        Map<String, Long> values = new LinkedHashMap<String, Long>();
        values.put("1m", 60_000L);
        values.put("3m", 180_000L);
        values.put("5m", 300_000L);
        values.put("15m", 900_000L);
        values.put("30m", 1_800_000L);
        values.put("1h", 3_600_000L);
        values.put("2h", 7_200_000L);
        values.put("4h", 14_400_000L);
        values.put("6h", 21_600_000L);
        values.put("8h", 28_800_000L);
        values.put("12h", 43_200_000L);
        values.put("1d", 86_400_000L);
        DURATIONS = Collections.unmodifiableMap(values);
    }

    private BinanceInterval() {
    }

    public static long durationMillis(String interval) {
        Long duration = DURATIONS.get(interval);
        if (duration == null)
            throw new IllegalArgumentException("unsupported Binance kline interval: " + interval);
        return duration.longValue();
    }
}
