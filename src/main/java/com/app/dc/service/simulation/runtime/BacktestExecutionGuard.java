package com.app.dc.service.simulation.runtime;

public final class BacktestExecutionGuard {

    private BacktestExecutionGuard() {
    }

    public static void checkInterrupted(String stage) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("backtest interrupted at " + safeStage(stage));
        }
    }

    private static String safeStage(String stage) {
        return stage == null || stage.trim().isEmpty() ? "unknown_stage" : stage.trim();
    }
}
