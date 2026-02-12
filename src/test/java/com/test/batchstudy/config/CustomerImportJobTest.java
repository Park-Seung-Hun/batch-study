package com.test.batchstudy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 02: customerImportJob 테스트
 *
 * Chunk 처리 검증:
 * - CSV → customer_stg 적재 성공
 * - READ_COUNT = WRITE_COUNT 일치
 * - Chunk Size 기반 COMMIT_COUNT 검증
 */
class CustomerImportJobTest extends AbstractBatchTest {

    @Test
    @DisplayName("CSV 파일을 읽어 customer_stg 테이블에 적재 성공")
    void CSV파일_스테이징_적재_성공() throws Exception {
        // given
        JobParameters params = params("input/customers_20250205.csv", "2025-02-05");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countByRunDate("customer_stg", "2025-02-05")).isEqualTo(100);
    }

    @Test
    @DisplayName("READ_COUNT와 WRITE_COUNT가 일치해야 함")
    void READ_COUNT_WRITE_COUNT_일치() throws Exception {
        // given
        JobParameters params = params("input/customers_20250205.csv", "2025-02-06");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        StepExecution stepExecution = execution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getReadCount()).isEqualTo(stepExecution.getWriteCount());
        assertThat(stepExecution.getReadCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("Chunk Size(100) 기반으로 COMMIT_COUNT 검증")
    void ChunkSize_기반_COMMIT_COUNT_검증() throws Exception {
        // given
        JobParameters params = params("input/customers_20250205.csv", "2025-02-07");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        StepExecution stepExecution = execution.getStepExecutions().iterator().next();

        // 100건 데이터 / Chunk Size 100 = 1 Chunk → COMMIT_COUNT = 1
        assertThat(stepExecution.getCommitCount()).isEqualTo(1);
        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }
}
