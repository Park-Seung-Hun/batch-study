package com.test.batchstudy.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Week 01: domainStudyJob 테스트
 *
 * Spring Batch 도메인 개념 검증:
 * - JobInstance: identifying 파라미터로 구분
 * - JobExecution: 실행 이력
 * - JobParameters: identifying vs non-identifying
 */
@SpringBatchTest
@SpringBootTest
class DomainStudyJobTest {

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private Job domainStudyJob;

    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        jobOperatorTestUtils.setJob(domainStudyJob);
    }

    @Test
    @DisplayName("시나리오1: 최초 실행 시 COMPLETED 상태로 완료")
    void 시나리오1_최초실행_COMPLETED() throws Exception {
        // given
        JobParameters params = new JobParametersBuilder()
                .addString("runDate", "2025-02-05", true)
                .addLong("chunkSize", 100L, false)
                .toJobParameters();

        // when
        JobExecution execution = jobOperatorTestUtils.startJob(params);

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("시나리오2: 동일 파라미터 재실행 시 JobInstanceAlreadyCompleteException 발생")
    void 시나리오2_동일파라미터_재실행시_예외발생() throws Exception {
        // given - 최초 실행
        JobParameters params = new JobParametersBuilder()
                .addString("runDate", "2025-02-06", true)
                .addLong("chunkSize", 100L, false)
                .toJobParameters();

        JobExecution firstExecution = jobOperatorTestUtils.startJob(params);
        assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // when & then - 동일 파라미터로 재실행 시 예외 발생
        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(params))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    @DisplayName("시나리오3: runDate(identifying) 변경 시 새 JobInstance 생성되어 COMPLETED")
    void 시나리오3_runDate변경시_새JobInstance생성() throws Exception {
        // given - 첫 번째 실행
        JobParameters params1 = new JobParametersBuilder()
                .addString("runDate", "2025-02-07", true)
                .addLong("chunkSize", 100L, false)
                .toJobParameters();

        JobExecution execution1 = jobOperatorTestUtils.startJob(params1);
        assertThat(execution1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // when - runDate 변경하여 두 번째 실행
        JobParameters params2 = new JobParametersBuilder()
                .addString("runDate", "2025-02-08", true)
                .addLong("chunkSize", 100L, false)
                .toJobParameters();

        JobExecution execution2 = jobOperatorTestUtils.startJob(params2);

        // then - 새로운 JobInstance로 성공적으로 실행
        assertThat(execution2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // JobInstance는 identifying 파라미터가 다르면 별개 객체
        assertThat(execution1.getJobInstance())
                .isNotSameAs(execution2.getJobInstance());
        // 각 JobInstance는 서로 다른 identifying parameters를 가짐
        assertThat(execution1.getJobParameters().getString("runDate"))
                .isNotEqualTo(execution2.getJobParameters().getString("runDate"));
    }

    @Test
    @DisplayName("시나리오4: non-identifying 파라미터만 변경 시 동일 JobInstance로 인해 예외 발생")
    void 시나리오4_nonIdentifying파라미터만_변경시_동일JobInstance() throws Exception {
        // given - 최초 실행
        JobParameters params1 = new JobParametersBuilder()
                .addString("runDate", "2025-02-09", true)
                .addLong("chunkSize", 100L, false)
                .toJobParameters();

        JobExecution execution1 = jobOperatorTestUtils.startJob(params1);
        assertThat(execution1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // when & then - non-identifying 파라미터(chunkSize)만 변경해도 동일 JobInstance로 인해 예외 발생
        JobParameters params2 = new JobParametersBuilder()
                .addString("runDate", "2025-02-09", true)
                .addLong("chunkSize", 200L, false)  // chunkSize만 변경
                .toJobParameters();

        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(params2))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }
}
