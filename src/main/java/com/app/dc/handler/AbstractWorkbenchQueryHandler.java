package com.app.dc.handler;

import com.app.common.utils.Consts;
import com.app.dc.service.workbench.WorkbenchService;
import com.gateway.connector.utils.JsonUtils;
import com.gw.common.utils.ContentHandler;
import com.gw.common.utils.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public abstract class AbstractWorkbenchQueryHandler extends ContentHandler {

    @Autowired
    protected WorkbenchService workbenchService;

    @Override
    public Map<String, Object> handle(String topic, Message message, String content, Map<String, Object> map,
                                      boolean fromList) {
        Map<String, Object> result = new HashMap<String, Object>();
        try {
            @SuppressWarnings("unchecked")
            LinkedHashMap<String, Object> request = JsonUtils.Deserialize(content, LinkedHashMap.class);
            Object data = doHandle(request == null ? new LinkedHashMap<String, Object>() : request);
            result.put(Consts.Code, Consts.SuccessCode);
            result.put(Consts.Msg, Consts.SuccessMsg);
            result.put(Consts.DATA, data);
        } catch (Exception e) {
            log.error("{} error, topic:{}, content:{}", getClass().getSimpleName(), topic, content, e);
            result.put(Consts.Code, Consts.NoKnowCode);
            result.put(Consts.Msg, e.getMessage());
        }
        return result;
    }

    protected abstract Object doHandle(Map<String, Object> request);
}
