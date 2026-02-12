package com.test.batchstudy.config;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static com.test.batchstudy.constants.BatchConstants.*;

/**
 * Week 08: customerImportJob 테스트 공통 기반 클래스
 * <p>
 * 제공 기능:
 * - @BeforeEach: 메타데이터 + 비즈니스 4테이블 초기화
 * - params(): identifying 파라미터 빌더
 * - countTable(): 테이블 전체 건수 조회
 * - countByRunDate(): 특정 runDate 건수 조회
 */
@SpringBatchTest
@SpringBootTest
abstract class AbstractBatchTest {

    @Autowired
    protected JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    protected JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    protected Job customerImportJob;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpBase() {
        jobRepositoryTestUtils.removeJobExecutions();
        jobOperatorTestUtils.setJob(customerImportJob);
        jdbcTemplate.execute("DELETE FROM customer_daily_stats");
        jdbcTemplate.execute("DELETE FROM customer_err");
        jdbcTemplate.execute("DELETE FROM customer");
        jdbcTemplate.execute("DELETE FROM customer_stg");
    }

    /**
     * identifying 파라미터(inputFile, runDate)로 JobParameters 생성
     */
    protected JobParameters params(String inputFile, String runDate) {
        return new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, inputFile, true)
                .addString(PARAM_RUN_DATE, runDate, true)
                .toJobParameters();
    }

    /**
     * 테이블 전체 건수 조회
     */
    protected int countTable(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName, Integer.class);
        return count != null ? count : 0;
    }

    /**
     * 특정 runDate의 건수 조회
     */
    protected int countByRunDate(String tableName, String runDate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE run_date = ?",
                Integer.class, LocalDate.parse(runDate));
        return count != null ? count : 0;
    }

}
