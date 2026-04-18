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

    @Value("${strategy.backtest.symbolParallelism:2}")
    private int symbolParallelism;

    @Value("${strategy.backtest.symbolThreadNamePrefix:strategy-backtest-symbol-}")
    private String symbolThreadNamePrefix;

    @Value("${strategy.backtest.kline-autofill.parallelism:2}")
    private int klineAutofillParallelism;

    @Value("${strategy.backtest.kline-autofill.threadNamePrefix:strategy-kline-autofill-}")
    private String klineAutofillThreadNamePrefix;

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

    @Bean(name = "strategyBacktestSymbolExecutor")
    public ThreadPoolTaskExecutor strategyBacktestSymbolExecutor() {
        int poolSize = Math.max(1, symbolParallelism);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(poolSize * 2);
        executor.setThreadNamePrefix(symbolThreadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "strategyBacktestKlineAutofillExecutor")
    public ThreadPoolTaskExecutor strategyBacktestKlineAutofillExecutor() {
        int poolSize = Math.max(1, klineAutofillParallelism);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(poolSize);
        executor.setThreadNamePrefix(klineAutofillThreadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
