package com.app.dc.service.simulation.deterministic;

import java.util.ArrayDeque;
import java.util.Deque;

/** Mutable state owned by one symbol replay session. */
public final class StructuralTrendState {
    String direction = StructuralTrendSnapshot.NEUTRAL;
    String pendingDirection;
    int pendingBars;
    int directionBars;
    int pullbackBars;
    boolean indicatorsInitialized;
    int lastIndicatorIndex = -1;
    double fastEma;
    double slowEma;
    final Deque<Double> slowEmaHistory = new ArrayDeque<Double>();

    void confirm(String nextDirection) {
        direction = nextDirection;
        directionBars = 1;
        pullbackBars = 0;
        pendingDirection = null;
        pendingBars = 0;
    }

    void clearPending() {
        pendingDirection = null;
        pendingBars = 0;
    }

    void clearDirection() {
        direction = StructuralTrendSnapshot.NEUTRAL;
        directionBars = 0;
        pullbackBars = 0;
        clearPending();
    }
}
