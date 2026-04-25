package com.app.dc.service.simulation.runtime;

import com.gateway.connector.utils.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StrategyBacktestTaskQueryService {

    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private StrategyBacktestTaskDao taskDao;

    public Map<String, Object> query(String taskId,
                                     String generationTaskId,
                                     String candidateId,
                                     String strategyName,
                                     String strategyVersion,
                                     String status,
                                     int limit) {
        List<StrategyBacktestTaskRow> rows = taskDao.loadLatest(taskId, generationTaskId, candidateId,
                strategyName, strategyVersion, status, limit);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (StrategyBacktestTaskRow row : rows) {
            items.add(toView(row));
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("taskId", blankToEmpty(taskId));
        data.put("generationTaskId", blankToEmpty(generationTaskId));
        data.put("candidateId", blankToEmpty(candidateId));
        data.put("strategyName", blankToEmpty(strategyName));
        data.put("strategyVersion", blankToEmpty(strategyVersion));
        data.put("status", blankToEmpty(status));
        data.put("limit", Math.max(1, Math.min(limit, 100)));
        data.put("rows", items);
        data.put("latest", items.isEmpty() ? null : items.get(0));
        return data;
    }

    private Map<String, Object> toView(StrategyBacktestTaskRow row) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", row.id);
        view.put("candidateId", row.candidateId);
        view.put("generationTaskId", row.generationTaskId);
        view.put("strategyName", row.strategyName);
        view.put("strategyVersion", row.strategyVersion);
        view.put("baselineVersion", row.baselineVersion);
        view.put("runtimeType", row.runtimeType);
        view.put("taskType", row.taskType);
        view.put("status", row.status);
        view.put("attemptCount", row.attemptCount);
        view.put("priority", row.priority);
        view.put("fitWindowDays", row.fitWindowDays);
        view.put("validateWindowDays", row.validateWindowDays);
        view.put("forwardWindowDays", row.forwardWindowDays);
        view.put("suspendReason", row.suspendReason);
        view.put("failureReason", row.failureReason);
        view.put("nextRetryTime", row.nextRetryTime);
        view.put("createTime", row.createTime);
        view.put("updateTime", row.updateTime);
        view.put("runtimeState", runtimeState(row));
        view.put("payloadSummary", summarizePayload(row.payload));
        view.put("rawPayload", blankToEmpty(row.payload));
        return view;
    }

    private Map<String, Object> runtimeState(StrategyBacktestTaskRow row) {
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        String status = blankToEmpty(row == null ? null : row.status);
        state.put("status", status);
        state.put("isTerminal", "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status));
        state.put("isRecoverable", "SUSPENDED".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status)
                || "PENDING".equalsIgnoreCase(status));
        state.put("retryReady", "SUSPENDED".equalsIgnoreCase(status) && retryReady(row == null ? null : row.nextRetryTime));
        state.put("staleRunningCandidate", "RUNNING".equalsIgnoreCase(status) && staleRunningCandidate(row == null ? null : row.updateTime));
        return state;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summarizePayload(String payload) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        if (StringUtils.isBlank(payload)) {
            return summary;
        }
        try {
            StrategyBacktestTaskPayloadEnvelope envelope =
                    JsonUtils.Deserialize(payload, StrategyBacktestTaskPayloadEnvelope.class);
            if (envelope != null) {
                if (envelope.backtestParam != null) {
                    summary.put("symbol", blankToEmpty(envelope.backtestParam.symbol));
                    summary.put("symbols", blankToEmpty(envelope.backtestParam.symbols));
                    summary.put("text", blankToEmpty(envelope.backtestParam.text));
                    summary.put("beginDate", blankToEmpty(envelope.backtestParam.beginDate));
                    summary.put("endDate", blankToEmpty(envelope.backtestParam.endDate));
                }
                if (envelope.suspendDetail != null && !envelope.suspendDetail.isEmpty()) {
                    summary.put("suspendDetail", envelope.suspendDetail);
                }
                return summary;
            }
        } catch (Exception ignored) {
        }
        try {
            Map<String, Object> map = JsonUtils.Deserialize(payload, Map.class);
            if (map != null && !map.isEmpty()) {
                summary.putAll(map);
            }
        } catch (Exception ignored) {
        }
        return summary;
    }

    private boolean retryReady(String nextRetryTime) {
        if (StringUtils.isBlank(nextRetryTime)) {
            return true;
        }
        try {
            LocalDateTime retryAt = LocalDateTime.parse(nextRetryTime.trim(), CLICKHOUSE_TIME);
            return !retryAt.isAfter(LocalDateTime.now());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean staleRunningCandidate(String updateTime) {
        if (StringUtils.isBlank(updateTime)) {
            return false;
        }
        try {
            LocalDateTime lastUpdate = LocalDateTime.parse(updateTime.trim(), CLICKHOUSE_TIME);
            return lastUpdate.isBefore(LocalDateTime.now().minusMinutes(5));
        } catch (Exception e) {
            return false;
        }
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
