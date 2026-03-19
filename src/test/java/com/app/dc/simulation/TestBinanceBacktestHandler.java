package com.app.dc.simulation;

import com.app.common.utils.JsonUtils;
import com.app.dc.po.backtest.BinanceBacktestParam;
import com.gateway.connector.tcp.client.GateWayApi;
import com.gateway.connector.tcp.client.IEventListener;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.PropertyConfigurator;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 通过网关接口调用 simulator 回测服务的测试类。
 */
public class TestBinanceBacktestHandler {

    /**
     * 本地网关客户端。
     */
    private GateWayApi clientApi;

    /**
     * 服务名需与 simulator 启动后的服务名保持一致。
     */
    private final String serverName = "SIMSvr";

    /**
     * 初始化网关连接与日志配置。
     */
    @Before
    public void init() {
        BasicConfigurator.configure();
        PropertyConfigurator.configure("./config/log4j.ini");

        clientApi = new GateWayApi();
        clientApi.setEventlistener(eventlistener);
        clientApi.setGzip(true);

        String result = clientApi.connect("172.16.189.26", 30044, serverName, "123456", false, 1);
        System.out.println("connect result:" + result);
    }

    /**
     * 输出连接事件，便于排查联通性问题。
     */
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

    /**
     * 调用 dc.ind.backtest.binance 接口执行一次币安策略回测。
     */
    @Test
    public void testBacktestBinanceHandler() throws Exception {
        BinanceBacktestParam param = new BinanceBacktestParam();
        param.strategyName = "binanceTrend";
        param.symbol = "ETHUSDT";
        param.text = "15m";
        param.beginDate = "2026-03-14";
        param.endDate = "2026-03-19";
        param.initialCapital = new BigDecimal("10000");
        param.feeRatePct = new BigDecimal("0.04");
//        param.fallbackStopLossPct = new BigDecimal("1.5");
        param.fallbackStopLossPct = new BigDecimal("6.0");
        param.fallbackTakeProfitPct = new BigDecimal("6.0");
//        param.maxHoldBars = 32;
        param.ignoreSentimentGuard = true;

        String content = JsonUtils.Serializer(param);
        Map resp = clientApi.requestSync(serverName, "dc.ind.backtest.binance", content, Map.class);
        System.out.println(JsonUtils.Serializer(resp));
    }
}
