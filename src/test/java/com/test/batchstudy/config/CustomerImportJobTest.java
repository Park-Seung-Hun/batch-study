package com.test.batchstudy.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 02: customerImportJob 테스트
 *
 * Chunk 처리 검증:
 * - CSV → customer_stg 적재 성공
 * - READ_COUNT = WRITE_COUNT 일치
 * - Chunk Size 기반 COMMIT_COUNT 검증
 */
@SpringBatchTest
@SpringBootTest
class CustomerImportJobTest {

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private Job customerImportJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        jobOperatorTestUtils.setJob(customerImportJob);
        // 테스트 전 스테이징 테이블 초기화
        jdbcTemplate.execute("DELETE FROM customer_stg");
    }

    @Test
    @DisplayName("CSV 파일을 읽어 customer_stg 테이블에 적재 성공")
    void CSV파일_스테이징_적재_성공() throws Exception {
        // given
        JobParameters params = new JobParametersBuilder()
                .addString("inputFile", "input/customers_20250205.csv", true)
                .addString("runDate", "2025-02-05", true)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // DB 적재 건수 확인
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_stg WHERE run_date = '2025-02-05'",
                Integer.class
        );
        assertThat(count).isEqualTo(100);
    }

    @Test
    @DisplayName("READ_COUNT와 WRITE_COUNT가 일치해야 함")
    void READ_COUNT_WRITE_COUNT_일치() throws Exception {
        // given
        JobParameters params = new JobParametersBuilder()
                .addString("inputFile", "input/customers_20250205.csv", true)
                .addString("runDate", "2025-02-06", true)
                .toJobParameters();

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
        JobParameters params = new JobParametersBuilder()
                .addString("inputFile", "input/customers_20250205.csv", true)
                .addString("runDate", "2025-02-07", true)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        StepExecution stepExecution = execution.getStepExecutions().iterator().next();

        // 100건 데이터 / Chunk Size 100 = 1 Chunk → COMMIT_COUNT = 1
        // (마지막 Chunk 완료 후 추가 commit 포함하여 실제로는 1회)
        assertThat(stepExecution.getCommitCount()).isEqualTo(1);
        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }
}
