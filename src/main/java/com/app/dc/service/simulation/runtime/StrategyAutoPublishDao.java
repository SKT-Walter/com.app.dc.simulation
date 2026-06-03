package com.app.dc.service.simulation.runtime;

public interface StrategyAutoPublishDao {
    StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName);

    StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName, String symbolScope);

    StrategyLiveRegistryPublishRow loadLatestLiveBaseline(String strategyName);

    StrategyLiveRegistryPublishRow loadLatestLiveBaseline(String strategyName, String symbolScope);

    StrategyLiveRegistryPublishRow loadExactActive(String strategyName, String strategyVersion);

    StrategyLiveRegistryPublishRow loadExactActive(String strategyName, String strategyVersion, String symbolScope);

    StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion);

    StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion, String symbolScope);

    StrategyReleaseEventRecord loadLatestReleaseEvent(String strategyName, String strategyVersion);

    void retireActive(String strategyName, String exceptVersion, String retireTime);

    void retireActive(String strategyName, String symbolScope, String exceptVersion, String retireTime);

    void insertRegistry(StrategyLiveRegistryPublishRow row);

    void insertReleaseEvent(StrategyReleaseEventRecord row);
}
