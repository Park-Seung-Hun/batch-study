package com.test.batchstudy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

import java.util.Collection;

import static com.test.batchstudy.constants.BatchConstants.*;
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
class CustomerImportJobFlowTest extends AbstractBatchTest {

    @Test
    @DisplayName("시나리오1: 정상 데이터 → VALID → UPSERT 성공")
    void 정상데이터_VALID_UPSERT_성공() throws Exception {
        // given
        JobParameters params = params("input/customers_20250205.csv", "2025-02-10");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Collection<StepExecution> stepExecutions = execution.getStepExecutions();
        assertThat(stepExecutions).extracting("stepName")
                .contains("csvToStagingStep", "validateStep", "stagingToTargetStep", "statsStep");

        assertThat(countTable(TABLE_CUSTOMER)).isEqualTo(100);
        assertThat(countByRunDate(TABLE_CUSTOMER_ERR, "2025-02-10")).isEqualTo(0);
    }

    @Test
    @DisplayName("시나리오2: 오류 데이터 → INVALID → errorIsolateStep → FAILED")
    void 오류데이터_INVALID_errorStep_FAILED() throws Exception {
        // given
        JobParameters params = params("input/customers_invalid_20250210.csv", "2025-02-11");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        Collection<StepExecution> stepExecutions = execution.getStepExecutions();
        assertThat(stepExecutions).extracting("stepName")
                .contains("csvToStagingStep", "validateStep", "stagingToTargetStep", "statsStep");

        assertThat(countByRunDate(TABLE_CUSTOMER_ERR, "2025-02-11"))
                .as("Processor Skip 2건").isEqualTo(2);
    }

    @Test
    @DisplayName("시나리오3: 동일 데이터 재실행 → UPDATE 발생 (created_at 유지, updated_at 갱신)")
    void 동일데이터_재실행_UPDATE_발생() throws Exception {
        // given - 첫 번째 실행
        jobOperatorTestUtils.startJob(params("input/customers_20250205.csv", "2025-02-12"));

        var firstResult = jdbcTemplate.queryForMap(
                "SELECT created_at, updated_at FROM customer WHERE customer_id = 'C001'");

        Thread.sleep(100);

        jdbcTemplate.execute("DELETE FROM customer_stg");

        // given - 두 번째 실행 (다른 runDate로)
        JobExecution execution2 = jobOperatorTestUtils.startJob(
                params("input/customers_20250205.csv", "2025-02-13"));

        // then
        assertThat(execution2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        var secondResult = jdbcTemplate.queryForMap(
                "SELECT created_at, updated_at FROM customer WHERE customer_id = 'C001'");

        assertThat(secondResult.get("created_at")).isEqualTo(firstResult.get("created_at"));
        assertThat(secondResult.get("updated_at")).isNotEqualTo(firstResult.get("updated_at"));
        assertThat(countTable(TABLE_CUSTOMER)).isEqualTo(100);
    }

    @Test
    @DisplayName("시나리오4: 집계 테이블 기록 확인")
    void 집계테이블_기록_확인() throws Exception {
        // given
        JobExecution execution = jobOperatorTestUtils.startJob(
                params("input/customers_20250205.csv", "2025-02-14"));

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        var stats = jdbcTemplate.queryForMap(
                "SELECT total_count, success_count, error_count FROM customer_daily_stats WHERE run_date = '2025-02-14'");

        assertThat(stats.get("total_count")).isEqualTo(100);
        assertThat(stats.get("success_count")).isEqualTo(100);
        assertThat(stats.get("error_count")).isEqualTo(0);
    }

    @Test
    @DisplayName("시나리오5: 오류 데이터 집계 확인")
    void 오류데이터_집계_확인() throws Exception {
        // given
        JobExecution execution = jobOperatorTestUtils.startJob(
                params("input/customers_invalid_20250210.csv", "2025-02-15"));

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        var stats = jdbcTemplate.queryForMap(
                "SELECT total_count, success_count, error_count FROM customer_daily_stats WHERE run_date = '2025-02-15'");

        assertThat(stats.get("total_count")).as("stg 적재 건수").isEqualTo(3);
        assertThat(stats.get("success_count")).as("customer에 적재된 건수").isEqualTo(1);
        assertThat(stats.get("error_count")).as("오류 건수 (stg-success)").isEqualTo(2);
    }
}
