package com.app.dc.service.simulation;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class BacktestSupportService {

    public String normalizeStrategyName(String strategyName) {
        if (StringUtils.isBlank(strategyName)) {
            return "all";
        }
        String value = strategyName.trim();
        if ("range".equalsIgnoreCase(value)) {
            return "binanceRange";
        }
        if ("rangemacd".equalsIgnoreCase(value) || "range_macd".equalsIgnoreCase(value)) {
            return "binanceRangeMacd";
        }
        if ("channel".equalsIgnoreCase(value)) {
            return "binanceChannel";
        }
        if ("trend".equalsIgnoreCase(value)) {
            return "binanceTrend";
        }
        return value;
    }

    public String normalizeText(String text) {
        return StringUtils.isBlank(text) ? text : text.trim().toUpperCase();
    }

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

    public List<String> resolveSymbols(String symbols, String fallbackSymbol) {
        Set<String> result = new LinkedHashSet<>();
        String raw = StringUtils.defaultIfBlank(symbols, fallbackSymbol);
        if (StringUtils.isBlank(raw)) {
            result.add("ETHUSDT");
        } else {
            String[] parts = raw.split("[|,\\s]+");
            for (String part : parts) {
                String item = StringUtils.trimToEmpty(part);
                if (StringUtils.isNotBlank(item)) {
                    result.add(item.toUpperCase(Locale.ROOT));
                }
            }
        }
        if (result.isEmpty()) {
            result.add("ETHUSDT");
        }
        return new ArrayList<>(result);
    }
}
