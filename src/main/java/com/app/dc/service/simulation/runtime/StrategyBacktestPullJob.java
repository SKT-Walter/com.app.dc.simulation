package com.app.dc.service.simulation.runtime;

import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class StrategyBacktestPullJob {

    @Value("${strategy.backtest.task.enabled:false}")
    private boolean enabled;

    @Value("${strategy.backtest.task.batchSize:10}")
    private int batchSize;

    @Autowired
    private StrategyBacktestTaskDao taskDao;

    @Autowired
    private VersionedBacktestRunner versionedBacktestRunner;

    @Scheduled(cron = "${strategy.backtest.task.cron:0 */1 * * * ?}")
    public void run() {
        if (!enabled) {
            return;
        }
        List<StrategyBacktestTaskRow> tasks = taskDao.pullPending(batchSize);
        for (StrategyBacktestTaskRow task : tasks) {
            handleTask(task);
        }
    }

    private void handleTask(StrategyBacktestTaskRow task) {
        try {
            taskDao.markRunning(task.id);
            StrategyCandidateRow candidate = taskDao.loadCandidate(task.strategyName, task.strategyVersion);
            if (candidate == null) {
                throw new IllegalStateException("candidate not found: " + task.strategyName + "@" + task.strategyVersion);
            }
            taskDao.markSuccess(task.id, JsonUtils.Serializer(task));
        } catch (Exception e) {
            log.error("StrategyBacktestPullJob handleTask error, task:{}", task == null ? null : task.id, e);
            taskDao.markFailed(task == null ? null : task.id, e.getMessage());
        }
    }
}
