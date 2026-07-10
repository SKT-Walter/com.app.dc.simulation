package com.app.dc.service.simulation.strategy.lifecycle;

import java.util.List;

/**
 * 生命周期决策需要的上下文。
 */
public class LifecycleContext {

    private final String symbol;
    private final String text;
    private final List<LifecycleIndicatorSample> samples;
    private final LifecycleConfig config;
    private final LifecycleState state;

    /**
     * 创建生命周期决策上下文。
     */
    public LifecycleContext(String symbol, String text, List<LifecycleIndicatorSample> samples,
                            LifecycleConfig config, LifecycleState state) {
        this.symbol = symbol == null ? "" : symbol;
        this.text = text == null ? "" : text;
        this.samples = samples;
        this.config = config;
        this.state = state;
    }

    /**
     * 获取品种。
     */
    public String getSymbol() { return symbol; }

    /**
     * 获取周期。
     */
    public String getText() { return text; }

    /**
     * 获取指标样本。
     */
    public List<LifecycleIndicatorSample> getSamples() { return samples; }

    /**
     * 获取配置。
     */
    public LifecycleConfig getConfig() { return config; }

    /**
     * 获取状态。
     */
    public LifecycleState getState() { return state; }

    /**
     * 获取当前样本。
     */
    public LifecycleIndicatorSample current() { return samples.get(samples.size() - 1); }

    /**
     * 获取上一根样本。
     */
    public LifecycleIndicatorSample previous() { return samples.get(samples.size() - 2); }
}
