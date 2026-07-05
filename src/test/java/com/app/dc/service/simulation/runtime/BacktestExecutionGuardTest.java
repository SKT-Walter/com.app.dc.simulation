package com.app.dc.service.simulation.runtime;

import org.junit.Assert;
import org.junit.Test;

public class BacktestExecutionGuardTest {

    @Test
    public void checkInterruptedShouldPassWhenThreadIsHealthy() throws Exception {
        BacktestExecutionGuard.checkInterrupted("healthy_stage");
    }

    @Test
    public void checkInterruptedShouldThrowWhenThreadWasCancelled() {
        Thread.currentThread().interrupt();
        try {
            BacktestExecutionGuard.checkInterrupted("cancelled_stage");
            Assert.fail("expected interruption");
        } catch (InterruptedException expected) {
            Assert.assertTrue(expected.getMessage().contains("cancelled_stage"));
        } finally {
            Thread.interrupted();
        }
    }
}
