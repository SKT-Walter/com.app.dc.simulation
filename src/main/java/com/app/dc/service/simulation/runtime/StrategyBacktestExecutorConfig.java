package com.app.dc.service.simulation.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class StrategyBacktestExecutorConfig {

    @Value("${strategy.backtest.parallelism:10}")
    private int parallelism;

    @Value("${strategy.backtest.threadNamePrefix:strategy-backtest-}")
    private String threadNamePrefix;

    @Bean(name = "strategyBacktestTaskExecutor")
    public ThreadPoolTaskExecutor strategyBacktestTaskExecutor() {
        int poolSize = Math.max(1, parallelism);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(poolSize);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
