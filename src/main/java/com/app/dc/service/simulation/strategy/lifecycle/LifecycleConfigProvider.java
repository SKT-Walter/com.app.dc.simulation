package com.app.dc.service.simulation.strategy.lifecycle;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供回测使用的生命周期策略默认配置。
 */
@Service
public class LifecycleConfigProvider {

    private static final String STRATEGY_NAME = "difDeaLifecycle";
    private static final boolean LAUNCH_ENTRY_AFTER_MACD_EXPAND_ENABLED = true;
    private final Map<String, LifecycleConfig> configMap = new ConcurrentHashMap<String, LifecycleConfig>();

    /**
     * 初始化内置品种配置。
     */
    public LifecycleConfigProvider() {
        put("ETHUSDT", "5M", 0.30, 0.5);
        put("SOLUSDT", "5M", 0.015, 0.5);
        put("BTCUSDT", "5M", 8.0, 0.5);
        put("BNBUSDT", "5M", 0.08, 0.5);
        put("ADAUSDT", "5M", 0.00008, 0.5);
    }

    /**
     * 获取指定品种和周期的生命周期配置。
     */
    public LifecycleConfig getConfig(String symbol, String text) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedText = normalizeText(text);
        LifecycleConfig config = configMap.get(key(normalizedSymbol, normalizedText));
        if (config != null) {
            return applyRuntimeOverrides(config);
        }
        return applyRuntimeOverrides(new LifecycleConfig(STRATEGY_NAME, normalizedSymbol, normalizedText, 0.0, 0.0,
                5, 4, 3, 2, true, 5, 0.5, 24, 0.0, 12, 3, 3, 5, 3,
                isLaunchEntryAfterMacdExpandEnabled()));
    }

    /**
     * 写入一个内置品种配置。
     */
    private void put(String symbol, String text, double difDeaBondThreshold,
                     double compressionStddevThreshold) {
        LifecycleConfig config = new LifecycleConfig(STRATEGY_NAME, normalizeSymbol(symbol), normalizeText(text),
                difDeaBondThreshold, 0.0, 5, 4, 3, 2, true, 5,
                compressionStddevThreshold, 24, 0.0, 12, 3, 3, 5, 3,
                isLaunchEntryAfterMacdExpandEnabled());
        configMap.put(key(config.getSymbol(), config.getText()), config);
    }

    /**
     * 为回测临时覆盖标准差过滤阈值，便于批量调参。
     */
    private LifecycleConfig applyRuntimeOverrides(LifecycleConfig config) {
        if (config == null) {
            return null;
        }
        double stddevOverride = readDoubleOverride("ddl.compression.stddevThreshold");
        if (Double.isNaN(stddevOverride)) {
            return config;
        }
        return new LifecycleConfig(config.getStrategyName(), config.getSymbol(), config.getText(),
                config.getDifDeaBondThreshold(), config.getReverseCrossBondThreshold(),
                config.getBondingWindow(), config.getBondingMinCount(),
                config.getWeaknessWindow(), config.getWeaknessMinCount(),
                config.isCompressionFilterEnabled(),
                config.getCompressionStddevWindow(),
                stddevOverride,
                config.getDonchianWindow(),
                config.getDonchianWidthThreshold(),
                config.getCrossCountWindow(),
                config.getCrossCountThreshold(),
                config.getLaunchMacdExpandBars(),
                config.getLaunchMaWindow(),
                config.getLaunchPriceConfirmBars(),
                config.isLaunchEntryAfterMacdExpandEnabled());
    }

    /**
     * 返回是否启用 MACD 放大后补开仓。
     */
    private boolean isLaunchEntryAfterMacdExpandEnabled() {
        return LAUNCH_ENTRY_AFTER_MACD_EXPAND_ENABLED;
    }

    /**
     * 读取回测系统属性中的浮点阈值，未配置时返回NaN。
     */
    private double readDoubleOverride(String key) {
        String raw = System.getProperty(key);
        if (StringUtils.isBlank(raw)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
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
