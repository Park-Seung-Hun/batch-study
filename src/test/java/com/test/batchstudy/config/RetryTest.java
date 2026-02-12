package com.test.batchstudy.config;

import com.test.batchstudy.domain.CustomerCsv;
import com.test.batchstudy.domain.CustomerStg;
import com.test.batchstudy.listener.RetryLoggingListener;
import com.test.batchstudy.processor.RestartableItemProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static com.test.batchstudy.constants.BatchConstants.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 05: Retry 통합 테스트
 *
 * Step을 직접 빌드하고 execute()로 실행하여,
 * Bean 오버라이딩 없이 DeadlockLoserDataAccessException 재시도를 검증한다.
 *
 * 시나리오:
 * 5. Retry 성공 — 첫 write 실패 후 재시도로 복구
 * 6. Retry 소진 — 항상 실패하여 retryLimit 초과 → Step FAILED
 */
@SpringBootTest
class RetryTest {

    static final AtomicInteger WRITE_CALL_COUNT = new AtomicInteger(0);
    static int maxFailures = 1;

    @Autowired private JobRepository jobRepository;
    @Autowired private FlatFileItemReader<CustomerCsv> customerCsvReader;
    @Autowired private RestartableItemProcessor restartableItemProcessor;
    @Autowired private JdbcBatchItemWriter<CustomerStg> customerStgWriter;
    @Autowired private RetryLoggingListener retryLoggingListener;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        WRITE_CALL_COUNT.set(0);
        maxFailures = 1;
        jdbcTemplate.execute("DELETE FROM customer_daily_stats");
        jdbcTemplate.execute("DELETE FROM customer_err");
        jdbcTemplate.execute("DELETE FROM customer");
        jdbcTemplate.execute("DELETE FROM customer_stg");
    }

    /**
     * RetryPolicy(delay=ZERO)로 backoff 없는 Step을 빌드한다.
     */
    private Step buildRetryStep() {
        ItemWriter<CustomerStg> retryWriter = new RetrySimulatingWriter(customerStgWriter);

        return new StepBuilder("retryTestStep", jobRepository)
                .<CustomerCsv, CustomerStg>chunk(6)
                .reader(customerCsvReader)
                .processor(restartableItemProcessor)
                .writer(retryWriter)
                .faultTolerant()
                .retry(DeadlockLoserDataAccessException.class)
                .retryPolicy(RetryPolicy.builder()
                        .maxRetries(3)
                        .delay(Duration.ZERO)
                        .includes(DeadlockLoserDataAccessException.class)
                        .build())
                .retryListener(retryLoggingListener)
                .stream(customerCsvReader)
                .stream(restartableItemProcessor)
                .build();
    }

    /**
     * Step을 직접 실행하고 StepExecution을 반환한다.
     */
    private StepExecution executeStep(Step step, JobParameters params) throws Exception {
        JobInstance jobInstance = jobRepository.createJobInstance("retryTestJob", params);
        JobExecution jobExecution = jobRepository.createJobExecution(jobInstance, params, new ExecutionContext());
        StepExecution stepExecution = jobRepository.createStepExecution(step.getName(), jobExecution);
        step.execute(stepExecution);
        return stepExecution;
    }

    @Test
    @Timeout(30)
    @DisplayName("시나리오5: Retry 성공 — 첫 write 실패 후 재시도로 복구, writeCount=6")
    void retry_성공() throws Exception {
        // given — 6건 정상 데이터, 첫 번째 write()만 실패
        maxFailures = 1;
        Step step = buildRetryStep();
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_clean_6.csv", true)
                .addString(PARAM_RUN_DATE, "2025-02-24", true)
                .toJobParameters();

        // when
        StepExecution stepExecution = executeStep(step, params);

        // then — Retry 후 정상 완료
        assertThat(stepExecution.getStatus())
                .as("Retry 성공 후 Step COMPLETED")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(stepExecution.getWriteCount())
                .as("6건 전부 적재")
                .isEqualTo(6);

        assertThat(WRITE_CALL_COUNT.get())
                .as("write() 2회 호출 (1회 실패 + 1회 성공 = Retry 증거)")
                .isEqualTo(2);
    }

    @Test
    @Timeout(30)
    @DisplayName("시나리오6: Retry 소진 — 항상 실패하여 retryLimit 초과, Step FAILED")
    void retry_소진() throws Exception {
        // given — 6건 정상 데이터, 항상 실패
        maxFailures = 999;
        Step step = buildRetryStep();
        JobParameters params = new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, "input/customers_clean_6.csv", true)
                .addString(PARAM_RUN_DATE, "2025-02-25", true)
                .toJobParameters();

        // when
        StepExecution stepExecution = executeStep(step, params);

        // then — retryLimit 초과로 Step FAILED
        assertThat(stepExecution.getStatus())
                .as("retryLimit 초과 시 Step FAILED")
                .isEqualTo(BatchStatus.FAILED);
    }

    /**
     * Retry 시뮬레이션 Writer
     *
     * WRITE_CALL_COUNT가 maxFailures 이하인 동안 DeadlockLoserDataAccessException을 던지고,
     * 초과하면 실제 Writer에 위임한다.
     */
    static class RetrySimulatingWriter implements ItemWriter<CustomerStg> {
        private final ItemWriter<CustomerStg> delegate;

        RetrySimulatingWriter(ItemWriter<CustomerStg> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(Chunk<? extends CustomerStg> chunk) throws Exception {
            int count = WRITE_CALL_COUNT.incrementAndGet();
            if (count <= maxFailures) {
                throw new DeadlockLoserDataAccessException("Simulated deadlock", null);
            }
            delegate.write(chunk);
        }
    }
}
