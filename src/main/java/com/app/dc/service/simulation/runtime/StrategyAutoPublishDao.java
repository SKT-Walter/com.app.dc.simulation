package com.app.dc.service.simulation.runtime;

import java.util.List;

public interface StrategyAutoPublishDao {
    StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName);

    StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName, String symbolScope);

    List<StrategyLiveRegistryPublishRow> listCurrentActiveRows(String strategyName);

    StrategyLiveRegistryPublishRow loadLatestLiveBaseline(String strategyName);

    StrategyLiveRegistryPublishRow loadLatestLiveBaseline(String strategyName, String symbolScope);

    StrategyLiveRegistryPublishRow loadExactActive(String strategyName, String strategyVersion);

    StrategyLiveRegistryPublishRow loadExactActive(String strategyName, String strategyVersion, String symbolScope);

    List<StrategyLiveRegistryPublishRow> listExactActiveRows(String strategyName, String strategyVersion);

    StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion);

    StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion, String symbolScope);

    StrategyLiveTradeStatsRow loadTodayTradeStats(String strategyName, String strategyVersion, String symbolScope);

    StrategyReleaseEventRecord loadLatestReleaseEvent(String strategyName, String strategyVersion);

    void retireActive(String strategyName, String exceptVersion, String retireTime);

    void retireActive(String strategyName, String symbolScope, String exceptVersion, String retireTime);

    void insertRegistry(StrategyLiveRegistryPublishRow row);

    void insertReleaseEvent(StrategyReleaseEventRecord row);
}
