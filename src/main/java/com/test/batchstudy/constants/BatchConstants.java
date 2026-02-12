package com.test.batchstudy.constants;

/**
 * Week 08: 배치 작업 전반에서 사용되는 상수 정의
 * <p>
 * 매직 스트링을 상수로 추출하여 타입 안전성과 리팩토링 편의성을 높인다.
 * SpEL 표현식(@Value) 내부의 문자열은 Java 상수 참조가 불가하므로 대상에서 제외한다.
 */
public final class BatchConstants {

    private BatchConstants() {}

    // ── Job Parameter 키 ──
    public static final String PARAM_INPUT_FILE = "inputFile";
    public static final String PARAM_RUN_DATE = "runDate";
    public static final String PARAM_CHUNK_SIZE = "chunkSize";
    public static final String PARAM_SKIP_LIMIT = "skipLimit";
    public static final String PARAM_RETRY_LIMIT = "retryLimit";
    public static final String PARAM_THREAD_COUNT = "threadCount";
    public static final String PARAM_FAIL_AT = "failAt";

    // ── 기본값 ──
    public static final int DEFAULT_CHUNK_SIZE = 100;
    public static final int DEFAULT_SKIP_LIMIT = 10;
    public static final int DEFAULT_RETRY_LIMIT = 3;
    public static final int DEFAULT_THREAD_COUNT = 4;

    // ── 테이블명 ──
    public static final String TABLE_CUSTOMER_STG = "customer_stg";
    public static final String TABLE_CUSTOMER = "customer";
    public static final String TABLE_CUSTOMER_ERR = "customer_err";
    public static final String TABLE_CUSTOMER_DAILY_STATS = "customer_daily_stats";

    // ── ExecutionContext 키 ──
    public static final String CTX_PROCESSED_COUNT = "processedCount";
}
