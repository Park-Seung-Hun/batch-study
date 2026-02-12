package com.test.batchstudy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;

import static com.test.batchstudy.constants.BatchConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Week 06: 파라미터 + Scope 테스트
 *
 * 시나리오:
 * 1. 동적 chunkSize — 100건 + chunkSize=2 → commitCount=50
 * 2. 동적 skipLimit — dirty 6건 + skipLimit=2 → 오류3 > limit2 → FAILED
 * 3. Non-identifying 재사용 — 같은 identifying + 다른 chunkSize → 이미 완료 예외
 * 4. Validator — inputFile 누락 시 FAILED + "inputFile" 메시지
 * 5. Validator — runDate 형식 오류 시 FAILED + "runDate" 메시지
 * 6. 기본값 동작 — 필수만 전달 (chunkSize 없음) → commitCount=1
 */
class ParamsScopeTest extends AbstractBatchTest {

    @Test
    @DisplayName("시나리오1: 동적 chunkSize — 100건 + chunkSize=2 → commitCount=50")
    void 동적_chunkSize() throws Exception {
        // given — 100건 정상 데이터, chunkSize=2
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_20250205.csv", true)
                .addString(PARAM_RUN_DATE, "2025-06-01", true)
                .addLong(PARAM_CHUNK_SIZE, 2L, false)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus())
                .as("정상 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        StepExecution csvStep = execution.getStepExecutions().stream()
                .filter(se -> "csvToStagingStep".equals(se.getStepName()))
                .findFirst()
                .orElseThrow();

        assertThat(csvStep.getCommitCount())
                .as("100건 / chunkSize=2 = 50 commits")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("시나리오2: 동적 skipLimit — dirty 6건 + skipLimit=2 → 오류3 > limit2 → FAILED")
    void 동적_skipLimit() throws Exception {
        // given — 6건 (정상 3, 오류 3), skipLimit=2
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_dirty_20250205.csv", true)
                .addString(PARAM_RUN_DATE, "2025-06-02", true)
                .addLong(PARAM_SKIP_LIMIT, 2L, false)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then — 오류 3건 > skipLimit 2 → Job FAILED
        assertThat(execution.getStatus())
                .as("오류 3건 > skipLimit 2 → Job FAILED")
                .isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("시나리오3: Non-identifying 재사용 — 같은 identifying + 다른 chunkSize → 이미 완료")
    void nonIdentifying_재사용() throws Exception {
        // 1차 실행
        JobExecution firstExecution = jobOperatorTestUtils.startJob(
                params("input/customers_20250205.csv", "2025-06-03"));
        assertThat(firstExecution.getStatus())
                .as("1차 실행 정상 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        // 2차 실행 — 같은 identifying + 다른 chunkSize(non-identifying)
        JobParameters secondParams = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_20250205.csv", true)
                .addString(PARAM_RUN_DATE, "2025-06-03", true)
                .addLong(PARAM_CHUNK_SIZE, 50L, false)
                .toJobParameters();

        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(secondParams))
                .as("non-identifying이 달라도 같은 JobInstance → 이미 완료 예외")
                .hasMessageContaining("complete");
    }

    @Test
    @DisplayName("시나리오4: Validator — inputFile 누락 시 예외")
    void validator_inputFile_누락() {
        // given — inputFile 없이 runDate만 전달
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_RUN_DATE, "2025-06-04", true)
                .toJobParameters();

        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(params))
                .as("inputFile 누락 시 InvalidJobParametersException")
                .hasMessageContaining("inputFile");
    }

    @Test
    @DisplayName("시나리오5: Validator — runDate 형식 오류 시 예외")
    void validator_runDate_형식오류() {
        // given — runDate가 yyyy-MM-dd 형식이 아님
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_20250205.csv", true)
                .addString(PARAM_RUN_DATE, "20250301", true)
                .toJobParameters();

        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(params))
                .as("runDate 형식 오류 시 InvalidJobParametersException")
                .hasMessageContaining("runDate");
    }

    @Test
    @DisplayName("시나리오6: 기본값 동작 — 필수만 전달, chunkSize=100 기본값으로 commitCount=1")
    void 기본값_동작() throws Exception {
        // given — chunkSize, skipLimit, retryLimit 모두 미전달 → Elvis 기본값 적용
        JobParameters params = params("input/customers_20250205.csv", "2025-06-06");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus())
                .as("기본값으로 정상 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        StepExecution csvStep = execution.getStepExecutions().stream()
                .filter(se -> "csvToStagingStep".equals(se.getStepName()))
                .findFirst()
                .orElseThrow();

        assertThat(csvStep.getCommitCount())
                .as("기본 chunkSize=100, 100건/100 = 1 commit")
                .isEqualTo(1);
    }
}
