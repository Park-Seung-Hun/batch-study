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
                .containsExactly("csvToStagingStep", "validateStep", "stagingToTargetStep", "statsStep");

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

        // then - Job이 FAILED 상태로 종료
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        // Step 실행 순서 확인 (INVALID 경로)
        Collection<StepExecution> stepExecutions = execution.getStepExecutions();
        assertThat(stepExecutions).extracting("stepName")
                .containsExactly("csvToStagingStep", "validateStep", "errorIsolateStep", "statsStep");

        // 오류 테이블에 격리된 레코드 확인
        // 오류 조건: 잘못된 이메일(1건) + 중복 customer_id(2건) = 3건
        // (CSV에서 빈 문자열 ""은 NULL이 아니므로 NULL 체크에 걸리지 않음)
        Integer errorCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_err WHERE run_date = '2025-02-11'", Integer.class);
        assertThat(errorCount).isEqualTo(3);

        // customer 테이블에는 적재되지 않아야 함 (INVALID 경로이므로)
        Integer customerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer", Integer.class);
        assertThat(customerCount).isEqualTo(0);
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

        // then - FAILED지만 집계는 기록됨
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        // 집계 테이블 확인 (statsStep은 failStep 전에 실행됨)
        var stats = jdbcTemplate.queryForMap(
                "SELECT total_count, success_count, error_count FROM customer_daily_stats WHERE run_date = '2025-02-15'");

        assertThat(stats.get("total_count")).isEqualTo(6);  // 총 6건
        assertThat(stats.get("error_count")).isEqualTo(3);  // 오류 3건 (이메일1 + 중복2)
        assertThat(stats.get("success_count")).isEqualTo(3); // 성공 3건 (6-3)
    }
}
