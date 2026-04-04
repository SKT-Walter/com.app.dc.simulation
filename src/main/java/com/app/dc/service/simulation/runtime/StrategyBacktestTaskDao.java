package com.app.dc.service.simulation.runtime;

import java.util.List;

public interface StrategyBacktestTaskDao {
    List<StrategyBacktestTaskRow> pullPending(int limit);

    void markRunning(String id);

    void markSuccess(String id, String payload);

    void markFailed(String id, String errorMsg);

    void markSuspended(String id, String reason, String payload, String nextRetryTime);

    StrategyCandidateRow loadCandidate(String strategyName, String strategyVersion);
}
