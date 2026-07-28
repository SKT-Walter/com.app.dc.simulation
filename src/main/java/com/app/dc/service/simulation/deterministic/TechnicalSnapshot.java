package com.app.dc.service.simulation.deterministic;

/** Immutable, current-bar-only technical feature snapshot used by deterministic routing. */
public final class TechnicalSnapshot {
    public final double open;
    public final double high;
    public final double low;
    public final double close;
    public final double previousClose;
    public final double atr;
    public final double atrPercentile;
    public final double adx;
    public final double emaFast;
    public final double emaSlow;
    public final double ema10;
    public final double ema20;
    public final double ema60;
    public final double emaSlowSlope;
    public final double volumeRatio;
    public final double bandwidth;
    public final double previousBandwidth;
    public final double zScore20;
    public final double rsi14;
    public final double vwap20;
    public final double previousVwap20;
    public final double recentHigh;
    public final double recentLow;
    public final double previousHigh;
    public final double previousLow;
    public final double previousBarHigh;
    public final double previousBarLow;
    public final double closeLocation;
    public final double bodyAtr;
    public final double structureStrength;
    public final double meanRecoveryStrength;
    public final double macdImprovement;

    public TechnicalSnapshot(double open, double high, double low, double close, double previousClose,
                             double atr, double atrPercentile, double adx,
                             double emaFast, double emaSlow, double ema10, double ema20, double ema60,
                             double emaSlowSlope, double volumeRatio, double bandwidth,
                             double previousBandwidth, double zScore20, double rsi14,
                             double vwap20, double previousVwap20,
                             double recentHigh, double recentLow, double previousHigh, double previousLow,
                             double previousBarHigh, double previousBarLow,
                             double closeLocation, double bodyAtr, double structureStrength,
                             double meanRecoveryStrength, double macdImprovement) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.previousClose = previousClose;
        this.atr = atr;
        this.atrPercentile = atrPercentile;
        this.adx = adx;
        this.emaFast = emaFast;
        this.emaSlow = emaSlow;
        this.ema10 = ema10;
        this.ema20 = ema20;
        this.ema60 = ema60;
        this.emaSlowSlope = emaSlowSlope;
        this.volumeRatio = volumeRatio;
        this.bandwidth = bandwidth;
        this.previousBandwidth = previousBandwidth;
        this.zScore20 = zScore20;
        this.rsi14 = rsi14;
        this.vwap20 = vwap20;
        this.previousVwap20 = previousVwap20;
        this.recentHigh = recentHigh;
        this.recentLow = recentLow;
        this.previousHigh = previousHigh;
        this.previousLow = previousLow;
        this.previousBarHigh = previousBarHigh;
        this.previousBarLow = previousBarLow;
        this.closeLocation = closeLocation;
        this.bodyAtr = bodyAtr;
        this.structureStrength = structureStrength;
        this.meanRecoveryStrength = meanRecoveryStrength;
        this.macdImprovement = macdImprovement;
    }
}
