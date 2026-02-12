package com.test.batchstudy.processor;

import com.test.batchstudy.domain.CustomerCsv;
import com.test.batchstudy.domain.CustomerStg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.validator.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Week 04: 재시작 가능한 ItemProcessor
 * <p>
 * ItemStream 인터페이스를 구현하여 처리 상태를 ExecutionContext에 저장/복원합니다.
 * 이를 통해 Job 실패 후 재시작 시 이전 처리 지점부터 이어서 처리할 수 있습니다.
 * <p>
 * 핵심 개념:
 * - ExecutionContext: Step 실행 상태를 저장하는 key-value 저장소 (DB에 직렬화됨)
 * - open(): Step 시작 시 호출 → 이전 실행 상태 복원
 * - update(): 청크 커밋 직전 호출 → 현재 상태 저장
 * - close(): Step 종료 시 호출 → 리소스 정리
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class RestartableItemProcessor implements ItemProcessor<CustomerCsv, CustomerStg>, ItemStream {

    private static final String PROCESSED_COUNT_KEY = "processedCount";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 현재까지 처리한 레코드 수 (Week 07: 멀티스레드 안전성을 위해 AtomicInteger 사용)
     */
    private final AtomicInteger processedCount = new AtomicInteger(0);

    @Value("#{jobParameters['failAt'] ?: 0}")
    private int failAt;

    private int effectiveFailAt;

    @Value("#{jobParameters['runDate']}")
    private String runDate;

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (executionContext.containsKey(PROCESSED_COUNT_KEY)) {
            this.processedCount.set(executionContext.getInt(PROCESSED_COUNT_KEY));
            log.info("Resuming from processedCount: {}", this.processedCount);
        } else {
            log.info("Starting fresh - processedCount: 0");
        }

        Integer existingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_stg WHERE run_date = ?",
                Integer.class, Date.valueOf(runDate)
        );

        if (existingCount != null && existingCount > 0) {
            this.effectiveFailAt = 0;
            log.info("Restart detected - {} records already exist, disabling failAt", existingCount);
        } else {
            this.effectiveFailAt = this.failAt;
            log.info("First execution - failAt={}", this.effectiveFailAt);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt(PROCESSED_COUNT_KEY, this.processedCount.get());
        log.debug("Updated processedCount: {}", this.processedCount);
    }

    @Override
    public CustomerStg process(CustomerCsv csv) {
        int currentCount = this.processedCount.incrementAndGet();
        validate(csv);
        if (effectiveFailAt > 0 && currentCount == effectiveFailAt) {
            throw new RuntimeException("Forced failure at " + effectiveFailAt);
        }
        LocalDate parsedRunDate = LocalDate.parse(runDate);
        return new CustomerStg(
                csv.customerId(),
                csv.email(),
                csv.name(),
                csv.phone(),
                parsedRunDate
        );
    }

    private void validate(CustomerCsv csv) {
        if (csv.customerId() == null || csv.customerId().isBlank()) {
            throw new ValidationException("Customer ID is required");
        }

        if (csv.email() == null || csv.email().isBlank()) {
            throw new ValidationException("Email is required");
        }

        if (!csv.email().contains("@")) {
            throw new ValidationException("Email address must contain @");
        }
    }

    @Override
    public void close() throws ItemStreamException {
        log.info("Closing RestartableItemProcessor - final processedCount: {}", processedCount);
    }

    public int getProcessedCount() {
        return processedCount.get();
    }
}
