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

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 03: Flow 분기 + UPSERT 테스트
 *
 * 시나리오:
 * 1. 정상 데이터 → VALID → UPSERT 성공
 * 2. 오류 데이터 → INVALID → errorIsolateStep → FAILED
 * 3. 동일 데이터 재실행 → UPDATE 발생 (created_at 유지)
 * 4. 집계 테이블 기록 확인
 */
@SpringBatchTest
@SpringBootTest
class CustomerImportJobFlowTest {

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
        // 테스트 전 모든 비즈니스 테이블 초기화
        jdbcTemplate.execute("DELETE FROM customer_daily_stats");
        jdbcTemplate.execute("DELETE FROM customer_err");
        jdbcTemplate.execute("DELETE FROM customer");
        jdbcTemplate.execute("DELETE FROM customer_stg");
    }

    @Test
    @DisplayName("시나리오1: 정상 데이터 → VALID → UPSERT 성공")
    void 정상데이터_VALID_UPSERT_성공() throws Exception {
        // given
        JobParameters params = new JobParametersBuilder()
                .addString("inputFile", "input/customers_20250205.csv", true)
                .addString("runDate", "2025-02-10", true)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Step 실행 순서 확인
        Collection<StepExecution> stepExecutions = execution.getStepExecutions();
        assertThat(stepExecutions).extracting("stepName")
                .contains("csvToStagingStep", "validateStep", "stagingToTargetStep", "statsStep");

        // customer 테이블에 UPSERT 확인
        Integer customerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer", Integer.class);
        assertThat(customerCount).isEqualTo(100);

        // 오류 테이블은 비어 있어야 함
        Integer errorCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_err WHERE run_date = '2025-02-10'", Integer.class);
        assertThat(errorCount).isEqualTo(0);
    }

    @Test
    @DisplayName("시나리오2: 오류 데이터 → INVALID → errorIsolateStep → FAILED")
    void 오류데이터_INVALID_errorStep_FAILED() throws Exception {
        // given - 오류 데이터가 포함된 CSV
        JobParameters params = new JobParametersBuilder()
                .addString("inputFile", "input/customers_invalid_20250210.csv", true)
                .addString("runDate", "2025-02-11", true)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then - Week 05: Processor Skip으로 stg 3건, StatsTasklet이 오류 감지 → FAILED
        // Processor Skip: C002 (@ 없음), 빈 customerId → 2건 Skip (customer_err 기록)
        // stg 도달: C001, C003, C005(UPSERT) = 3건
        // ValidateTasklet: 중복 없음 → COMPLETED → stagingToTargetStep 실행
        // stagingReader 필터: C005 중복 제외 → customer 1건 (C001만? 또는 C001+C003)
        // StatsTasklet: error 존재 → ExitStatus.FAILED
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        // Step 실행 순서 확인 (COMPLETED 경로 + statsStep FAILED)
        Collection<StepExecution> stepExecutions = execution.getStepExecutions();
        assertThat(stepExecutions).extracting("stepName")
                .contains("csvToStagingStep", "validateStep", "stagingToTargetStep", "statsStep");

        // Processor Skip 2건 customer_err에 기록
        Integer skipErrCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_err WHERE run_date = '2025-02-11'", Integer.class);
        assertThat(skipErrCount).as("Processor Skip 2건").isEqualTo(2);
    }

    @Test
    @DisplayName("시나리오3: 동일 데이터 재실행 → UPDATE 발생 (created_at 유지, updated_at 갱신)")
    void 동일데이터_재실행_UPDATE_발생() throws Exception {
        // given - 첫 번째 실행
        JobParameters params1 = new JobParametersBuilder()
                .addString("inputFile", "input/customers_20250205.csv", true)
                .addString("runDate", "2025-02-12", true)
                .toJobParameters();
        jobOperatorTestUtils.startJob(params1);

        // 첫 번째 실행 후 created_at 저장
        var firstResult = jdbcTemplate.queryForMap(
                "SELECT created_at, updated_at FROM customer WHERE customer_id = 'C001'");

        // 잠시 대기 (시간 차이 확보)
        Thread.sleep(100);

        // 스테이징 테이블 초기화 (동일 데이터 재적재를 위해)
        jdbcTemplate.execute("DELETE FROM customer_stg");

        // given - 두 번째 실행 (다른 runDate로)
        JobParameters params2 = new JobParametersBuilder()
                .addString("inputFile", "input/customers_20250205.csv", true)
                .addString("runDate", "2025-02-13", true)
                .toJobParameters();

        // when
        JobExecution execution2 = jobOperatorTestUtils.startJob(params2);

        // then
        assertThat(execution2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 두 번째 실행 후 시간 비교
        var secondResult = jdbcTemplate.queryForMap(
                "SELECT created_at, updated_at FROM customer WHERE customer_id = 'C001'");

        // created_at은 유지되어야 함
        assertThat(secondResult.get("created_at")).isEqualTo(firstResult.get("created_at"));

        // updated_at은 갱신되어야 함
        assertThat(secondResult.get("updated_at")).isNotEqualTo(firstResult.get("updated_at"));

        // 레코드 수는 그대로 100건
        Integer customerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer", Integer.class);
        assertThat(customerCount).isEqualTo(100);
    }

    @Test
    @DisplayName("시나리오4: 집계 테이블 기록 확인")
    void 집계테이블_기록_확인() throws Exception {
        // given
        JobParameters params = new JobParametersBuilder()
                .addString("inputFile", "input/customers_20250205.csv", true)
                .addString("runDate", "2025-02-14", true)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 집계 테이블 확인
        var stats = jdbcTemplate.queryForMap(
                "SELECT total_count, success_count, error_count FROM customer_daily_stats WHERE run_date = '2025-02-14'");

        assertThat(stats.get("total_count")).isEqualTo(100);
        assertThat(stats.get("success_count")).isEqualTo(100);
        assertThat(stats.get("error_count")).isEqualTo(0);
    }

    @Test
    @DisplayName("시나리오5: 오류 데이터 집계 확인")
    void 오류데이터_집계_확인() throws Exception {
        // given - 오류 데이터가 포함된 CSV
        JobParameters params = new JobParametersBuilder()
                .addString("inputFile", "input/customers_invalid_20250210.csv", true)
                .addString("runDate", "2025-02-15", true)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then - Week 05: StatsTasklet이 오류 감지 → FAILED
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        // StatsTasklet 집계 확인
        var stats = jdbcTemplate.queryForMap(
                "SELECT total_count, success_count, error_count FROM customer_daily_stats WHERE run_date = '2025-02-15'");

        assertThat(stats.get("total_count")).as("stg 적재 건수").isEqualTo(3);
        assertThat(stats.get("success_count")).as("customer에 적재된 건수").isEqualTo(1);
        assertThat(stats.get("error_count")).as("오류 건수 (stg-success)").isEqualTo(2);
    }
}
