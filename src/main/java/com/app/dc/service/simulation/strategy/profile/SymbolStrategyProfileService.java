package com.app.dc.service.simulation.strategy.profile;

import com.app.common.utils.JsonUtils;
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

/** Loads deterministic static overrides keyed by exact symbol and timeframe. */
@Service
public class SymbolStrategyProfileService {
    @Value("${backtest.strategy.profileDir:./config/strategy-profiles}")
    private String profileDir;
    private Map<String, SymbolStrategyProfile> profiles = Collections.emptyMap();

    @PostConstruct
    public void load() {
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

    public boolean isStrategyEnabled(String symbol, String timeframe, String strategyName) {
        SymbolStrategyProfile.StrategyOverride override =
                findOverride(symbol, timeframe, strategyName);
        return override == null || override.enabled == null || override.enabled;
    }

    public Double takeProfitPct(String symbol, String timeframe, String strategyName) {
        SymbolStrategyProfile.StrategyOverride override =
                findOverride(symbol, timeframe, strategyName);
        return override == null ? null : override.takeProfitPct;
    }

    public double minimumTriggerRangeAtr(String symbol, String timeframe,
                                         String strategyName) {
        SymbolStrategyProfile.StrategyOverride override =
                findOverride(symbol, timeframe, strategyName);
        return override == null || override.minimumTriggerRangeAtr == null
                ? 0.0 : override.minimumTriggerRangeAtr;
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
        return profile.strategies.get(strategyName.trim().toLowerCase());
    }

    private String key(String symbol, String timeframe) {
        return (symbol == null ? "" : symbol.trim().toUpperCase()) + "|"
                + (timeframe == null ? "" : timeframe.trim().toUpperCase());
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
