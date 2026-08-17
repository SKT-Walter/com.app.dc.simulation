package com.app.dc.service.simulation.strategy;

/** Canonical names for symbol-owned strategy pools. */
public final class SymbolStrategyNames {
    public static final String ETH_SUFFIX = "ETH";
    public static final String SOL_SUFFIX = "SOL";
    public static final String BTC_SUFFIX = "BTC";

    private SymbolStrategyNames() {}

    public static String baseName(String strategyName) {
        if (strategyName == null) return null;
        if (strategyName.endsWith(ETH_SUFFIX))
            return strategyName.substring(0, strategyName.length() - ETH_SUFFIX.length());
        if (strategyName.endsWith(SOL_SUFFIX))
            return strategyName.substring(0, strategyName.length() - SOL_SUFFIX.length());
        if (strategyName.endsWith(BTC_SUFFIX))
            return strategyName.substring(0, strategyName.length() - BTC_SUFFIX.length());
        return strategyName;
    }

    public static String qualify(String baseName, String symbol) {
        if (baseName == null) return null;
        if ("ETHUSDT".equalsIgnoreCase(symbol)) return baseName(baseName) + ETH_SUFFIX;
        if ("SOLUSDT".equalsIgnoreCase(symbol)) return baseName(baseName) + SOL_SUFFIX;
        if ("BTCUSDT".equalsIgnoreCase(symbol)) return baseName(baseName) + BTC_SUFFIX;
        return baseName(baseName);
    }

    public static String supportedSymbol(String strategyName) {
        if (strategyName == null) return null;
        if (strategyName.endsWith(ETH_SUFFIX)) return "ETHUSDT";
        if (strategyName.endsWith(SOL_SUFFIX)) return "SOLUSDT";
        if (strategyName.endsWith(BTC_SUFFIX)) return "BTCUSDT";
        return null;
    }
}
