package com.app.dc.service.simulation.runtime;

import java.util.List;

public interface StrategyBacktestTaskDao {
    List<StrategyBacktestTaskRow> pullPending(int limit);

    List<StrategyBacktestTaskRow> pullRunnable(int limit, String reclaimRunningBefore);

    List<StrategyBacktestTaskRow> loadLatest(String taskId,
                                             String generationTaskId,
                                             String candidateId,
                                             String strategyName,
                                             String strategyVersion,
                                             String status,
                                             int limit);

    void markRunning(String id);

    void refreshRunningProgress(String id, String payload);

    void markSuccess(String id, String payload);

    void markFailed(String id, String errorMsg);

    void markSuspended(String id, String reason, String payload, String nextRetryTime);

    void markRetryReadyNow(String id, String reason);

    void refreshRecoveryProgress(String id, String payload, String nextRetryTime);

    StrategyCandidateRow loadCandidate(String strategyName, String strategyVersion);
}
