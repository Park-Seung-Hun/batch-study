package com.test.batchstudy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

import static com.test.batchstudy.constants.BatchConstants.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 05: 내결함성 (Fault Tolerance) 테스트
 *
 * 시나리오:
 * 1. 스킵 동작 검증 — dirty 데이터 3건 스킵, 3건 stg 적재 (Job FAILED: StatsTasklet이 customer_err 감지)
 * 2. 오류 격리 확인 — customer_err 테이블에 스킵된 레코드 기록 검증
 * 3. skipLimit 초과 — 오류 12건 > skipLimit 10 → csvToStagingStep에서 Job FAILED
 * 4. 정상 데이터 회귀 — 기존 100건 데이터에서 skipCount=0 확인
 */
class FaultToleranceTest extends AbstractBatchTest {

    @Test
    @DisplayName("시나리오1: 스킵 동작 검증 — processSkipCount=3, writeCount=3")
    void 스킵_동작_검증() throws Exception {
        // given — 6건 (정상 3, 오류 3)
        JobParameters params = params("input/customers_dirty_20250205.csv", "2025-02-20");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then — SkipListener가 customer_err에 기록 → StatsTasklet이 오류 감지 → Job FAILED
        assertThat(execution.getStatus())
                .as("StatsTasklet이 customer_err 감지하여 Job FAILED")
                .isEqualTo(BatchStatus.FAILED);

        StepExecution csvStep = execution.getStepExecutions().stream()
                .filter(se -> "csvToStagingStep".equals(se.getStepName()))
                .findFirst()
                .orElseThrow();

        assertThat(csvStep.getStatus())
                .as("csvToStagingStep 자체는 Skip 허용 범위 내이므로 COMPLETED")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(csvStep.getProcessSkipCount())
                .as("Processor에서 3건 Skip")
                .isEqualTo(3);

        assertThat(csvStep.getWriteCount())
                .as("정상 3건만 stg에 적재")
                .isEqualTo(3);

        assertThat(execution.getStepExecutions()).extracting("stepName")
                .contains("csvToStagingStep", "validateStep", "stagingToTargetStep", "statsStep");
    }

    @Test
    @DisplayName("시나리오2: 오류 격리 확인 — customer_err에 3건, error_message 유형별 검증")
    void 오류격리_확인() throws Exception {
        // given — 6건 (정상 3, 오류 3)
        JobParameters params = params("input/customers_dirty_20250205.csv", "2025-02-21");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        assertThat(countByRunDate(TABLE_CUSTOMER_ERR, "2025-02-21"))
                .as("Skip된 3건이 customer_err에 격리").isEqualTo(3);

        Integer emailRequired = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_err WHERE run_date = '2025-02-21' AND error_message = ?",
                Integer.class, "Email is required");
        assertThat(emailRequired).as("빈 이메일 오류 1건").isEqualTo(1);

        Integer emailFormat = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_err WHERE run_date = '2025-02-21' AND error_message = ?",
                Integer.class, "Email address must contain @");
        assertThat(emailFormat).as("이메일 형식 오류 1건").isEqualTo(1);

        Integer customerIdRequired = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_err WHERE run_date = '2025-02-21' AND error_message = ?",
                Integer.class, "Customer ID is required");
        assertThat(customerIdRequired).as("빈 고객ID 오류 1건").isEqualTo(1);
    }

    @Test
    @DisplayName("시나리오3: skipLimit 초과 — 오류 12건 > limit 10 → Job FAILED")
    void skipLimit_초과() throws Exception {
        // given — 15건 (정상 3, 오류 12 > skipLimit 10)
        JobParameters params = params("input/customers_very_dirty_20250205.csv", "2025-02-22");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then — skipLimit 초과로 Job FAILED
        assertThat(execution.getStatus())
                .as("skipLimit(10) 초과 시 Job FAILED")
                .isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("시나리오4: 정상 데이터 회귀 — skipCount=0, writeCount=100")
    void 정상데이터_회귀() throws Exception {
        // given — 100건 정상 데이터
        JobParameters params = params("input/customers_20250205.csv", "2025-02-23");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus())
                .as("정상 데이터는 Skip 없이 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        StepExecution csvStep = execution.getStepExecutions().stream()
                .filter(se -> "csvToStagingStep".equals(se.getStepName()))
                .findFirst()
                .orElseThrow();

        assertThat(csvStep.getProcessSkipCount())
                .as("Skip 0건")
                .isEqualTo(0);

        assertThat(csvStep.getWriteCount())
                .as("100건 전부 적재")
                .isEqualTo(100);

        assertThat(countTable(TABLE_CUSTOMER)).isEqualTo(100);
    }
}
