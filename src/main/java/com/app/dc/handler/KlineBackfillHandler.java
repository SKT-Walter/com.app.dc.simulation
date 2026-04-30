package com.app.dc.handler;

import com.app.common.utils.Consts;
import com.app.dc.service.simulation.runtime.ManualKlineBackfillService;
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
@Service("dc.sim.kline.backfill")
public class KlineBackfillHandler extends ContentHandler {

    @Autowired
    private ManualKlineBackfillService manualKlineBackfillService;

    @Override
    public Map<String, Object> handle(String topic, Message message, String content, Map<String, Object> map,
                                      boolean fromList) {
        Map<String, Object> result = new HashMap<String, Object>();
        try {
            @SuppressWarnings("unchecked")
            LinkedHashMap<String, Object> request = JsonUtils.Deserialize(content, LinkedHashMap.class);
            Map<String, Object> data = manualKlineBackfillService.trigger(request);
            result.put(Consts.Code, Consts.SuccessCode);
            result.put(Consts.Msg, Consts.SuccessMsg);
            result.put(Consts.DATA, data);
        } catch (Exception e) {
            log.error("KlineBackfillHandler error, topic:{}, content:{}", topic, content, e);
            result.put(Consts.Code, Consts.NoKnowCode);
            result.put(Consts.Msg, e.getMessage());
        }
        return result;
    }
}
