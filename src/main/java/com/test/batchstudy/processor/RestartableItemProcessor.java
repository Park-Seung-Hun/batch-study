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
     * 현재까지 처리한 레코드 수 (재시작 시 복원됨)
     */
    private int processedCount = 0;

    /**
     * 강제 실패 지점 (0이면 실패 없음, 양수면 해당 건수에서 예외 발생)
     * JobParameter로 주입: --failAt=500
     * 재시작 시에는 무시됨 (effectiveFailAt = 0)
     */
    @Value("#{jobParameters['failAt'] ?: 0}")
    private int failAt;

    /**
     * 실제 적용될 failAt 값 (재시작 시 0으로 설정됨)
     */
    private int effectiveFailAt;

    /**
     * 실행 기준일 (JobParameter로 주입)
     */
    @Value("#{jobParameters['runDate']}")
    private String runDate;

    /**
     * Step 시작 시 호출 - 재시작인 경우 이전 상태 복원
     * <p>
     * 재시작 감지 로직:
     * - DB에 해당 run_date의 데이터가 이미 존재하면 재시작으로 판단
     * - 재시작 시 failAt을 무시하여 정상 진행
     * - UPSERT로 중복 방지, 멱등성 보장
     *
     * @param executionContext Step의 상태를 저장하는 컨텍스트
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        // ExecutionContext에서 이전 상태 복원 시도
        if (executionContext.containsKey(PROCESSED_COUNT_KEY)) {
            this.processedCount = executionContext.getInt(PROCESSED_COUNT_KEY);
            log.info("Resuming from processedCount: {}", this.processedCount);
        } else {
            log.info("Starting fresh - processedCount: 0");
        }

        // 재시작 감지: DB에 이미 데이터가 있으면 재시작 상황
        Integer existingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_stg WHERE run_date = ?",
                Integer.class, Date.valueOf(runDate)
        );

        if (existingCount != null && existingCount > 0) {
            // 재시작: failAt 무시 (UPSERT로 멱등성 보장)
            this.effectiveFailAt = 0;
            log.info("Restart detected - {} records already exist, disabling failAt", existingCount);
        } else {
            // 첫 실행: failAt 그대로 적용
            this.effectiveFailAt = this.failAt;
            log.info("First execution - failAt={}", this.effectiveFailAt);
        }
    }

    /**
     * 청크 커밋 직전 호출 - 현재 상태 저장
     * <p>
     * 힌트:
     * - executionContext.putInt(KEY, value)로 현재 상태 저장
     * - 매 청크마다 호출되므로 잦은 로그는 피하기
     *
     * @param executionContext Step의 상태를 저장하는 컨텍스트
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt(PROCESSED_COUNT_KEY, this.processedCount);
        log.debug("Updated processedCount: {}", this.processedCount);
    }

    /**
     * CustomerCsv → CustomerStg 변환 + 검증 + 강제 실패 로직
     * <p>
     * Week 05: 검증 로직 추가
     * - validate() 메서드로 비즈니스 규칙 검증
     * - 검증 실패 시 ValidationException 발생 → Skip 처리
     * <p>
     * 힌트:
     * - processedCount 증가
     * - failAt > 0 && processedCount == failAt 조건에서 RuntimeException 발생
     * - 정상 처리 시 CustomerStg 반환
     *
     * @param csv 입력 CSV 레코드
     * @return 변환된 스테이징 레코드
     */
    @Override
    public CustomerStg process(CustomerCsv csv) {
        this.processedCount += 1;
        validate(csv);
        if (effectiveFailAt > 0 && processedCount == effectiveFailAt) {
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

    /**
     * Step 종료 시 호출 - 리소스 정리 (현재는 정리할 리소스 없음)
     */
    @Override
    public void close() throws ItemStreamException {
        log.info("Closing RestartableItemProcessor - final processedCount: {}", processedCount);
    }

    /**
     * 현재 처리 건수 조회 (테스트용)
     */
    public int getProcessedCount() {
        return processedCount;
    }
}
