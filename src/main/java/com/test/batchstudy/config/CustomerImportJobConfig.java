package com.test.batchstudy.config;

import com.test.batchstudy.validator.CustomerImportJobParametersValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * customerImportJob 정의 — Job Flow만 관리
 * <p>
 * 처리 흐름:
 * 1. csvToStagingStep: CSV → customer_stg
 * 2. validateStep: 스테이징 데이터 검증
 * 3. ExitStatus 기반 분기:
 *    - COMPLETED → stagingToTargetStep → statsStep
 *    - INVALID → errorIsolateStep → statsStep → FAILED
 */
@Configuration
@RequiredArgsConstructor
public class CustomerImportJobConfig {

    private final CustomerImportJobParametersValidator validator;

    @Bean
    public Job customerImportJob(JobRepository jobRepository,
                                 Step csvToStagingStep,
                                 Step validateStep,
                                 Step stagingToTargetStep,
                                 Step errorIsolateStep,
                                 Step statsStep) {
        return new JobBuilder("customerImportJob", jobRepository)
                .validator(validator)
                .start(csvToStagingStep)
                .next(validateStep)
                .on("COMPLETED").to(stagingToTargetStep)
                .from(validateStep)
                .on("INVALID").to(errorIsolateStep)
                .from(validateStep)
                .on("*").fail()
                .from(stagingToTargetStep).on("*").to(statsStep)
                .from(errorIsolateStep).on("*").to(statsStep)
                .from(statsStep).on("FAILED").fail()
                .from(statsStep).on("*").end()
                .end()
                .build();
    }
}
