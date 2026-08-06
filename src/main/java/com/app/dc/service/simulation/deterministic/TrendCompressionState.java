package com.app.dc.service.simulation.deterministic;

/** Mutable, session-local memory for a directional low-volatility compression. */
public final class TrendCompressionState {
    String direction = TrendCompressionSnapshot.NONE;
    int preparationBars;
    int armedUntil = -1;
    int breakoutPendingUntil = -1;
    double breakoutLevel = Double.NaN;
    double compressionHigh = Double.NaN;
    double compressionLow = Double.NaN;

    void reset() {
        direction = TrendCompressionSnapshot.NONE;
        preparationBars = 0;
        armedUntil = -1;
        breakoutPendingUntil = -1;
        breakoutLevel = Double.NaN;
        compressionHigh = Double.NaN;
        compressionLow = Double.NaN;
    }
}
