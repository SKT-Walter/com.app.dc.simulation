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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class StrategyBacktestPullJob {

    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${strategy.backtest.task.enabled:false}")
    private boolean enabled;

    @Value("${strategy.backtest.task.batchSize:10}")
    private int batchSize;

    @Autowired
    private StrategyBacktestTaskDao taskDao;

    @Autowired
    private BacktestService backtestService;

    @Autowired
    private BacktestReportService backtestReportService;

    @Autowired
    private BacktestResultClickHouseDao backtestResultClickHouseDao;

    @Autowired
    private StrategyAutoPublishService strategyAutoPublishService;

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
            BacktestModels.BacktestResponse response = backtestService.run(param,
                    task.fitWindowDays == null ? 120 : task.fitWindowDays.intValue(),
                    task.validateWindowDays == null ? 30 : task.validateWindowDays.intValue(),
                    task.forwardWindowDays == null ? 14 : task.forwardWindowDays.intValue());
            String reportPath = backtestReportService.writeReport(response);
            String compareReportPath = backtestReportService.writeCompareReport(response);
            backtestResultClickHouseDao.insertResults(task.id, reportPath, response);
            StrategyAutoPublishDecision publishDecision =
                    strategyAutoPublishService.maybePublish(task, candidate, response);

            Map<String, Object> taskResult = new LinkedHashMap<String, Object>();
            taskResult.put("taskId", task.id);
            taskResult.put("strategyName", candidate.strategyName);
            taskResult.put("strategyVersion", candidate.strategyVersion);
            taskResult.put("runtimeType", candidate.runtimeType);
            taskResult.put("scene", candidate.scene);
            taskResult.put("reportPath", reportPath);
            taskResult.put("compareReportPath", compareReportPath);
            taskResult.put("resultCount", response.results == null ? 0 : response.results.size());
            taskResult.put("windowMode", response.windowMode);
            taskResult.put("sliceCount", response.sliceCount);
            taskResult.put("fitPnl", response.fitPnl);
            taskResult.put("validatePnl", response.validatePnl);
            taskResult.put("forwardPnl", response.forwardPnl);
            taskResult.put("totalPnl", response.totalPnl);
            taskResult.put("overfitPass", response.overfitPass);
            taskResult.put("overfitReason", response.overfitReason);
            taskResult.put("autoPublishAction", publishDecision.action);
            taskResult.put("autoPublished", publishDecision.published);
            taskResult.put("autoPublishReason", publishDecision.reason);
            taskResult.put("baselineVersion", publishDecision.baselineVersion);
            taskResult.put("currentTotalPnl", publishDecision.currentTotalPnl);
            taskResult.put("currentValidatePnl", publishDecision.currentValidatePnl);
            taskResult.put("currentForwardPnl", publishDecision.currentForwardPnl);
            taskResult.put("currentForwardScore", publishDecision.currentForwardScore);
            taskResult.put("baselineTotalPnl", publishDecision.baselineTotalPnl);
            taskResult.put("baselineForwardScore", publishDecision.baselineForwardScore);
            taskDao.markSuccess(task.id, JsonUtils.Serializer(taskResult));
        } catch (BacktestTaskSuspendedException e) {
            log.warn("StrategyBacktestPullJob suspend task:{}, reason:{}, detail:{}",
                    task == null ? null : task.id, e.getReason(), JsonUtils.Serializer(e.getDetail()));
            taskDao.markSuspended(task == null ? null : task.id,
                    e.getReason(),
                    buildSuspendPayload(task, e),
                    CLICKHOUSE_TIME.format(LocalDateTime.now().plusMinutes(30)));
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
            if (param == null || (isBlank(param.strategyName) && isBlank(param.strategyVersion)
                    && isBlank(param.symbol) && isBlank(param.symbols) && isBlank(param.text))) {
                try {
                    StrategyBacktestTaskPayloadEnvelope envelope =
                            JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
                    if (envelope != null && envelope.backtestParam != null) {
                        param = envelope.backtestParam;
                    }
                } catch (Exception e) {
                    log.warn("StrategyBacktestPullJob envelope parse fallback, task:{}", task.id, e);
                }
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
            int totalDays = Math.max(30, fitDays + validateDays + (forwardDays * 3));
            param.beginDate = LocalDate.parse(param.endDate).minusDays(totalDays).toString();
        }
        return param;
    }

    private String buildSuspendPayload(StrategyBacktestTaskRow task, BacktestTaskSuspendedException error) {
        StrategyBacktestTaskPayloadEnvelope envelope = new StrategyBacktestTaskPayloadEnvelope();
        if (task != null && task.payload != null && !task.payload.trim().isEmpty()) {
            try {
                BacktestParam param = JsonUtils.Deserialize(task.payload, BacktestParam.class);
                if (param != null && (!isBlank(param.strategyName) || !isBlank(param.symbol) || !isBlank(param.text))) {
                    envelope.backtestParam = param;
                }
            } catch (Exception e) {
                log.warn("buildSuspendPayload parse payload fallback, task:{}", task.id, e);
            }
            if (envelope.backtestParam == null) {
                try {
                    StrategyBacktestTaskPayloadEnvelope existing =
                            JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
                    if (existing != null && existing.backtestParam != null) {
                        envelope.backtestParam = existing.backtestParam;
                    }
                } catch (Exception e) {
                    log.warn("buildSuspendPayload parse envelope fallback, task:{}", task.id, e);
                }
            }
        }
        envelope.suspendDetail = error.getDetail();
        return JsonUtils.Serializer(envelope);
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
