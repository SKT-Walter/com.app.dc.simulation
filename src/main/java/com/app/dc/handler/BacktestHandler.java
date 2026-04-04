package com.app.dc.handler;

import com.app.common.utils.Consts;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.dao.BacktestResultClickHouseDao;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestReportService;
import com.app.dc.service.simulation.BacktestService;
import com.gateway.connector.utils.JsonUtils;
import com.gw.common.utils.ContentHandler;
import com.gw.common.utils.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service("dc.ind.backtest")
public class BacktestHandler extends ContentHandler {

    @Autowired
    private BacktestService binanceBacktestService;

    @Autowired
    private BacktestReportService backtestReportService;

    @Autowired
    private BacktestResultClickHouseDao backtestResultClickHouseDao;

    public Map<String, Object> handle(String topic, Message message, String content, Map<String, Object> map,
                                      boolean fromList) {
        String sid = message.getSignalID();
        logger.info("sid:{}, topic:{}, content:{}", sid, topic, content);

        Map<String, Object> resultMap = new HashMap<>();
        try {
            BacktestParam param = JsonUtils.Deserialize(content, BacktestParam.class);
            BacktestModels.BacktestResponse result = binanceBacktestService.run(param);
            String reportPath = backtestReportService.writeReport(sid, result, null);
            String compareReportPath = backtestReportService.writeCompareReport(result);
            backtestResultClickHouseDao.insertResults(sid, reportPath, result);
            resultMap.put(Consts.DATA, result);
            resultMap.put("report_path", reportPath);
            resultMap.put("compare_report_path", compareReportPath);
            resultMap.put(Consts.Code, Consts.SuccessCode);
            resultMap.put(Consts.Msg, Consts.SuccessMsg);
        } catch (Exception e) {
            logger.error("BacktestHandler handle error", e);
            resultMap.put(Consts.Code, Consts.NoKnowCode);
            resultMap.put(Consts.Msg, e.getMessage());
        }
        logger.info("BacktestHandler result:{}", JsonUtils.Serializer(resultMap));
        return resultMap;
    }
}
