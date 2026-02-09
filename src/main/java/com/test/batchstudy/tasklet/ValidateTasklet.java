package com.test.batchstudy.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 스테이징 데이터 검증 Tasklet
 * <p>
 * 검증 항목:
 * 1. 이메일 형식 검사 (@ 포함 여부)
 * 2. 중복 customer_id 검사
 * 3. 필수 필드 NULL 검사
 * <p>
 * 검증 결과에 따라 ExitStatus 설정:
 * - 모든 데이터 유효: COMPLETED
 * - 오류 데이터 존재: INVALID
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class ValidateTasklet implements Tasklet {

    private final JdbcTemplate jdbcTemplate;

    @Value("#{jobParameters['runDate']}")
    private String runDate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        log.info("=== ValidateTasklet 시작: runDate={} ===", runDate);

        LocalDate parsedRunDate = LocalDate.parse(runDate);
        int errorCount = validateStagingData(parsedRunDate);

        if (errorCount > 0) {
            log.warn("검증 실패: {} 건의 오류 발견", errorCount);
            contribution.setExitStatus(new ExitStatus("INVALID"));
        } else {
            log.info("검증 성공: 모든 데이터 유효");
            contribution.setExitStatus(ExitStatus.COMPLETED);
        }

        return RepeatStatus.FINISHED;
    }

    /**
     * 스테이징 데이터 검증 후 오류 건수 반환
     *
     * @param runDate 검증 대상 실행일
     * @return 오류 레코드 수 (이메일 형식 오류 + NULL customer_id + 중복 customer_id)
     */
    private int validateStagingData(LocalDate runDate) {
        String sql = "SELECT COUNT(*) " +
                "FROM customer_stg " +
                "WHERE run_date = ? " +
                "AND (email NOT LIKE '%@%' " +
                "OR customer_id IS NULL " +
                "OR customer_id IN (                                                                                          \n" +
                "          SELECT customer_id FROM customer_stg                                                                     \n" +
                "          WHERE run_date = ?                                                                                       \n" +
                "          GROUP BY customer_id HAVING COUNT(*) > 1                                                                 \n" +
                "      ))";

        return jdbcTemplate.queryForObject(sql, Integer.class, runDate, runDate);
    }
}
