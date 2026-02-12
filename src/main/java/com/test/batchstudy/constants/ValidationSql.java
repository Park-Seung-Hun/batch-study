package com.test.batchstudy.constants;

/**
 * Week 08: 데이터 검증 SQL 조건절 중앙 관리
 * <p>
 * ValidateTasklet, ErrorIsolateTasklet, CustomerPartitioner, partitionedStagingReader에서
 * 동일한 검증 조건을 사용한다. 한 곳에서 관리하여 불일치를 방지한다.
 * <p>
 * INVALID_RECORD_WHERE: 오류 레코드 조건 (서브쿼리 포함, run_date 파라미터 2개 필요)
 * VALID_RECORD_FILTER: 유효 레코드 필터 (단순 조건, 파라미터 불필요)
 */
public final class ValidationSql {

    private ValidationSql() {}

    /**
     * 오류 레코드 WHERE 절 — ValidateTasklet, ErrorIsolateTasklet에서 사용
     * <p>
     * 파라미터 바인딩: run_date = ? (2회)
     * 조건: 이메일 형식 오류 OR customer_id NULL OR 중복 customer_id
     */
    public static final String INVALID_RECORD_WHERE = """
            run_date = ?
            AND (
                email NOT LIKE '%@%'
                OR customer_id IS NULL
                OR customer_id IN (
                    SELECT customer_id FROM customer_stg
                    WHERE run_date = ?
                    GROUP BY customer_id HAVING COUNT(*) > 1
                )
            )""";

    /**
     * 유효 레코드 필터 — CustomerPartitioner, partitionedStagingReader에서 사용
     * <p>
     * INVALID_RECORD_WHERE의 단순 부정이 아님 (서브쿼리 구조가 다름).
     * 이메일 형식 유효 AND customer_id NOT NULL 조건만 체크한다.
     */
    public static final String VALID_RECORD_FILTER = "email LIKE '%@%' AND customer_id IS NOT NULL";
}
