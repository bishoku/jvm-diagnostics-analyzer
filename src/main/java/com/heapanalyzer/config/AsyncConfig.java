package com.heapanalyzer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures async support with a dedicated thread pool
 * for heap dump analysis tasks.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${app.async.core-pool-size:2}")
    private int corePoolSize;

    @Value("${app.async.max-pool-size:4}")
    private int maxPoolSize;

    @Value("${app.async.queue-capacity:10}")
    private int queueCapacity;

    @Bean(name = "analysisExecutor")
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("analysis-");
        executor.initialize();
        return executor;
    }
}
