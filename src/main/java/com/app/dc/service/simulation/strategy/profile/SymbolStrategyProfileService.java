package com.app.dc.service.simulation.strategy.profile;

import com.app.common.utils.JsonUtils;
import com.app.dc.service.simulation.strategy.range.BinanceRangeSettings;
import com.app.dc.service.simulation.strategy.trend.BinanceTrendSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import com.app.dc.service.simulation.strategy.SymbolStrategyNames;

/** Loads deterministic static overrides keyed by exact symbol and timeframe. */
@Service
public class SymbolStrategyProfileService {
    @Value("${backtest.strategy.profileDir:./config/strategy-profiles}")
    private String profileDir;
    private Map<String, SymbolStrategyProfile> profiles = Collections.emptyMap();

    @PostConstruct
    public void load() {
        if (profileDir != null && profileDir.startsWith("classpath:")) {
            loadClasspathProfiles(profileDir.substring("classpath:".length()));
            return;
        }
        Path directory = Paths.get(profileDir);
        if (!Files.isDirectory(directory))
            throw new IllegalStateException("strategy profile directory does not exist: " + profileDir);
        Map<String, SymbolStrategyProfile> loaded =
                new LinkedHashMap<String, SymbolStrategyProfile>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted().forEach(path -> loadOne(path, loaded));
        } catch (Exception e) {
            throw new IllegalStateException("cannot load strategy profiles: " + profileDir, e);
        }
        profiles = Collections.unmodifiableMap(loaded);
    }

    private void loadClasspathProfiles(String root) {
        String normalized = root == null ? "" : root;
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        Map<String, SymbolStrategyProfile> loaded = new LinkedHashMap<String, SymbolStrategyProfile>();
        for (String name : new String[]{"BTCUSDT.json", "ETHUSDT.json", "SOLUSDT.json"}) {
            String resource = normalized + (normalized.endsWith("/") ? "" : "/") + name;
            try (java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                if (in == null) throw new IllegalStateException("strategy profile resource not found: " + resource);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
                SymbolStrategyProfile profile = JsonUtils.Deserialize(
                        new String(out.toByteArray(), StandardCharsets.UTF_8), SymbolStrategyProfile.class);
                Path identity = Paths.get(name);
                validate(profile, identity);
                String profileKey = key(profile.symbol, profile.timeframe);
                if (loaded.put(profileKey, profile) != null)
                    throw new IllegalStateException("duplicate strategy profile: " + profileKey);
            } catch (Exception e) {
                throw new IllegalStateException("cannot load strategy profile: " + resource, e);
            }
        }
        profiles = Collections.unmodifiableMap(loaded);
    }

    public boolean isStrategyEnabled(String symbol, String timeframe, String strategyName) {
        SymbolStrategyProfile.StrategyOverride override =
                findOverride(symbol, timeframe, strategyName);
        return override == null || override.enabled == null || override.enabled;
    }

    public boolean isSideEnabled(String symbol, String timeframe,
                                 String strategyName, String side) {
        SymbolStrategyProfile.StrategyOverride override =
                findOverride(symbol, timeframe, strategyName);
        if (override == null || side == null) return true;
        if ("BUY".equalsIgnoreCase(side))
            return override.buyEnabled == null || override.buyEnabled;
        if ("SELL".equalsIgnoreCase(side))
            return override.sellEnabled == null || override.sellEnabled;
        return true;
    }

    public Double takeProfitPct(String symbol, String timeframe, String strategyName) {
        SymbolStrategyProfile.StrategyOverride override =
                findOverride(symbol, timeframe, strategyName);
        return override == null ? null : override.takeProfitPct;
    }

    public boolean binanceTrendBuyEnabled(String symbol,String timeframe){
        return binanceTrendBuyEnabled(symbol,timeframe,"binanceTrend");
    }

    public boolean binanceTrendBuyEnabled(String symbol,String timeframe,String strategyName){
        SymbolStrategyProfile.StrategyOverride value=findOverride(symbol,timeframe,strategyName);
        return value==null||value.binanceTrendBuyEnabled==null||value.binanceTrendBuyEnabled;
    }

    public double minimumTriggerRangeAtr(String symbol, String timeframe,
                                         String strategyName) {
        SymbolStrategyProfile.StrategyOverride override =
                findOverride(symbol, timeframe, strategyName);
        return override == null || override.minimumTriggerRangeAtr == null
                ? 0.0 : override.minimumTriggerRangeAtr;
    }

    public double minimumBuyRecoveryBodyAtr(String symbol, String timeframe,
                                            String strategyName) {
        SymbolStrategyProfile.StrategyOverride override =
                findOverride(symbol, timeframe, strategyName);
        return override == null || override.minimumBuyRecoveryBodyAtr == null
                ? 0.0 : override.minimumBuyRecoveryBodyAtr;
    }

    public double minimumBuyCloseLocation(String symbol, String timeframe,
                                          String strategyName) {
        SymbolStrategyProfile.StrategyOverride override =
                findOverride(symbol, timeframe, strategyName);
        return override == null || override.minimumBuyCloseLocation == null
                ? 0.65 : override.minimumBuyCloseLocation;
    }

    public BinanceRangeSettings binanceRangeSettings(String symbol,
                                                     String timeframe,
                                                     String strategyName) {
        SymbolStrategyProfile.StrategyOverride value =
                findOverride(symbol, timeframe, strategyName);
        double trigger = value == null || value.minimumTriggerRangeAtr == null
                ? 0.0 : value.minimumTriggerRangeAtr;
        double buyBody = value == null || value.minimumBuyRecoveryBodyAtr == null
                ? 0.0 : value.minimumBuyRecoveryBodyAtr;
        double buyLocation = value == null || value.minimumBuyCloseLocation == null
                ? .65 : value.minimumBuyCloseLocation;
        if (value == null || value.rangeStateMachineEnabled == null
                || !value.rangeStateMachineEnabled)
            return BinanceRangeSettings.legacy(trigger, buyBody, buyLocation);
        return new BinanceRangeSettings(true,
                integer(value.rangeLookbackBars, 20),
                integer(value.rangeStabilityBars, 6),
                integer(value.rangeMinimumMidCrosses, 3),
                decimal(value.minimumBoxRangeAtr, 3.5),
                decimal(value.maximumBoxRangeAtr, 5.5),
                decimal(value.rangeEdgeZoneRatio, .15),
                integer(value.rangeTouchValidityBars, 3),
                integer(value.rangeConfirmedValidityBars, 3),
                value.rangeNextBarConfirmationRequired == null
                        || value.rangeNextBarConfirmationRequired,
                decimal(value.rangeMaximumBreakoutAtr, .50),
                decimal(value.rangeMinimumRewardRisk, 1.40),
                decimal(value.rangeStopPaddingAtr, .25),
                decimal(value.rangeTargetExtensionRatio, 0),
                trigger, buyBody, buyLocation);
    }

    public BinanceTrendSettings binanceTrendSettings(String symbol,
                                                     String timeframe) {
        return binanceTrendSettings(symbol,timeframe,"binanceTrend");
    }

    public BinanceTrendSettings binanceTrendSettings(String symbol,
                                                     String timeframe,
                                                     String strategyName) {
        SymbolStrategyProfile.StrategyOverride value =
                findOverride(symbol, timeframe, strategyName);
        if (value == null || value.trendLifecycleEnabled == null
                || !value.trendLifecycleEnabled)
            return BinanceTrendSettings.legacy();
        return new BinanceTrendSettings(true,
                integer(value.trendImpulseLookbackBars, 96),
                integer(value.trendMinimumPullbackBars, 3),
                decimal(value.trendMinimumRetracement, .236),
                decimal(value.trendMaximumRetracement, .618),
                decimal(value.trendInvalidationRetracement, .786),
                integer(value.trendMaximumLifecycleBars, 384),
                integer(value.trendTriggerValidityBars, 4),
                decimal(value.trendMinimumBodyAtr, .30),
                decimal(value.trendMinimumVolumeRatio, .80),
                decimal(value.trendMinimumCloseLocation, .65),
                decimal(value.trendMaximumTriggerExtensionAtr, 1),
                decimal(value.trendStopPaddingAtr, .50),
                decimal(value.trendMinimumStopAtr, 2),
                decimal(value.trendMaximumStopAtr, 4),
                decimal(value.trendTrailActivationAtr, 2),
                decimal(value.trendInitialTrailAtr, 3.5),
                decimal(value.trendMatureTrailActivationAtr, 4),
                decimal(value.trendMatureTrailAtr, 3));
    }

    public String profileVersion(String symbol, String timeframe) {
        SymbolStrategyProfile profile = profiles.get(key(symbol, timeframe));
        return profile == null || !profile.enabled ? null : profile.profileVersion;
    }

    private void loadOne(Path path, Map<String, SymbolStrategyProfile> loaded) {
        try {
            SymbolStrategyProfile profile = JsonUtils.Deserialize(
                    new String(Files.readAllBytes(path), StandardCharsets.UTF_8),
                    SymbolStrategyProfile.class);
            validate(profile, path);
            String key = key(profile.symbol, profile.timeframe);
            if (loaded.put(key, profile) != null)
                throw new IllegalStateException("duplicate strategy profile: " + key);
        } catch (Exception e) {
            throw new IllegalStateException("invalid strategy profile: " + path, e);
        }
    }

    private void validate(SymbolStrategyProfile profile, Path path) {
        if (profile == null || blank(profile.symbol) || blank(profile.timeframe)
                || blank(profile.profileVersion))
            throw new IllegalArgumentException("missing profile identity: " + path);
        if (profile.strategies == null)
            profile.strategies =
                    new LinkedHashMap<String, SymbolStrategyProfile.StrategyOverride>();
        Map<String, SymbolStrategyProfile.StrategyOverride> normalized =
                new LinkedHashMap<String, SymbolStrategyProfile.StrategyOverride>();
        for (Map.Entry<String, SymbolStrategyProfile.StrategyOverride> entry
                : profile.strategies.entrySet()) {
            if (blank(entry.getKey()) || entry.getValue() == null)
                throw new IllegalArgumentException("invalid strategy override: " + path);
            SymbolStrategyProfile.StrategyOverride override = entry.getValue();
            if (override.takeProfitPct != null
                    && (!Double.isFinite(override.takeProfitPct)
                    || override.takeProfitPct < 0 || override.takeProfitPct > 100))
                throw new IllegalArgumentException("takeProfitPct must be between 0 and 100: "
                        + entry.getKey());
            if (override.minimumTriggerRangeAtr != null
                    && (!Double.isFinite(override.minimumTriggerRangeAtr)
                    || override.minimumTriggerRangeAtr < 0
                    || override.minimumTriggerRangeAtr > 5))
                throw new IllegalArgumentException(
                        "minimumTriggerRangeAtr must be between 0 and 5: "
                                + entry.getKey());
            if (override.minimumBuyRecoveryBodyAtr != null
                    && (!Double.isFinite(override.minimumBuyRecoveryBodyAtr)
                    || override.minimumBuyRecoveryBodyAtr < 0
                    || override.minimumBuyRecoveryBodyAtr > 3))
                throw new IllegalArgumentException(
                        "minimumBuyRecoveryBodyAtr must be between 0 and 3: "
                                + entry.getKey());
            if (override.minimumBuyCloseLocation != null
                    && (!Double.isFinite(override.minimumBuyCloseLocation)
                    || override.minimumBuyCloseLocation < 0.5
                    || override.minimumBuyCloseLocation > 1))
                throw new IllegalArgumentException(
                        "minimumBuyCloseLocation must be between 0.5 and 1: "
                                + entry.getKey());
            validateRangeSettings(override, entry.getKey());
            validateTrendSettings(override, entry.getKey());
            String strategyKey = entry.getKey().trim().toLowerCase();
            if (normalized.put(strategyKey, override) != null)
                throw new IllegalArgumentException("duplicate strategy override: " + entry.getKey());
        }
        profile.symbol = profile.symbol.trim().toUpperCase();
        profile.timeframe = profile.timeframe.trim().toUpperCase();
        profile.strategies = normalized;
    }

    private SymbolStrategyProfile.StrategyOverride findOverride(
            String symbol, String timeframe, String strategyName) {
        SymbolStrategyProfile profile = profiles.get(key(symbol, timeframe));
        if (profile == null || !profile.enabled || blank(strategyName)) return null;
        String requested=strategyName.trim().toLowerCase();
        SymbolStrategyProfile.StrategyOverride exact=profile.strategies.get(requested);
        if(exact!=null)return exact;
        String qualified=SymbolStrategyNames.qualify(
                SymbolStrategyNames.baseName(strategyName),symbol).toLowerCase();
        SymbolStrategyProfile.StrategyOverride symbolOwned=profile.strategies.get(qualified);
        if(symbolOwned!=null)return symbolOwned;
        return profile.strategies.get(
                SymbolStrategyNames.baseName(strategyName).toLowerCase());
    }

    private String key(String symbol, String timeframe) {
        return (symbol == null ? "" : symbol.trim().toUpperCase()) + "|"
                + (timeframe == null ? "" : timeframe.trim().toUpperCase());
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void validateRangeSettings(SymbolStrategyProfile.StrategyOverride value,
                                       String strategyName) {
        positiveInteger(value.rangeLookbackBars, 10, 200,
                "rangeLookbackBars", strategyName);
        positiveInteger(value.rangeStabilityBars, 1, 50,
                "rangeStabilityBars", strategyName);
        positiveInteger(value.rangeMinimumMidCrosses, 1, 20,
                "rangeMinimumMidCrosses", strategyName);
        positiveInteger(value.rangeTouchValidityBars, 1, 20,
                "rangeTouchValidityBars", strategyName);
        positiveInteger(value.rangeConfirmedValidityBars, 1, 10,
                "rangeConfirmedValidityBars", strategyName);
        decimalRange(value.minimumBoxRangeAtr, 0.1, 20,
                "minimumBoxRangeAtr", strategyName);
        decimalRange(value.maximumBoxRangeAtr, 0.1, 30,
                "maximumBoxRangeAtr", strategyName);
        if (value.minimumBoxRangeAtr != null && value.maximumBoxRangeAtr != null
                && value.minimumBoxRangeAtr >= value.maximumBoxRangeAtr)
            throw new IllegalArgumentException(
                    "minimumBoxRangeAtr must be below maximumBoxRangeAtr: "
                            + strategyName);
        decimalRange(value.rangeEdgeZoneRatio, .01, .40,
                "rangeEdgeZoneRatio", strategyName);
        decimalRange(value.rangeMaximumBreakoutAtr, .05, 3,
                "rangeMaximumBreakoutAtr", strategyName);
        decimalRange(value.rangeMinimumRewardRisk, .5, 10,
                "rangeMinimumRewardRisk", strategyName);
        decimalRange(value.rangeStopPaddingAtr, 0, 3,
                "rangeStopPaddingAtr", strategyName);
        decimalRange(value.rangeTargetExtensionRatio, 0, .40,
                "rangeTargetExtensionRatio", strategyName);
    }

    private void validateTrendSettings(SymbolStrategyProfile.StrategyOverride value,
                                       String strategyName) {
        positiveInteger(value.trendImpulseLookbackBars, 20, 500,
                "trendImpulseLookbackBars", strategyName);
        positiveInteger(value.trendMinimumPullbackBars, 1, 50,
                "trendMinimumPullbackBars", strategyName);
        positiveInteger(value.trendMaximumLifecycleBars, 20, 2000,
                "trendMaximumLifecycleBars", strategyName);
        positiveInteger(value.trendTriggerValidityBars, 1, 20,
                "trendTriggerValidityBars", strategyName);
        decimalRange(value.trendMinimumRetracement, 0, 1,
                "trendMinimumRetracement", strategyName);
        decimalRange(value.trendMaximumRetracement, 0, 1,
                "trendMaximumRetracement", strategyName);
        decimalRange(value.trendInvalidationRetracement, 0, 1.5,
                "trendInvalidationRetracement", strategyName);
        if(value.trendMinimumRetracement!=null&&value.trendMaximumRetracement!=null
                &&value.trendMinimumRetracement>=value.trendMaximumRetracement)
            throw new IllegalArgumentException("trend retracement bounds invalid: "+strategyName);
        decimalRange(value.trendMinimumBodyAtr, 0, 3,"trendMinimumBodyAtr",strategyName);
        decimalRange(value.trendMinimumVolumeRatio, 0, 5,"trendMinimumVolumeRatio",strategyName);
        decimalRange(value.trendMinimumCloseLocation, .5, 1,"trendMinimumCloseLocation",strategyName);
        decimalRange(value.trendMaximumTriggerExtensionAtr, 0, 5,"trendMaximumTriggerExtensionAtr",strategyName);
        decimalRange(value.trendStopPaddingAtr, 0, 3,
                "trendStopPaddingAtr", strategyName);
        decimalRange(value.trendMinimumStopAtr, 0, 10,"trendMinimumStopAtr",strategyName);
        decimalRange(value.trendMaximumStopAtr, 0, 20,"trendMaximumStopAtr",strategyName);
        decimalRange(value.trendTrailActivationAtr, 0, 20,"trendTrailActivationAtr",strategyName);
        decimalRange(value.trendInitialTrailAtr, 0, 20,"trendInitialTrailAtr",strategyName);
        decimalRange(value.trendMatureTrailActivationAtr, 0, 20,"trendMatureTrailActivationAtr",strategyName);
        decimalRange(value.trendMatureTrailAtr, 0, 20,"trendMatureTrailAtr",strategyName);
    }

    private void positiveInteger(Integer value, int min, int max,
                                 String field, String strategyName) {
        if (value != null && (value < min || value > max))
            throw new IllegalArgumentException(field + " must be between "
                    + min + " and " + max + ": " + strategyName);
    }

    private void decimalRange(Double value, double min, double max,
                              String field, String strategyName) {
        if (value != null && (!Double.isFinite(value) || value < min || value > max))
            throw new IllegalArgumentException(field + " must be between "
                    + min + " and " + max + ": " + strategyName);
    }

    private int integer(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private double decimal(Double value, double fallback) {
        return value == null ? fallback : value;
    }
}
