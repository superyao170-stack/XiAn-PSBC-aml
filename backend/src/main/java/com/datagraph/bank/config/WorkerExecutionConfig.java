package com.datagraph.bank.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WorkerExecutionConfig {

    @Bean(name = "applicationTaskExecutor")
    @Primary
    public TaskExecutor applicationTaskExecutor(
            @Value("${worker.execution.concurrency:5}") int concurrency,
            @Value("${worker.execution.queue-capacity:1000}") int queueCapacity,
            @Value("${worker.execution.thread-prefix:analysis-worker-}") String threadPrefix) {
        if (concurrency < 1) throw new IllegalArgumentException("worker.execution.concurrency must be positive");
        if (queueCapacity < 1) throw new IllegalArgumentException("worker.execution.queue-capacity must be positive");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadPrefix);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
