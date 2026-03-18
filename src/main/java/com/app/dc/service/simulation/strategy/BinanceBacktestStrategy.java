package com.app.dc.service.simulation.strategy;

import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import org.ta4j.core.BarSeries;

/**
 * 回测策略评估接口。
 */
public interface BinanceBacktestStrategy {

    /**
     * 策略名称（如 binanceRange/binanceChannel/binanceTrend）。
     */
    String getName();

    /**
     * 基于当前序列与最新 K 线评估信号。
     */
    Signal evaluate(String symbol, String text, BarSeries series, TTbookOhlc currentOhlc);
}
