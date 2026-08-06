package com.app.dc.service.simulation.strategy.range;

/** Mutable session state owned by one symbol/timeframe range setup. */
public final class BinanceRangeState {
    int lastProcessedIndex = -1;
    int touchIndex = -1;
    int reclaimedIndex = -1;
    int confirmedIndex = -1;
    String phase = "IDLE";
    String side = "HOLD";
    double boxHigh = Double.NaN;
    double boxLow = Double.NaN;
    double boxAtr = Double.NaN;
    double boxQuality;
    double touchExtreme = Double.NaN;
    double reclaimedClose = Double.NaN;
    double reclaimedExtreme = Double.NaN;
    boolean consumed;
    BinanceRangeStateSnapshot cached;

    void clearSetup() {
        touchIndex = -1;
        reclaimedIndex = -1;
        confirmedIndex = -1;
        phase = "IDLE";
        side = "HOLD";
        boxHigh = Double.NaN;
        boxLow = Double.NaN;
        boxAtr = Double.NaN;
        boxQuality = 0;
        touchExtreme = Double.NaN;
        reclaimedClose = Double.NaN;
        reclaimedExtreme = Double.NaN;
        consumed = false;
        cached = null;
    }
}
