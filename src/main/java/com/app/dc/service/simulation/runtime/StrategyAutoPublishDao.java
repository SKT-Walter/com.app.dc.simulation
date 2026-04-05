package com.app.dc.service.simulation.runtime;

public interface StrategyAutoPublishDao {
    StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName);

    StrategyLiveRegistryPublishRow loadLatestLiveBaseline(String strategyName);

    StrategyLiveRegistryPublishRow loadExactActive(String strategyName, String strategyVersion);

    StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion);

    StrategyReleaseEventRecord loadLatestReleaseEvent(String strategyName, String strategyVersion);

    void retireActive(String strategyName, String exceptVersion, String retireTime);

    void insertRegistry(StrategyLiveRegistryPublishRow row);

    void insertReleaseEvent(StrategyReleaseEventRecord row);
}
