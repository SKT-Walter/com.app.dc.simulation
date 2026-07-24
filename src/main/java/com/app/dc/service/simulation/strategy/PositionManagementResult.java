package com.app.dc.service.simulation.strategy;

public final class PositionManagementResult {

    public final Double stopPrice;
    public final boolean exit;
    public final String exitReason;

    private PositionManagementResult(Double stopPrice, boolean exit, String exitReason) {
        this.stopPrice = stopPrice;
        this.exit = exit;
        this.exitReason = exitReason;
    }

    public static PositionManagementResult hold() {
        return new PositionManagementResult(null, false, null);
    }

    public static PositionManagementResult tightenStop(double stopPrice) {
        return new PositionManagementResult(stopPrice, false, null);
    }

    public static PositionManagementResult exit(String exitReason) {
        return new PositionManagementResult(null, true, exitReason);
    }
}
