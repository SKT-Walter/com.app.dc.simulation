package com.app.dc.handler;

import com.app.common.utils.Consts;
import com.app.dc.po.backtest.BinanceBacktestParam;
import com.app.dc.service.dao.BinanceBacktestResultClickHouseDao;
import com.app.dc.service.simulation.BinanceBacktestModels;
import com.app.dc.service.simulation.BinanceBacktestReportService;
import com.app.dc.service.simulation.BinanceBacktestService;
import com.gateway.connector.utils.JsonUtils;
import com.gw.common.utils.ContentHandler;
import com.gw.common.utils.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 币安策略回测接口。
 */
@Service("dc.ind.backtest.binance")
public class BinanceBacktestHandler extends ContentHandler {

    @Autowired
    private BinanceBacktestService binanceBacktestService;

    @Autowired
    private BinanceBacktestReportService backtestReportService;

    @Autowired
    private BinanceBacktestResultClickHouseDao backtestResultClickHouseDao;

    /**
     * 处理外部回测请求并返回评估结果。
     */
    public Map<String, Object> handle(String topic, Message message, String content, Map<String, Object> map,
                                      boolean fromList) {
        String sid = message.getSignalID();
        logger.info("sid:{}, topic:{}, content:{}", sid, topic, content);

        Map<String, Object> resultMap = new HashMap<>();
        try {
            BinanceBacktestParam param = JsonUtils.Deserialize(content, BinanceBacktestParam.class);
            BinanceBacktestModels.BacktestResponse result = binanceBacktestService.run(param);
            String reportPath = backtestReportService.writeReport(result);
            backtestResultClickHouseDao.insertResults(sid, reportPath, result);
            resultMap.put(Consts.DATA, result);
            resultMap.put("report_path", reportPath);
            resultMap.put(Consts.Code, Consts.SuccessCode);
            resultMap.put(Consts.Msg, Consts.SuccessMsg);
        } catch (Exception e) {
            logger.error("BinanceBacktestHandler handle error", e);
            resultMap.put(Consts.Code, Consts.NoKnowCode);
            resultMap.put(Consts.Msg, e.getMessage());
        }
        logger.info("BinanceBacktestHandler result:{}", JsonUtils.Serializer(resultMap));
        return resultMap;
    }
}
