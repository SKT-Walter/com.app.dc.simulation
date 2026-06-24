package com.app.dc.service.simulation.strategy.lifecycle;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供回测用的生命周期策略默认配置。
 */
@Service
public class LifecycleConfigProvider {

    private static final String STRATEGY_NAME = "difDeaLifecycle";
    private final Map<String, LifecycleConfig> configMap = new ConcurrentHashMap<String, LifecycleConfig>();

    /**
     * 初始化内置品种配置，后续可替换为数据库配置。
     */
    public LifecycleConfigProvider() {
        put("ETHUSDT", "5M", 0.30);
        put("SOLUSDT", "5M", 0.015);
        put("BTCUSDT", "5M", 8.0);
        put("BNBUSDT", "5M", 0.08);
        put("ADAUSDT", "5M", 0.00008);
    }

    /**
     * 获取指定品种和周期的生命周期配置。
     */
    public LifecycleConfig getConfig(String symbol, String text) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedText = normalizeText(text);
        LifecycleConfig config = configMap.get(key(normalizedSymbol, normalizedText));
        if (config != null) {
            return config;
        }
        return new LifecycleConfig(STRATEGY_NAME, normalizedSymbol, normalizedText, 0.0, 5, 4, 3, 2);
    }

    /**
     * 写入一个内置品种配置。
     */
    private void put(String symbol, String text, double difDeaBondThreshold) {
        LifecycleConfig config = new LifecycleConfig(STRATEGY_NAME, normalizeSymbol(symbol), normalizeText(text),
                difDeaBondThreshold, 5, 4, 3, 2);
        configMap.put(key(config.getSymbol(), config.getText()), config);
    }

    /**
     * 生成配置缓存键。
     */
    private String key(String symbol, String text) {
        return normalizeSymbol(symbol) + "|" + normalizeText(text);
    }

    /**
     * 标准化交易品种。
     */
    private String normalizeSymbol(String symbol) {
        return StringUtils.trimToEmpty(symbol).toUpperCase(Locale.ROOT);
    }

    /**
     * 标准化K线周期。
     */
    private String normalizeText(String text) {
        return StringUtils.trimToEmpty(text).toUpperCase(Locale.ROOT);
    }
}
