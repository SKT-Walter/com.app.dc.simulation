package com.app.dc.service.simulation.runtime;

import java.util.Map;

public class BacktestTaskSuspendedException extends RuntimeException {
    private final String reason;
    private final Map<String, Object> detail;

    public BacktestTaskSuspendedException(String reason, Map<String, Object> detail) {
        super(reason);
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }
}
