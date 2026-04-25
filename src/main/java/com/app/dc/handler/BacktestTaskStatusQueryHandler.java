package com.app.dc.handler;

import com.app.common.utils.Consts;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskQueryService;
import com.gateway.connector.utils.JsonUtils;
import com.gw.common.utils.ContentHandler;
import com.gw.common.utils.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service("dc.sim.backtest.task.status.query")
public class BacktestTaskStatusQueryHandler extends ContentHandler {

    @Autowired
    private StrategyBacktestTaskQueryService queryService;

    @Override
    public Map<String, Object> handle(String topic, Message message, String content, Map<String, Object> map,
                                      boolean fromList) {
        Map<String, Object> result = new HashMap<String, Object>();
        try {
            @SuppressWarnings("unchecked")
            LinkedHashMap<String, Object> request = JsonUtils.Deserialize(content, LinkedHashMap.class);
            Map<String, Object> data = queryService.query(
                    text(request, "taskId"),
                    text(request, "generationTaskId"),
                    text(request, "candidateId"),
                    text(request, "strategyName"),
                    text(request, "strategyVersion"),
                    text(request, "status"),
                    number(request, "limit", 20));
            result.put(Consts.Code, Consts.SuccessCode);
            result.put(Consts.Msg, Consts.SuccessMsg);
            result.put(Consts.DATA, data);
        } catch (Exception e) {
            log.error("BacktestTaskStatusQueryHandler error, topic:{}, content:{}", topic, content, e);
            result.put(Consts.Code, Consts.NoKnowCode);
            result.put(Consts.Msg, e.getMessage());
        }
        return result;
    }

    private String text(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private int number(Map<String, Object> request, String key, int defaultValue) {
        Object value = request == null ? null : request.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
