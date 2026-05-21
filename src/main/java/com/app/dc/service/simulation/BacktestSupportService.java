package com.app.dc.service.simulation;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class BacktestSupportService {

    private static final Map<String, Integer> DEFAULT_MAX_BARS_BY_TEXT = buildDefaultMaxBarsByText();

    @Value("${strategy.backtest.maxBarsByText:1m:60000,5m:80000,15m:120000,30m:120000,1h:120000,1d:3650}")
    private String configuredMaxBarsByText;

    public String normalizeStrategyName(String strategyName) {
        if (StringUtils.isBlank(strategyName)) {
            return "all";
        }
        String value = strategyName.trim();
        if ("range".equalsIgnoreCase(value)) {
            return "binanceRange";
        }
        if ("rangeguarded".equalsIgnoreCase(value) || "range_guarded".equalsIgnoreCase(value)) {
            return "binanceRangeGuarded";
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
        if ("breakoutretestcontinuationtrend".equalsIgnoreCase(value)
                || "breakout_retest_continuation_trend".equalsIgnoreCase(value)
                || "breakoutretestcontinuationsignal".equalsIgnoreCase(value)
                || "brct".equalsIgnoreCase(value)) {
            return "breakoutRetestContinuationTrend";
        }
        if ("emapullbackbuy".equalsIgnoreCase(value)
                || "ema_pullback_buy".equalsIgnoreCase(value)
                || "emapullbackbuysignal".equalsIgnoreCase(value)
                || "epb".equalsIgnoreCase(value)) {
            return "emaPullbackBuy";
        }
        if ("trendrestart".equalsIgnoreCase(value)
                || "trend_restart".equalsIgnoreCase(value)
                || "trendrestartsignal".equalsIgnoreCase(value)
                || "trs".equalsIgnoreCase(value)) {
            return "trendRestart";
        }
        if ("smallrangebreakout".equalsIgnoreCase(value)
                || "small_range_breakout".equalsIgnoreCase(value)
                || "smallrangebreakoutsignal".equalsIgnoreCase(value)
                || "srb".equalsIgnoreCase(value)) {
            return "smallRangeBreakout";
        }
        if ("strongmomentumcontinuation".equalsIgnoreCase(value)
                || "strong_momentum_continuation".equalsIgnoreCase(value)
                || "strongmomentumcontinuationsignal".equalsIgnoreCase(value)
                || "smc".equalsIgnoreCase(value)) {
            return "strongMomentumContinuation";
        }
        if ("trendpullbackrecovery".equalsIgnoreCase(value)
                || "trend_pullback_recovery".equalsIgnoreCase(value)
                || "tpr".equalsIgnoreCase(value)) {
            return "trendPullbackRecovery";
        }
        if ("bollinger".equalsIgnoreCase(value) || "boll".equalsIgnoreCase(value)) {
            return "bollingerMeanReversion";
        }
        if ("bollingerbias".equalsIgnoreCase(value)
                || "bollinger_pullback_bias".equalsIgnoreCase(value)
                || "bollingerpullbackbias".equalsIgnoreCase(value)
                || "boll_bias".equalsIgnoreCase(value)) {
            return "bollingerPullbackBias";
        }
        if ("breakoutretestcontinuation".equalsIgnoreCase(value)
                || "breakout_retest_continuation".equalsIgnoreCase(value)
                || "brc".equalsIgnoreCase(value)) {
            return "breakoutRetestContinuation";
        }
        if ("compressionbreak".equalsIgnoreCase(value)
                || "compression_break".equalsIgnoreCase(value)
                || "cmp".equalsIgnoreCase(value)) {
            return "compressionBreak";
        }
        if ("failedbreakreversal".equalsIgnoreCase(value)
                || "failed_break_reversal".equalsIgnoreCase(value)
                || "fbr".equalsIgnoreCase(value)) {
            return "failedBreakReversal";
        }
        if ("impulsereclaim".equalsIgnoreCase(value)
                || "impulse_reclaim".equalsIgnoreCase(value)
                || "imp".equalsIgnoreCase(value)) {
            return "impulseReclaim";
        }
        if ("rsikdj".equalsIgnoreCase(value) || "rsi_kdj".equalsIgnoreCase(value)) {
            return "rsiKdjReversion";
        }
        if ("donchian".equalsIgnoreCase(value)) {
            return "donchianReversion";
        }
        if ("vwap".equalsIgnoreCase(value)) {
            return "vwapReversion";
        }
        if ("zscore".equalsIgnoreCase(value) || "z_score".equalsIgnoreCase(value)) {
            return "zscoreReversion";
        }
        if ("grid".equalsIgnoreCase(value)) {
            return "gridRange";
        }
        if ("atrchannel".equalsIgnoreCase(value) || "atr_channel".equalsIgnoreCase(value)) {
            return "atrChannelReversion";
        }
        if ("atrchannelbias".equalsIgnoreCase(value)
                || "atr_channel_bias".equalsIgnoreCase(value)
                || "atrchannelbiasreversion".equalsIgnoreCase(value)
                || "atr_bias".equalsIgnoreCase(value)) {
            return "atrChannelBiasReversion";
        }
        if ("orderbook".equalsIgnoreCase(value) || "order_book".equalsIgnoreCase(value)) {
            return "orderBookImbalanceReversion";
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
            case "1D":
                return Duration.ofDays(1);
            default:
                throw new IllegalArgumentException("unsupported text: " + text);
        }
    }

    public int resolveMaxBars(String text) {
        String normalized = normalizeText(text).toLowerCase(Locale.ROOT);
        Map<String, Integer> configured = parseMaxBarsByText(configuredMaxBarsByText);
        Integer value = configured.get(normalized);
        if (value != null && value.intValue() > 0) {
            return value.intValue();
        }
        value = DEFAULT_MAX_BARS_BY_TEXT.get(normalized);
        if (value != null && value.intValue() > 0) {
            return value.intValue();
        }
        return 120000;
    }

    public int estimateBarCount(String text, String beginDate, String endDate) {
        if (StringUtils.isBlank(beginDate) || StringUtils.isBlank(endDate)) {
            return 0;
        }
        LocalDate begin = LocalDate.parse(beginDate.trim());
        LocalDate end = LocalDate.parse(endDate.trim());
        if (begin.isAfter(end)) {
            LocalDate swap = begin;
            begin = end;
            end = swap;
        }
        long days = ChronoUnit.DAYS.between(begin, end) + 1L;
        return (int) Math.max(0L, days * resolveBarsPerDay(text));
    }

    public int resolveMaxDays(String text) {
        int maxBars = resolveMaxBars(text);
        int barsPerDay = resolveBarsPerDay(text);
        if (maxBars <= 0 || barsPerDay <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.floor((double) (maxBars - 1) / (double) barsPerDay) + 1);
    }

    public void validateBacktestRange(String text, String beginDate, String endDate) {
        int estimatedBars = estimateBarCount(text, beginDate, endDate);
        int maxBars = resolveMaxBars(text);
        if (estimatedBars > maxBars) {
            throw new IllegalArgumentException(String.format(
                    "%s 时间段太长，预计回测 K 线 %d 根，超过上限 %d 根。请缩短到最近 %d 天以内。",
                    normalizeText(text), estimatedBars, maxBars, resolveMaxDays(text)));
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

    private int resolveBarsPerDay(String text) {
        String value = normalizeText(text);
        switch (value) {
            case "1M":
                return 1440;
            case "5M":
                return 288;
            case "15M":
                return 96;
            case "30M":
                return 48;
            case "1H":
                return 24;
            case "1D":
                return 1;
            default:
                throw new IllegalArgumentException("unsupported text: " + text);
        }
    }

    private Map<String, Integer> parseMaxBarsByText(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Collections.emptyMap();
        }
        Map<String, Integer> result = new HashMap<String, Integer>();
        String[] parts = raw.split("[,|\\s]+");
        for (String part : parts) {
            String item = StringUtils.trimToEmpty(part);
            if (item.isEmpty()) {
                continue;
            }
            int separator = item.indexOf(':');
            if (separator <= 0 || separator >= item.length() - 1) {
                continue;
            }
            String key = item.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String valueText = item.substring(separator + 1).trim();
            try {
                int value = Integer.parseInt(valueText);
                if (value > 0) {
                    result.put(key, value);
                }
            } catch (Exception ignore) {
            }
        }
        return result;
    }

    private static Map<String, Integer> buildDefaultMaxBarsByText() {
        Map<String, Integer> result = new HashMap<String, Integer>();
        result.put("1m", 60000);
        result.put("5m", 80000);
        result.put("15m", 120000);
        result.put("30m", 120000);
        result.put("1h", 120000);
        result.put("1d", 3650);
        return result;
    }
}
