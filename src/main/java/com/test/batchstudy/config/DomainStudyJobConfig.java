package com.test.batchstudy.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Week 01: Spring Batch 도메인 개념 실습용 Job 설정
 *
 * JobParameters:
 * - runDate (String, identifying): JobInstance를 구분하는 식별 파라미터
 * - chunkSize (Long, non-identifying): 설정값 (JobInstance 구분에 사용되지 않음)
 */
@Slf4j
@Configuration
public class DomainStudyJobConfig {

    @Bean
    public Job domainStudyJob(JobRepository jobRepository,
                              Step domainStep1,
                              Step domainStep2) {
        return new JobBuilder("domainStudyJob", jobRepository)
                .start(domainStep1)
                .next(domainStep2)
                .build();
    }

    @Bean
    public Step domainStep1(JobRepository jobRepository,
                            PlatformTransactionManager transactionManager) {
        return new StepBuilder("domainStep1", jobRepository)
                .tasklet(domainStep1Tasklet(), transactionManager)
                .build();
    }

    @Bean
    public Step domainStep2(JobRepository jobRepository,
                            PlatformTransactionManager transactionManager) {
        return new StepBuilder("domainStep2", jobRepository)
                .tasklet(domainStep2Tasklet(), transactionManager)
                .build();
    }

    @Bean
    public Tasklet domainStep1Tasklet() {
        return (contribution, chunkContext) -> {
            log.info("========================================");
            log.info("Step 1 시작");

            // JobParameters에서 runDate 조회
            String runDate = chunkContext.getStepContext()
                    .getJobParameters()
                    .get("runDate") != null
                    ? chunkContext.getStepContext().getJobParameters().get("runDate").toString()
                    : "미지정";

            log.info("runDate (identifying): {}", runDate);
            log.info("========================================");

            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Tasklet domainStep2Tasklet() {
        return (contribution, chunkContext) -> {
            log.info("========================================");
            log.info("Step 2 시작");

            // JobParameters에서 chunkSize 조회
            Object chunkSizeObj = chunkContext.getStepContext()
                    .getJobParameters()
                    .get("chunkSize");
            String chunkSize = chunkSizeObj != null ? chunkSizeObj.toString() : "미지정";

            log.info("chunkSize (non-identifying): {}", chunkSize);
            log.info("========================================");

            return RepeatStatus.FINISHED;
        };
    }
}
