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
 * 일별 통계 집계 Tasklet
 * <p>
 * Job 실행 완료 시 customer_daily_stats 테이블에
 * 성공/실패 건수를 기록합니다.
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class StatsTasklet implements Tasklet {

    private final JdbcTemplate jdbcTemplate;

    @Value("#{jobParameters['runDate']}")
    private String runDate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        log.info("=== StatsTasklet 시작: runDate={} ===", runDate);

        LocalDate parsedRunDate = LocalDate.parse(runDate);

        int totalCount = countStagingRecords(parsedRunDate);
        int errorCount = countErrorRecords(parsedRunDate);
        int successCount = totalCount - errorCount;

        saveStats(parsedRunDate, totalCount, successCount, errorCount);

        log.info("통계 저장 완료 - 전체: {}, 성공: {}, 실패: {}", totalCount, successCount, errorCount);

        // 오류가 있으면 FAILED ExitStatus 설정 → Flow에서 Job FAILED 처리
        if (errorCount > 0) {
            log.warn("오류 레코드 존재 → ExitStatus.FAILED 설정");
            contribution.setExitStatus(ExitStatus.FAILED);
        }

        return RepeatStatus.FINISHED;
    }

    private int countStagingRecords(LocalDate runDate) {
        String sql = "SELECT COUNT(*) FROM customer_stg WHERE run_date = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, runDate);
        return count != null ? count : 0;
    }

    private int countErrorRecords(LocalDate runDate) {
        String sql = "SELECT COUNT(*) FROM customer_err WHERE run_date = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, runDate);
        return count != null ? count : 0;
    }

    /**
     * 통계 저장 (UPSERT - 동일 run_date 존재 시 UPDATE)
     */
    private void saveStats(LocalDate runDate, int totalCount, int successCount, int errorCount) {
        String sql = """
            INSERT INTO customer_daily_stats (run_date, total_count, success_count, error_count)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (run_date) DO UPDATE SET
                total_count = EXCLUDED.total_count,
                success_count = EXCLUDED.success_count,
                error_count = EXCLUDED.error_count
            """;

        jdbcTemplate.update(sql, runDate, totalCount, successCount, errorCount);
    }
}
