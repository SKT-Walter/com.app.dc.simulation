package com.app.dc.service.simulation.deterministic;

public final class StrategyRoutingState {
    String activeStrategy;
    double activeScore;
    int activatedAt = -1;
    int holdUntil = -1;
    String pendingStrategy;
    int pendingCount;
    int lowScoreCount;

    public String activeStrategy() { return activeStrategy; }
    public double activeScore() { return activeScore; }

    void activate(String strategy, double score, int barIndex, int minimumHoldBars) {
        activeStrategy = strategy;
        activeScore = score;
        activatedAt = barIndex;
        holdUntil = barIndex + Math.max(0, minimumHoldBars);
        pendingStrategy = null;
        pendingCount = 0;
        lowScoreCount = 0;
    }

    void clearActive() {
        activeStrategy = null;
        activeScore = 0;
        activatedAt = -1;
        holdUntil = -1;
        lowScoreCount = 0;
    }

    void clearPending() { pendingStrategy = null; pendingCount = 0; }
}
