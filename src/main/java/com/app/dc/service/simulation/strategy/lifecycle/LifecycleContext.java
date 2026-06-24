package com.app.dc.service.simulation.strategy.lifecycle;

import java.util.Collections;
import java.util.List;

/**
 * 生命周期决策所需的上下文，聚合样本、配置和状态。
 */
public class LifecycleContext {

    private final String strategyName;
    private final String symbol;
    private final String text;
    private final List<LifecycleIndicatorSample> samples;
    private final LifecycleConfig config;
    private final LifecycleState state;

    /**
     * 创建一次生命周期决策上下文。
     */
    public LifecycleContext(String strategyName, String symbol, String text,
                            List<LifecycleIndicatorSample> samples,
                            LifecycleConfig config,
                            LifecycleState state) {
        this.strategyName = strategyName;
        this.symbol = symbol;
        this.text = text;
        this.samples = samples == null ? Collections.<LifecycleIndicatorSample>emptyList() : samples;
        this.config = config;
        this.state = state;
    }

    /**
     * 获取策略名称。
     */
    public String getStrategyName() {
        return strategyName;
    }

    /**
     * 获取交易品种。
     */
    public String getSymbol() {
        return symbol;
    }

    /**
     * 获取K线周期。
     */
    public String getText() {
        return text;
    }

    /**
     * 获取指标样本列表。
     */
    public List<LifecycleIndicatorSample> getSamples() {
        return samples;
    }

    /**
     * 获取生命周期策略配置。
     */
    public LifecycleConfig getConfig() {
        return config;
    }

    /**
     * 获取生命周期策略状态。
     */
    public LifecycleState getState() {
        return state;
    }

    /**
     * 获取上一根K线的指标样本。
     */
    public LifecycleIndicatorSample previous() {
        return samples.get(samples.size() - 2);
    }

    /**
     * 获取当前K线的指标样本。
     */
    public LifecycleIndicatorSample current() {
        return samples.get(samples.size() - 1);
    }
}
