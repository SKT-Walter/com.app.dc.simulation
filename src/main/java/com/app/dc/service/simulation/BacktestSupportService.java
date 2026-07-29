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
        if ("binanceRangeGuarded".equalsIgnoreCase(value)
                || "rangeguarded".equalsIgnoreCase(value)
                || "range_guarded".equalsIgnoreCase(value)) {
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
        if ("grid".equalsIgnoreCase(value) || "gridRange".equalsIgnoreCase(value)) {
            return "binanceRange";
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
