package com.app.dc.service.simulation.runtime;

public interface StrategyAutoPublishDao {
    StrategyLiveRegistryPublishRow loadCurrentActive(String strategyName);

    StrategyBacktestSummary loadLatestSummary(String strategyName, String strategyVersion);

    void retireActive(String strategyName, String exceptVersion, String retireTime);

    void insertRegistry(StrategyLiveRegistryPublishRow row);

    void insertReleaseEvent(StrategyReleaseEventRecord row);
}
