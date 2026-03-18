package com.app.dc.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BinanceBacktestParam;
import com.app.dc.service.simulation.BinanceBacktestMetricService;
import com.app.dc.service.simulation.BinanceBacktestModels;
import com.app.dc.service.simulation.BinanceBacktestService;
import com.app.dc.service.simulation.BinanceBacktestStrategyService;
import com.app.dc.service.simulation.BinanceBacktestSupportService;
import com.app.dc.service.simulation.BinanceBacktestTradeService;
import com.app.dc.service.simulation.strategy.BinanceChannelBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceRangeBacktestStrategy;
import com.app.dc.service.simulation.strategy.BinanceTrendBacktestStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * simulator 功能演示测试。
 * 该类不依赖 ClickHouse，直接构造历史 K 线验证回测主流程是否可运行。
 */
public class SimulatorDemoTest {

    /**
     * 直接运行该方法即可验证 simulator 核心流程。
     */
    public static void main(String[] args) throws Exception {
        SimulatorDemoTest test = new SimulatorDemoTest();
        test.runChannelBacktestDemo();
    }

    /**
     * 使用一段人工构造的 15m K 线，验证通道策略回测可正常产出结果。
     */
    public void runChannelBacktestDemo() throws Exception {
        BinanceBacktestService service = buildService();

        BinanceBacktestParam param = new BinanceBacktestParam();
        param.strategyName = "binanceChannel";
        param.symbol = "ETHUSDT";
        param.text = "15m";
        param.beginDate = "2025-01-01";
        param.endDate = "2025-01-03";
        param.initialCapital = new BigDecimal("10000");
        param.feeRatePct = new BigDecimal("0.04");
        param.fallbackStopLossPct = new BigDecimal("1.5");
        param.fallbackTakeProfitPct = new BigDecimal("3.0");
        param.maxHoldBars = 24;

        List<TTbookOhlc> ohlcList = buildChannelDemoBars();
        BinanceBacktestModels.BacktestResult result = service.runSingleStrategy("binanceChannel", param, ohlcList);

        System.out.println("strategy=" + result.strategyName);
        System.out.println("tradeCount=" + result.tradeCount);
        System.out.println("winCount=" + result.winCount);
        System.out.println("lossCount=" + result.lossCount);
        System.out.println("winRate=" + result.winRate);
        System.out.println("totalReturnPct=" + result.totalReturnPct);
        System.out.println("maxDrawdownPct=" + result.maxDrawdownPct);
        System.out.println("finalCapital=" + result.finalCapital);

        if (result.tradeList != null) {
            for (BinanceBacktestModels.TradeRecord tradeRecord : result.tradeList) {
                System.out.println(
                        "trade side=" + tradeRecord.side
                                + ", entry=" + tradeRecord.entryPrice
                                + ", exit=" + tradeRecord.exitPrice
                                + ", holdBars=" + tradeRecord.holdBars
                                + ", returnPct=" + tradeRecord.returnPct
                                + ", reason=" + tradeRecord.exitReason);
            }
        }
    }

    /**
     * 手工组装回测服务，避免依赖 Spring 上下文。
     */
    private BinanceBacktestService buildService() throws Exception {
        BinanceBacktestService service = new BinanceBacktestService();
        setField(service, "supportService", new BinanceBacktestSupportService());
        setField(service, "strategyService", new BinanceBacktestStrategyService(Arrays.asList(
                new BinanceChannelBacktestStrategy(),
                new BinanceRangeBacktestStrategy(),
                new BinanceTrendBacktestStrategy()
        )));
        setField(service, "tradeService", new BinanceBacktestTradeService());
        setField(service, "metricService", new BinanceBacktestMetricService());
        return service;
    }

    /**
     * 构造一段先上破后下破的 15m K 线，用于触发通道策略开平仓。
     */
    private List<TTbookOhlc> buildChannelDemoBars() {
        List<TTbookOhlc> list = new ArrayList<>();
        LocalDateTime begin = LocalDateTime.of(2025, 1, 1, 0, 0);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        double close = 2000.0;
        for (int i = 0; i < 80; i++) {
            if (i < 25) {
                close += 2.0;
            } else if (i < 45) {
                close += 10.0;
            } else if (i < 60) {
                close -= 4.0;
            } else {
                close -= 12.0;
            }

            double open = close + (i % 2 == 0 ? -1.5 : 1.2);
            double high = Math.max(open, close) + 2.5;
            double low = Math.min(open, close) - 2.5;

            TTbookOhlc ohlc = new TTbookOhlc();
            ohlc.securityid = "ETHUSDT";
            ohlc.text = "15M";
            ohlc.open = price(open);
            ohlc.high = price(high);
            ohlc.low = price(low);
            ohlc.close = price(close);
            ohlc.volume = new BigDecimal("100");
            ohlc.starttime = begin.plusMinutes(i * 15L).format(formatter);
            list.add(ohlc);
        }
        return list;
    }

    /**
     * 统一价格精度。
     */
    private BigDecimal price(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 通过反射注入回测服务依赖。
     */
    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
