package com.app.dc.service.simulation.strategy.exit;

/** Immutable close-at-bar-close decision from a strategy-specific position policy. */
public final class PositionExitDecision {
    public final boolean exit;
    public final String reason;

    private PositionExitDecision(boolean exit, String reason) {
        this.exit = exit;
        this.reason = reason;
    }

    public static PositionExitDecision hold() {
        return new PositionExitDecision(false, null);
    }

    public static PositionExitDecision exit(String reason) {
        return new PositionExitDecision(true, reason);
    }
}
