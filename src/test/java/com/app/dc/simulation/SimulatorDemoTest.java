package com.app.dc.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestMetricService;
import com.app.dc.strategy.core.StrategyRuntimeModels;
import com.app.dc.service.simulation.BacktestService;
import com.app.dc.strategy.core.StrategyRegistry;
import com.app.dc.service.simulation.BacktestSupportService;
import com.app.dc.service.simulation.BacktestTradeService;
//import com.app.dc.strategy.core.strategy.BinanceChannelStrategyAlgorithm;
//import com.app.dc.strategy.core.strategy.BinanceRangeStrategyAlgorithm;
//import com.app.dc.strategy.core.strategy.BinanceTrendStrategyAlgorithm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SimulatorDemoTest {

    public static void main(String[] args) throws Exception {
        SimulatorDemoTest test = new SimulatorDemoTest();
        test.runChannelBacktestDemo();
    }

    public void runChannelBacktestDemo() throws Exception {
        BacktestService service = buildService();

        BacktestParam param = new BacktestParam();
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
        StrategyRuntimeModels.StrategyRunResult result = service.runSingleStrategy("binanceChannel", param, ohlcList);

        System.out.println("strategy=" + result.strategyName);
        System.out.println("tradeCount=" + result.tradeCount);
        System.out.println("winCount=" + result.winCount);
        System.out.println("lossCount=" + result.lossCount);
        System.out.println("winRate=" + result.winRate);
        System.out.println("totalReturnPct=" + result.totalReturnPct);
        System.out.println("maxDrawdownPct=" + result.maxDrawdownPct);
        System.out.println("finalCapital=" + result.finalCapital);

        if (result.tradeList != null) {
            for (StrategyRuntimeModels.TradeRecord tradeRecord : result.tradeList) {
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

    private BacktestService buildService() throws Exception {
        BacktestService service = new BacktestService();
        setField(service, "supportService", new BacktestSupportService());
        setField(service, "strategyService", new StrategyRegistry(Arrays.asList(
//                new BinanceChannelStrategyAlgorithm(),
//                new BinanceRangeStrategyAlgorithm(),
//                new BinanceTrendStrategyAlgorithm()
        )));
        setField(service, "tradeService", new BacktestTradeService());
        setField(service, "metricService", new BacktestMetricService());
        return service;
    }

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

    private BigDecimal price(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
