package com.test.batchstudy.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

import static com.test.batchstudy.constants.ValidationSql.INVALID_RECORD_WHERE;

/**
 * 오류 레코드 격리 Tasklet
 * <p>
 * ValidateTasklet에서 INVALID 판정 시 실행되어,
 * 오류 레코드를 customer_stg → customer_err로 이동합니다.
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class ErrorIsolateTasklet implements Tasklet {

    private final JdbcTemplate jdbcTemplate;

    @Value("#{jobParameters['runDate']}")
    private String runDate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        log.info("=== ErrorIsolateTasklet 시작: runDate={} ===", runDate);

        LocalDate parsedRunDate = LocalDate.parse(runDate);
        int isolatedCount = isolateErrorRecords(parsedRunDate);

        log.info("오류 레코드 {} 건을 customer_err로 이동 완료", isolatedCount);

        return RepeatStatus.FINISHED;
    }

    /**
     * 오류 레코드를 customer_err 테이블로 INSERT하고 건수 반환
     * <p>
     * 오류 조건 (ValidateTasklet과 동일):
     * 1. 이메일에 '@' 없음
     * 2. customer_id가 NULL
     * 3. 동일 run_date 내 중복 customer_id
     */
    private int isolateErrorRecords(LocalDate runDate) {
        String sql = """
                INSERT INTO customer_err (customer_id, email, name, phone, error_message, run_date)
                SELECT
                    customer_id, email, name, phone,
                    CASE
                        WHEN customer_id IS NULL THEN 'customer_id is NULL'
                        WHEN email NOT LIKE '%@%' THEN 'Invalid email format'
                        ELSE 'Duplicate customer_id'
                    END AS error_message,
                    run_date
                FROM customer_stg
                WHERE \
                """ + INVALID_RECORD_WHERE;

        return jdbcTemplate.update(sql, runDate, runDate);
    }
}
