package com.app.dc.binance;

import java.math.BigDecimal;

/** Immutable Binance kline row parsed from the official archive CSV. */
public final class BinanceKline {
    private final long openTime;
    private final long closeTime;
    private final BigDecimal open;
    private final BigDecimal high;
    private final BigDecimal low;
    private final BigDecimal close;
    private final BigDecimal volume;

    public BinanceKline(long openTime, long closeTime, BigDecimal open, BigDecimal high,
                        BigDecimal low, BigDecimal close, BigDecimal volume) {
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public long getOpenTime() { return openTime; }
    public long getCloseTime() { return closeTime; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public BigDecimal getVolume() { return volume; }

    public boolean sameMarketData(BinanceKline other) {
        return other != null
                && openTime == other.openTime
                && closeTime == other.closeTime
                && open.compareTo(other.open) == 0
                && high.compareTo(other.high) == 0
                && low.compareTo(other.low) == 0
                && close.compareTo(other.close) == 0
                && volume.compareTo(other.volume) == 0;
    }
}
