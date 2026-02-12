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

/**
 * Week 07: 병렬/튜닝 테스트
 *
 * 시나리오:
 * 1. 멀티스레드 정합성 — 100건, 4스레드, chunkSize=10 → COMPLETED + stg/customer 100건 + 중복 0
 * 2. 스레드 수 비교 — 1000건, 1스레드 vs 4스레드 양쪽 COMPLETED + 소요시간 로그
 * 3. 파티셔닝 정합성 — 100건, 4파티션 → 4개 slave Step + readCount 합 = 100
 * 4. 멀티스레드 + Skip — dirty 6건, 4스레드 → 3건 skip + 3건 적재 + FAILED
 * 5. threadCount 기본값 — 100건, threadCount 미전달 → 기본값 4로 COMPLETED
 * 6. 대용량 정합성 — 1000건, 4스레드, chunkSize=50 → 1000건 + 중복 0
 */
class ParallelTuningTest extends AbstractBatchTest {

    @Test
    @DisplayName("시나리오1: 멀티스레드 정합성 — 100건, 4스레드, chunkSize=10 → COMPLETED + 중복 0")
    void 멀티스레드_정합성() throws Exception {
        // given
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_20250205.csv", true)
                .addString(PARAM_RUN_DATE, "2025-07-01", true)
                .addLong(PARAM_CHUNK_SIZE, 10L, false)
                .addLong(PARAM_THREAD_COUNT, 4L, false)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus())
                .as("멀티스레드 4스레드로 정상 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(countByRunDate(TABLE_CUSTOMER_STG, "2025-07-01"))
                .as("stg 테이블에 100건 적재").isEqualTo(100);

        assertThat(countTable(TABLE_CUSTOMER))
                .as("customer 테이블에 100건 적재").isEqualTo(100);

        // 중복 검증
        Integer duplicates = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT customer_id FROM customer_stg WHERE run_date = '2025-07-01'
                    GROUP BY customer_id HAVING COUNT(*) > 1
                ) dup
                """, Integer.class);
        assertThat(duplicates).as("stg 테이블에 중복 0건").isEqualTo(0);
    }

    @Test
    @DisplayName("시나리오2: 스레드 수 비교 — 1000건, 1스레드 vs 4스레드 양쪽 COMPLETED")
    void 스레드_수_비교() throws Exception {
        // 1스레드 실행
        JobParameters params1 = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_1000.csv", true)
                .addString(PARAM_RUN_DATE, "2025-07-02", true)
                .addLong(PARAM_THREAD_COUNT, 1L, false)
                .toJobParameters();

        long start1 = System.currentTimeMillis();
        JobExecution execution1 = jobOperatorTestUtils.startJob(params1);
        long elapsed1 = System.currentTimeMillis() - start1;

        assertThat(execution1.getStatus())
                .as("1스레드 정상 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(countTable(TABLE_CUSTOMER))
                .as("1스레드 — customer 1000건").isEqualTo(1000);

        // 데이터 정리 후 4스레드 실행
        jdbcTemplate.execute("DELETE FROM customer_daily_stats");
        jdbcTemplate.execute("DELETE FROM customer_err");
        jdbcTemplate.execute("DELETE FROM customer");
        jdbcTemplate.execute("DELETE FROM customer_stg");
        jobRepositoryTestUtils.removeJobExecutions();

        JobParameters params4 = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_1000.csv", true)
                .addString(PARAM_RUN_DATE, "2025-07-03", true)
                .addLong(PARAM_THREAD_COUNT, 4L, false)
                .toJobParameters();

        long start4 = System.currentTimeMillis();
        JobExecution execution4 = jobOperatorTestUtils.startJob(params4);
        long elapsed4 = System.currentTimeMillis() - start4;

        assertThat(execution4.getStatus())
                .as("4스레드 정상 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(countTable(TABLE_CUSTOMER))
                .as("4스레드 — customer 1000건").isEqualTo(1000);

        org.slf4j.LoggerFactory.getLogger(getClass())
                .info("1000건 처리 소요시간 — 1스레드: {}ms, 4스레드: {}ms", elapsed1, elapsed4);
    }

    @Test
    @DisplayName("시나리오3: 파티셔닝 정합성 — 100건, 4파티션 → slave Step 4개 + readCount 합 = 100")
    void 파티셔닝_정합성() throws Exception {
        // given
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_20250205.csv", true)
                .addString(PARAM_RUN_DATE, "2025-07-04", true)
                .addLong(PARAM_THREAD_COUNT, 4L, false)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus())
                .as("파티셔닝 정상 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        long slaveStepCount = execution.getStepExecutions().stream()
                .filter(se -> se.getStepName().contains("stagingToTargetSlaveStep"))
                .count();
        assertThat(slaveStepCount).as("4개 파티션 Slave Step 실행").isEqualTo(4);

        long totalReadCount = execution.getStepExecutions().stream()
                .filter(se -> se.getStepName().contains("stagingToTargetSlaveStep"))
                .mapToLong(StepExecution::getReadCount)
                .sum();
        assertThat(totalReadCount).as("파티션 readCount 합 = 100").isEqualTo(100);
    }

    @Test
    @DisplayName("시나리오4: 멀티스레드 + Skip — dirty 6건, 4스레드 → 3건 skip + FAILED")
    void 멀티스레드_스킵() throws Exception {
        // given — 6건 (정상 3, 오류 3)
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_dirty_20250205.csv", true)
                .addString(PARAM_RUN_DATE, "2025-07-05", true)
                .addLong(PARAM_THREAD_COUNT, 4L, false)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus())
                .as("dirty 데이터 skip → StatsTasklet이 오류 감지 → FAILED")
                .isEqualTo(BatchStatus.FAILED);

        StepExecution csvStep = execution.getStepExecutions().stream()
                .filter(se -> "csvToStagingStep".equals(se.getStepName()))
                .findFirst()
                .orElseThrow();

        assertThat(csvStep.getProcessSkipCount())
                .as("멀티스레드에서도 3건 Skip")
                .isEqualTo(3);

        assertThat(csvStep.getWriteCount())
                .as("정상 3건만 stg에 적재")
                .isEqualTo(3);

        assertThat(countByRunDate(TABLE_CUSTOMER_ERR, "2025-07-05"))
                .as("customer_err에 3건 격리").isEqualTo(3);
    }

    @Test
    @DisplayName("시나리오5: threadCount 기본값 — threadCount 미전달 → 기본값 4로 COMPLETED")
    void threadCount_기본값() throws Exception {
        // given — threadCount 파라미터 없음 → Elvis 기본값 4 적용
        JobParameters params = params("input/customers_20250205.csv", "2025-07-06");

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus())
                .as("threadCount 기본값 4로 정상 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(countTable(TABLE_CUSTOMER))
                .as("customer 테이블에 100건").isEqualTo(100);

        long slaveStepCount = execution.getStepExecutions().stream()
                .filter(se -> se.getStepName().contains("stagingToTargetSlaveStep"))
                .count();
        assertThat(slaveStepCount).as("기본 gridSize=4 → Slave Step 4개").isEqualTo(4);
    }

    @Test
    @DisplayName("시나리오6: 대용량 정합성 — 1000건, 4스레드, chunkSize=50 → 1000건 + 중복 0")
    void 대용량_정합성() throws Exception {
        // given
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_1000.csv", true)
                .addString(PARAM_RUN_DATE, "2025-07-07", true)
                .addLong(PARAM_CHUNK_SIZE, 50L, false)
                .addLong(PARAM_THREAD_COUNT, 4L, false)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus())
                .as("대용량 1000건 멀티스레드 정상 완료")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(countByRunDate(TABLE_CUSTOMER_STG, "2025-07-07"))
                .as("stg 1000건 적재").isEqualTo(1000);

        assertThat(countTable(TABLE_CUSTOMER))
                .as("customer 1000건 적재").isEqualTo(1000);

        Integer duplicates = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT customer_id FROM customer_stg WHERE run_date = '2025-07-07'
                    GROUP BY customer_id HAVING COUNT(*) > 1
                ) dup
                """, Integer.class);
        assertThat(duplicates).as("stg 중복 0건").isEqualTo(0);
    }
}
