package com.app.dc.service.simulation.runtime;

/**
 * Defines the evidence boundary shared by both walk-forward runners.
 */
final class WalkForwardWindowPolicy {

    private WalkForwardWindowPolicy() {
    }

    static int sliceStepDays(int validateWindowDays, int forwardWindowDays) {
        if (validateWindowDays <= 0 || forwardWindowDays <= 0) {
            throw new IllegalArgumentException("walk-forward windows must be positive");
        }
        return Math.addExact(validateWindowDays, forwardWindowDays);
    }
}
