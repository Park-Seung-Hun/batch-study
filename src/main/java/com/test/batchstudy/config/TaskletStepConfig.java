package com.test.batchstudy.config;

import com.test.batchstudy.tasklet.ErrorIsolateTasklet;
import com.test.batchstudy.tasklet.StatsTasklet;
import com.test.batchstudy.tasklet.ValidateTasklet;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Week 03~: Tasklet 기반 Step 설정 (검증, 오류 격리, 통계)
 */
@Configuration
public class TaskletStepConfig {

    @Bean
    public Step validateStep(JobRepository jobRepository, ValidateTasklet validateTasklet) {
        return new StepBuilder("validateStep", jobRepository)
                .tasklet(validateTasklet)
                .build();
    }

    @Bean
    public Step errorIsolateStep(JobRepository jobRepository, ErrorIsolateTasklet errorIsolateTasklet) {
        return new StepBuilder("errorIsolateStep", jobRepository)
                .tasklet(errorIsolateTasklet)
                .build();
    }

    @Bean
    public Step statsStep(JobRepository jobRepository, StatsTasklet statsTasklet) {
        return new StepBuilder("statsStep", jobRepository)
                .tasklet(statsTasklet)
                .build();
    }
}
