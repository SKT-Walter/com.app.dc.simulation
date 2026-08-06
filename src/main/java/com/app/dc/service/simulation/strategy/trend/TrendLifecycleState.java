package com.app.dc.service.simulation.strategy.trend;

/** Mutable session memory owned by TrendLifecycleService. */
public final class TrendLifecycleState {
    int lastProcessedIndex = -1;
    String phase = TrendLifecycleSnapshot.WARMUP;
    String direction = TrendLifecycleSnapshot.NONE;
    int phaseStartIndex = -1;
    double impulseOrigin = Double.NaN;
    double impulseExtreme = Double.NaN;
    double pullbackExtreme = Double.NaN;
    int pullbackBars;
    double recoveryLevel = Double.NaN;
    int triggeredIndex = -1;
    int signalExpiresIndex = -1;
    double triggerPrice = Double.NaN;
    double stopPrice = Double.NaN;
    boolean consumed;
    TrendLifecycleSnapshot cached;

    void directional(String newDirection, int index) {
        phase = TrendLifecycleSnapshot.DIRECTIONAL;
        direction = newDirection;
        phaseStartIndex = index;
        impulseOrigin = impulseExtreme = pullbackExtreme = recoveryLevel = Double.NaN;
        pullbackBars = 0;
        triggeredIndex = signalExpiresIndex = -1;
        triggerPrice = stopPrice = Double.NaN;
        consumed = false;
    }
}
