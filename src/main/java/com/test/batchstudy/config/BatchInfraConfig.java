package com.test.batchstudy.config;

import com.test.batchstudy.partitioner.CustomerPartitioner;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Week 07~: 배치 인프라 설정 (스레드 풀, 파티셔너)
 */
@Configuration
public class BatchInfraConfig {

    @Bean
    @JobScope
    public ThreadPoolTaskExecutor batchTaskExecutor(
            @Value("#{jobParameters['threadCount'] ?: 4}") int threadCount) {
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setCorePoolSize(threadCount);
        threadPoolTaskExecutor.setMaxPoolSize(threadCount);
        threadPoolTaskExecutor.setQueueCapacity(Integer.MAX_VALUE);
        threadPoolTaskExecutor.setThreadNamePrefix("batch-thread-");
        threadPoolTaskExecutor.afterPropertiesSet();
        return threadPoolTaskExecutor;
    }

    @Bean
    @StepScope
    public CustomerPartitioner customerPartitioner(
            JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['runDate']}") String runDate) {
        return new CustomerPartitioner(jdbcTemplate, runDate);
    }
}
