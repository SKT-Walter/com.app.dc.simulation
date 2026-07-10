package com.app.dc.service.simulation.strategy.lifecycle;

import org.springframework.stereotype.Service;

/**
 * 提供生命周期策略的代码默认配置。
 */
@Service
public class LifecycleConfigProvider {

    private final LifecycleConfig defaultConfig = new LifecycleConfig();

    /**
     * 获取默认配置。
     */
    public LifecycleConfig getConfig(String symbol, String text) {
        return defaultConfig;
    }
}
