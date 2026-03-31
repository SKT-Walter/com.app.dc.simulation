package com.app.dc.simulation;

import com.app.common.utils.JsonUtils;
import com.app.dc.po.backtest.BacktestParam;
import com.gateway.connector.tcp.client.GateWayApi;
import com.gateway.connector.tcp.client.IEventListener;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.PropertyConfigurator;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Map;

public class TestBacktestHandler {

    private GateWayApi clientApi;

    private final String serverName = "SIMSvr";

    @Before
    public void init() {
        BasicConfigurator.configure();
        PropertyConfigurator.configure("./config/log4j.ini");

        clientApi = new GateWayApi();
        clientApi.setEventlistener(eventlistener);
        clientApi.setGzip(true);

        String result = clientApi.connect("172.16.188.95", 30044, serverName, "123456", false, 1);
        System.out.println("connect result:" + result);
    }

    static IEventListener eventlistener = new IEventListener() {
        @Override
        public void onEvent(int code) {
            switch (code) {
                case IEventListener.CONNECTION_CLOSED:
                    System.out.println("CONNECTION_CLOSED");
                    break;
                case IEventListener.CONNECTION_LOGIN_SUCCESS:
                    System.out.println("CONNECTION_LOGIN_SUCCESS");
                    break;
                case IEventListener.CONNECTION_SUCCESS:
                    System.out.println("CONNECTION_SUCCESS");
                    break;
                default:
                    break;
            }
        }
    };

    @Test
    public void testBacktestBinanceHandler() throws Exception {
        BacktestParam param = new BacktestParam();
        param.strategyName = "all";//"binanceRangeMacd";
//        param.strategyName = "trendPullbackRecovery";
//        param.strategyName = "binanceRange";
//        param.symbol = "ETHUSDT";
        param.symbols = "ETHUSDT,SOLUSDT,ADAUSDT,BNBUSDT";
        // 娉ㄩ噴宸蹭慨澶嶃€?
        param.text = "15M";
        param.beginDate = "2026-03-28";
        param.endDate = "2026-03-31";
        param.initialCapital = new BigDecimal("10000");
        param.feeRatePct = new BigDecimal("0.04");
//        param.fallbackStopLossPct = new BigDecimal("1.5");
        param.fallbackStopLossPct = new BigDecimal("6.0");
        param.fallbackTakeProfitPct = new BigDecimal("6.0");
//        param.maxHoldBars = 32;
        param.ignoreSentimentGuard = true;

        String content = JsonUtils.Serializer(param);
        Map resp = clientApi.requestSync(serverName, "dc.ind.backtest", content, Map.class);
        System.out.println(JsonUtils.Serializer(resp));
    }
}
