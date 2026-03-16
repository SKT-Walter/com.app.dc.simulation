package com.app.dc.service.simulation;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 回测公共工具服务。
 */
@Service
public class BinanceBacktestSupportService {

    /**
     * 统一策略名写法。
     */
    public String normalizeStrategyName(String strategyName) {
        if (StringUtils.isBlank(strategyName)) {
            return "all";
        }
        String value = strategyName.trim();
        if ("range".equalsIgnoreCase(value)) {
            return "binanceRange";
        }
        if ("channel".equalsIgnoreCase(value)) {
            return "binanceChannel";
        }
        if ("trend".equalsIgnoreCase(value)) {
            return "binanceTrend";
        }
        return value;
    }

    /**
     * 统一周期字符串。
     */
    public String normalizeText(String text) {
        return StringUtils.isBlank(text) ? text : text.trim().toUpperCase();
    }

    /**
     * 将周期字符串解析为 Duration。
     */
    public Duration resolveDuration(String text) {
        String value = normalizeText(text);
        switch (value) {
            case "1M":
                return Duration.ofMinutes(1);
            case "5M":
                return Duration.ofMinutes(5);
            case "15M":
                return Duration.ofMinutes(15);
            case "30M":
                return Duration.ofMinutes(30);
            case "1H":
                return Duration.ofHours(1);
            case "4H":
                return Duration.ofHours(4);
            case "1D":
                return Duration.ofDays(1);
            case "1W":
                return Duration.ofDays(7);
            default:
                throw new IllegalArgumentException("unsupported text: " + text);
        }
    }
}
