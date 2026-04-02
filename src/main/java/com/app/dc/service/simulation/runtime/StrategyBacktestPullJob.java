package com.app.dc.service.simulation.runtime;

import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.dao.BacktestResultClickHouseDao;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestReportService;
import com.app.dc.service.simulation.BacktestService;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private BacktestService backtestService;

    @Autowired
    private BacktestReportService backtestReportService;

    @Autowired
    private BacktestResultClickHouseDao backtestResultClickHouseDao;

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

            BacktestParam param = buildParam(task, candidate);
            BacktestModels.BacktestResponse response = backtestService.run(param);
            String reportPath = backtestReportService.writeReport(response);
            String compareReportPath = backtestReportService.writeCompareReport(response);
            backtestResultClickHouseDao.insertResults(task.id, reportPath, response);

            Map<String, Object> taskResult = new LinkedHashMap<String, Object>();
            taskResult.put("taskId", task.id);
            taskResult.put("strategyName", candidate.strategyName);
            taskResult.put("strategyVersion", candidate.strategyVersion);
            taskResult.put("runtimeType", candidate.runtimeType);
            taskResult.put("scene", candidate.scene);
            taskResult.put("reportPath", reportPath);
            taskResult.put("compareReportPath", compareReportPath);
            taskResult.put("resultCount", response.results == null ? 0 : response.results.size());
            taskDao.markSuccess(task.id, JsonUtils.Serializer(taskResult));
        } catch (Exception e) {
            log.error("StrategyBacktestPullJob handleTask error, task:{}", task == null ? null : task.id, e);
            taskDao.markFailed(task == null ? null : task.id, e.getMessage());
        }
    }

    private BacktestParam buildParam(StrategyBacktestTaskRow task, StrategyCandidateRow candidate) {
        BacktestParam param = null;
        if (task != null && task.payload != null && !task.payload.trim().isEmpty()) {
            try {
                param = JsonUtils.Deserialize(task.payload, BacktestParam.class);
            } catch (Exception e) {
                log.warn("StrategyBacktestPullJob payload parse fallback, task:{}", task.id, e);
            }
        }
        if (param == null) {
            param = new BacktestParam();
        }
        if (isBlank(param.strategyName)) {
            param.strategyName = candidate.strategyName;
        }
        if (isBlank(param.strategyVersion)) {
            param.strategyVersion = candidate.strategyVersion;
        }
        if (isBlank(param.baselineVersion)) {
            param.baselineVersion = task.baselineVersion;
        }
        if (isBlank(param.runtimeType)) {
            param.runtimeType = candidate.runtimeType;
        }
        if (isBlank(param.scene)) {
            param.scene = candidate.scene;
        }
        if (isBlank(param.strategyPayload)) {
            param.strategyPayload = candidate.payload;
        }
        if (isBlank(param.symbol) && isBlank(param.symbols)) {
            param.symbol = defaultSymbol(candidate.scene);
            param.symbols = param.symbol;
        }
        if (isBlank(param.text)) {
            param.text = defaultText(candidate.scene);
        }
        if (isBlank(param.endDate)) {
            param.endDate = LocalDate.now().minusDays(1).toString();
        }
        if (isBlank(param.beginDate)) {
            int fitDays = task.fitWindowDays == null ? 120 : task.fitWindowDays.intValue();
            int validateDays = task.validateWindowDays == null ? 30 : task.validateWindowDays.intValue();
            int forwardDays = task.forwardWindowDays == null ? 14 : task.forwardWindowDays.intValue();
            int totalDays = Math.max(30, fitDays + validateDays + forwardDays);
            param.beginDate = LocalDate.parse(param.endDate).minusDays(totalDays).toString();
        }
        return param;
    }

    private String defaultSymbol(String scene) {
        if ("trend".equalsIgnoreCase(scene)) {
            return "BTCUSDT";
        }
        if ("channel".equalsIgnoreCase(scene)) {
            return "SOLUSDT";
        }
        return "ETHUSDT";
    }

    private String defaultText(String scene) {
        if ("trend".equalsIgnoreCase(scene)) {
            return "1h";
        }
        return "15m";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
