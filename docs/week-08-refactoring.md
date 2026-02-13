# Week 08: 리팩토링 (Refactoring)

> 작성일: 2026-02-12
> 상태: ✅ 완료

---

## 이번 주 목표

- [x] Phase 1: 매직 스트링 → `BatchConstants` 상수 추출
- [x] Phase 2: 검증 SQL → `ValidationSql` 중앙화
- [x] Phase 3: `ErrorIsolationSkipListener` 중복 `insertErr` 통합
- [x] Phase 4: `CustomerImportJobConfig` → 5개 설정 파일 분리
- [x] Phase 5: `AbstractBatchTest` 기반 클래스로 테스트 중복 제거
- [x] Phase 6: `effectiveFailAt`에 `volatile` 추가 (멀티스레드 가시성)

---

## 핵심 개념 요약 (내 말로)

### 리팩토링 원칙

이번 주는 Week 01~07에 걸쳐 성장해 온 코드를 **기능 변경 없이** 구조적으로 개선하는 작업이다. 적용한 핵심 원칙 세 가지:

- **SRP (Single Responsibility Principle)**: 367줄짜리 `CustomerImportJobConfig`를 역할별 5개 파일로 분리. 각 파일이 하나의 관심사만 담당한다.
- **DRY (Don't Repeat Yourself)**: 검증 SQL, SkipListener INSERT, 테스트 setup 코드의 중복을 제거. 변경 시 한 곳만 수정하면 된다.
- **일관성**: 매직 스트링을 상수로 추출하여 오타 위험을 제거하고, 변수명만으로 의도를 드러낸다.

### 상수 추출의 이점과 한계

Java 상수로 추출하면 컴파일 타임 검증, IDE 자동완성, 리팩토링 지원을 얻는다. 단, **SpEL 표현식** 내부에서는 Java 상수를 참조할 수 없다:

```java
// ❌ SpEL에서 Java 상수 참조 불가
@Value("#{jobParameters[BatchConstants.PARAM_RUN_DATE]}")  // 컴파일 에러

// ✅ SpEL 내부는 문자열 리터럴 유지
@Value("#{jobParameters['runDate']}")
```

따라서 SpEL 표현식은 상수 추출 대상에서 제외하고, 나머지 Java 코드(테스트, Tasklet, Listener 등)에만 상수를 적용했다.

### Config 분할 전략

분할 기준은 **역할(responsibility)**이다:

| 파일 | 역할 |
|------|------|
| `CustomerImportJobConfig` | Job Flow 정의만 |
| `CsvToStagingStepConfig` | CSV → Staging Step (Reader/Processor/Writer + Skip/Retry) |
| `StagingToTargetStepConfig` | Staging → Target Step (파티셔닝 Master/Slave) |
| `TaskletStepConfig` | Tasklet 기반 Step 3개 (검증/격리/통계) |
| `BatchInfraConfig` | 인프라 빈 (ThreadPool, Partitioner) |

### 테스트 기반 클래스 패턴

`AbstractBatchTest`는 7개 테스트 클래스의 공통 setup과 유틸리티를 추출한 것이다. 단, Spring Batch의 `HippyMethodInvoker`가 클래스 계층의 모든 `StepExecution` 반환 메서드를 StepScope 팩토리로 오인하기 때문에, **기반 클래스에 `StepExecution` 반환 메서드를 두면 안 된다.**

### volatile과 멀티스레드 가시성

`volatile` 키워드는 변수의 값이 모든 스레드에서 즉시 보이도록 보장한다. `effectiveFailAt`은 `open()`에서 한 번 쓰고 `process()`에서 여러 스레드가 읽는 write-once-read-many 패턴이므로, `volatile`이 적합하다. `AtomicInteger`처럼 CAS 연산이 필요 없는 경우 `volatile`이 더 가벼운 선택이다.

---

## 구현 상세

### Phase 1: 매직 스트링 → `BatchConstants` 상수 추출

**파일**: `constants/BatchConstants.java` (신규, 36줄)

프로젝트 전반에 흩어진 매직 스트링을 용도별로 그룹화한 상수 클래스:

```java
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
```

**설계 의도**: `private` 생성자 + `final` 클래스로 인스턴스화를 방지한다. 용도별 주석 구분으로 상수를 찾기 쉽게 했다.

**적용 범위**: 테스트 8개 파일 + 프로덕션 3개 파일 (Listener, Processor, Validator)에서 매직 스트링을 `BatchConstants.PARAM_*`로 교체. SpEL 표현식은 제외.

**커밋**: `d5f2afa refactor(constants): JobParameter 키와 테이블명을 BatchConstants로 추출` — 12 files changed, +150 / -99

---

### Phase 2: 검증 SQL → `ValidationSql` 중앙화

**파일**: `constants/ValidationSql.java` (신규, 41줄)

4곳에서 중복되던 검증 WHERE 절을 하나의 상수로 통합:

```java
public final class ValidationSql {

    private ValidationSql() {}

    /**
     * 오류 레코드 WHERE 절 — ValidateTasklet, ErrorIsolateTasklet에서 사용
     * 파라미터 바인딩: run_date = ? (2회)
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
     * INVALID_RECORD_WHERE의 단순 부정이 아님 (서브쿼리 구조가 다름).
     */
    public static final String VALID_RECORD_FILTER =
            "email LIKE '%@%' AND customer_id IS NOT NULL";
}
```

**설계 의도**: 두 상수가 서로의 단순 부정(NOT)이 아닌 이유는 서브쿼리 구조의 차이 때문이다:
- `INVALID_RECORD_WHERE`는 중복 `customer_id` 감지용 서브쿼리 포함 (2회 파라미터 바인딩)
- `VALID_RECORD_FILTER`는 단순 조건만 (파라미터 불필요)

**사용처 매핑**:

| 상수 | 사용 파일 | 용도 |
|------|----------|------|
| `INVALID_RECORD_WHERE` | `ValidateTasklet` | 오류 건수 COUNT |
| `INVALID_RECORD_WHERE` | `ErrorIsolateTasklet` | 오류 레코드 INSERT INTO customer_err |
| `VALID_RECORD_FILTER` | `CustomerPartitioner` | 유효 레코드 MIN/MAX id 조회 |
| `VALID_RECORD_FILTER` | `StagingToTargetStepConfig` | 파티션별 유효 레코드 읽기 |

**커밋**: `2f6d3da refactor(sql): 검증 WHERE 절을 ValidationSql로 중앙화` — 5 files changed, +64 / -41

---

### Phase 3: `ErrorIsolationSkipListener` 중복 `insertErr` 통합

**파일**: `listener/ErrorIsolationSkipListener.java` (68→68줄, 구조 개선)

#### Before (2개의 오버로드된 `insertErr`)
```java
private void insertErr(String errMsg) {
    String sql = "INSERT INTO customer_err (...) VALUES(null, null, null, null, ?, ?)";
    jdbcTemplate.update(sql, errMsg, Date.valueOf(getRunDate()));
}

private void insertErr(String errMsg, String customerId, String email, String name, String phone) {
    String sql = "INSERT INTO customer_err (...) VALUES(?, ?, ?, ?, ?, ?)";
    jdbcTemplate.update(sql, customerId, email, name, phone, errMsg, Date.valueOf(getRunDate()));
}
```

#### After (단일 `insertErr`)
```java
private void insertErr(String errMsg, String customerId, String email, String name, String phone) {
    String sql = """
            INSERT INTO customer_err (customer_id, email, name, phone, error_message, run_date)
            VALUES (?, ?, ?, ?, ?, ?)""";
    jdbcTemplate.update(sql, customerId, email, name, phone, errMsg, Date.valueOf(getRunDate()));
}
```

**설계 의도**: `onSkipInRead`는 아이템이 없으므로 `null`을 전달하면 된다. 별도 오버로드를 만들 이유가 없다. SQL도 text block으로 가독성을 높였다.

```java
@Override
public void onSkipInRead(Throwable t) {
    String errMsg = getErrMsg(t);
    log.warn("Skip in Read: {}", errMsg);
    insertErr(errMsg, null, null, null, null);  // null 명시 전달
}
```

**커밋**: `4518c58 refactor(listener): ErrorIsolationSkipListener의 중복 insertErr 통합` — 1 file changed, +4 / -9

---

### Phase 4: `CustomerImportJobConfig` → 5개 설정 파일 분리

**파일**: 1개(367줄) → 5개(총 375줄)

이전에는 Job Flow, Step 정의, Reader/Writer, ThreadPool, Partitioner가 모두 한 파일에 있었다. 역할별로 분리:

```
분리 전                             분리 후
CustomerImportJobConfig (367줄)     CustomerImportJobConfig  (51줄) ── Job Flow만
                                    CsvToStagingStepConfig  (117줄) ── CSV → Staging
                                    StagingToTargetStepConfig(131줄) ── Staging → Target
                                    TaskletStepConfig        (38줄) ── Tasklet Steps
                                    BatchInfraConfig         (38줄) ── ThreadPool + Partitioner
```

#### `CustomerImportJobConfig.java` (51줄) — Job Flow만

```java
@Configuration
@RequiredArgsConstructor
public class CustomerImportJobConfig {

    private final CustomerImportJobParametersValidator validator;

    @Bean
    public Job customerImportJob(JobRepository jobRepository,
                                 Step csvToStagingStep,
                                 Step validateStep,
                                 Step stagingToTargetStep,
                                 Step errorIsolateStep,
                                 Step statsStep) {
        return new JobBuilder("customerImportJob", jobRepository)
                .validator(validator)
                .start(csvToStagingStep)
                .next(validateStep)
                .on("COMPLETED").to(stagingToTargetStep)
                .from(validateStep)
                .on("INVALID").to(errorIsolateStep)
                .from(validateStep)
                .on("*").fail()
                .from(stagingToTargetStep).on("*").to(statsStep)
                .from(errorIsolateStep).on("*").to(statsStep)
                .from(statsStep).on("FAILED").fail()
                .from(statsStep).on("*").end()
                .end()
                .build();
    }
}
```

Step 빈들은 이름으로 주입되므로, 다른 Config 파일에서 `@Bean`으로 등록하면 Spring이 자동으로 연결한다.

#### `BatchInfraConfig.java` (38줄) — 인프라 빈

```java
@Configuration
public class BatchInfraConfig {

    @Bean
    @JobScope
    public ThreadPoolTaskExecutor batchTaskExecutor(
            @Value("#{jobParameters['threadCount'] ?: 4}") int threadCount) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threadCount);
        executor.setMaxPoolSize(threadCount);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("batch-thread-");
        executor.afterPropertiesSet();  // @JobScope에서는 수동 호출 필수
        return executor;
    }

    @Bean
    @StepScope
    public CustomerPartitioner customerPartitioner(
            JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['runDate']}") String runDate) {
        return new CustomerPartitioner(jdbcTemplate, runDate);
    }
}
```

**설계 의도**: `afterPropertiesSet()` 수동 호출이 필요한 이유는 `@JobScope` 빈이 CGLIB 프록시로 생성되어 `InitializingBean` 콜백이 자동 실행되지 않기 때문이다.

#### Config 분할 다이어그램

```
CustomerImportJobConfig (Job Flow)
    │
    ├── csvToStagingStep ←── CsvToStagingStepConfig
    │       ├── Reader (FlatFileItemReader → SynchronizedItemStreamReader)
    │       ├── Processor (RestartableItemProcessor)
    │       ├── Writer (JdbcBatchItemWriter)
    │       └── Skip/Retry (ErrorIsolationSkipListener, RetryLoggingListener)
    │
    ├── validateStep ←── TaskletStepConfig
    ├── errorIsolateStep ←── TaskletStepConfig
    ├── statsStep ←── TaskletStepConfig
    │
    └── stagingToTargetStep ←── StagingToTargetStepConfig
            ├── Master Step (Partitioner) ←── BatchInfraConfig
            ├── Slave Step (Chunk)
            └── ThreadPoolTaskExecutor ←── BatchInfraConfig
```

**주의사항**: 파티셔닝 Slave Step에는 `@JobScope`를 사용하면 안 된다. CGLIB 프록시가 파티셔닝의 Step 복제 메커니즘과 충돌하여 customer 테이블에 0건이 적재되는 문제가 발생한다.

**커밋**: `bce6a46 refactor(config): CustomerImportJobConfig를 5개 설정 파일로 분리` — 5 files changed, +328 / -320

---

### Phase 5: `AbstractBatchTest` 기반 클래스로 테스트 중복 제거

**파일**: `config/AbstractBatchTest.java` (신규, 82줄)

7개 테스트 클래스에서 반복되던 setup과 유틸리티를 추출:

```java
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

    protected JobParameters params(String inputFile, String runDate) {
        return new JobParametersBuilder()
                .addString(PARAM_INPUT_FILE, inputFile, true)
                .addString(PARAM_RUN_DATE, runDate, true)
                .toJobParameters();
    }

    protected int countTable(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName, Integer.class);
        return count != null ? count : 0;
    }

    protected int countByRunDate(String tableName, String runDate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE run_date = ?",
                Integer.class, LocalDate.parse(runDate));
        return count != null ? count : 0;
    }
}
```

**`countByRunDate`에서 `LocalDate.parse()` 사용 이유**: PostgreSQL JDBC 드라이버는 `run_date = ?`에 String을 직접 바인딩하면 타입 추론에 실패하여 `BadSqlGrammarException`을 던진다. SQL 리터럴(`WHERE run_date = '2025-02-05'`)은 자동 캐스팅되지만, 파라미터 바인딩은 명시적 타입 변환이 필요하다.

#### 테스트 클래스 크기 변화

| 테스트 클래스 | Before | After | 감소 |
|-------------|--------|-------|------|
| `CustomerImportJobTest` | 115줄 | 67줄 | -48줄 |
| `CustomerImportJobFlowTest` | 217줄 | 129줄 | -88줄 |
| `FaultToleranceTest` | 193줄 | 134줄 | -59줄 |
| `ParallelTuningTest` | 280줄 | 232줄 | -48줄 |
| `ParamsScopeTest` | 193줄 | 147줄 | -46줄 |
| **합계** | **998줄** | **709줄** | **-289줄** |

`AbstractBatchTest` 82줄 추가 기준, 순수 **207줄 절감**.

**`@SpringBatchTest`와 `StepExecution` 반환 메서드 충돌**: `@SpringBatchTest`가 활성화하는 `StepScopeTestExecutionListener`의 `HippyMethodInvoker`는 클래스 계층 전체에서 `StepExecution`을 반환하는 메서드를 찾아 StepScope 팩토리로 사용하려 한다. 기반 클래스에 이런 메서드가 있으면 "No context to determine Step Executions from" 에러가 발생하므로, `AbstractBatchTest`에는 `StepExecution` 반환 메서드를 두지 않았다.

**커밋**: `f0c1d17 refactor(test): AbstractBatchTest 기반 클래스로 테스트 중복 제거` — 6 files changed, +143 / -350

---

### Phase 6: `effectiveFailAt`에 `volatile` 추가

**파일**: `processor/RestartableItemProcessor.java` (1줄 변경)

#### Before
```java
private int effectiveFailAt;
```

#### After
```java
private volatile int effectiveFailAt;
```

**문제 상황**: 멀티스레드 Step(`csvToStagingStep`)에서 `open()`은 메인 스레드가 호출하고, `process()`는 `batch-thread-*` 스레드들이 호출한다. `volatile` 없이는 각 스레드가 CPU 캐시에 있는 오래된 `effectiveFailAt` 값을 읽을 수 있다.

```
Main Thread                  Worker Threads
    │                             │
    ▼                             │
open() ──┐                        │
  effectiveFailAt = 0  ────┐      │
  (메모리에 쓰기)          │      │
                           │      ▼
                      volatile: 모든 스레드에
                      즉시 가시성 보장
                           │      │
                           └──→ process()
                                  effectiveFailAt 읽기 ← 최신값 보장
```

**`volatile` vs `AtomicInteger` 선택 기준**:
- `effectiveFailAt`: write-once(open), read-many(process) → `volatile`로 충분
- `processedCount`: 여러 스레드가 동시에 increment → `AtomicInteger` 필요 (CAS 연산)

**커밋**: `f9082c5 fix(processor): effectiveFailAt에 volatile 추가 — 멀티스레드 가시성 보장` — 1 file changed, +1 / -1

---

## 데이터 흐름 추적

### 상수/SQL 사용 매핑

```
BatchConstants
├── PARAM_INPUT_FILE ──→ 테스트 8파일, Validator
├── PARAM_RUN_DATE ───→ 테스트 8파일, SkipListener, Validator
├── PARAM_FAIL_AT ────→ RestartabilityTest, ParallelTuningTest
├── TABLE_* ──────────→ 테스트 countTable()/countByRunDate()
└── CTX_PROCESSED_COUNT → RestartableItemProcessor

ValidationSql
├── INVALID_RECORD_WHERE ──→ ValidateTasklet (COUNT)
│                           → ErrorIsolateTasklet (INSERT INTO customer_err)
└── VALID_RECORD_FILTER ───→ CustomerPartitioner (MIN/MAX id)
                            → StagingToTargetStepConfig (WHERE 조건)
```

### Config 분할 후 빈 의존 흐름

```
                   ┌─ BatchInfraConfig ────────────────────────┐
                   │  batchTaskExecutor (ThreadPoolTaskExecutor)│
                   │  customerPartitioner (CustomerPartitioner) │
                   └────────────┬───────────────────────────────┘
                                │ 주입
                                ▼
CustomerImportJobConfig ──→ CsvToStagingStepConfig
(Job Flow 조립)           │   csvToStagingStep
                          │   customerCsvReader
                          │   synchronizedCsvReader
                          │   customerStgWriter
                          │
                          ├→ StagingToTargetStepConfig
                          │   stagingToTargetStep (Master)
                          │   stagingToTargetSlaveStep (Slave)
                          │   partitionedStagingReader
                          │   customerUpsertWriter
                          │
                          └→ TaskletStepConfig
                              validateStep
                              errorIsolateStep
                              statsStep
```

---

## 리팩토링 전후 비교

| 항목 | Before | After |
|------|--------|-------|
| Config 파일 수 | 1개 (367줄) | 5개 (375줄) |
| 상수 클래스 | 없음 | 2개 (77줄) |
| 테스트 기반 클래스 | 없음 | 1개 (82줄) |
| SkipListener `insertErr` | 2개 오버로드 | 1개 통합 |
| `effectiveFailAt` | `int` | `volatile int` |
| **총 변경** | **17 files** | **+537 / -718 (순 -181줄)** |

---

## 트러블슈팅 로그

### 이슈 1: 파티셔닝 Slave Step에 `@JobScope` 적용 시 0건 적재

- **현상**: `stagingToTargetSlaveStep`에 `@JobScope`를 적용하면 customer 테이블에 0건 적재
- **원인**: CGLIB 프록시가 파티셔닝의 Step 복제 메커니즘과 충돌. 프록시 객체가 복제되면서 내부 상태가 초기화됨
- **해결**: Slave Step에서 `@JobScope` 제거. Master Step에만 `@JobScope` 적용

### 이슈 2: `@SpringBatchTest` + `StepExecution` 반환 메서드 충돌

- **현상**: `AbstractBatchTest`에 `StepExecution`을 반환하는 헬퍼 메서드를 두면 "No context to determine Step Executions from" 에러
- **원인**: `StepScopeTestExecutionListener`의 `HippyMethodInvoker`가 클래스 계층의 모든 `StepExecution` 반환 메서드를 StepScope 팩토리로 오인
- **해결**: `AbstractBatchTest`에는 `StepExecution` 반환 메서드를 두지 않음. 필요한 경우 각 테스트 클래스에서 직접 정의

### 이슈 3: PostgreSQL JDBC `run_date = ?` 파라미터 바인딩 실패

- **현상**: `countByRunDate("customer_stg", "2025-02-05")`에서 `BadSqlGrammarException`
- **원인**: String 직접 바인딩 시 PostgreSQL JDBC 드라이버가 `DATE` 타입으로 자동 변환하지 못함. SQL 리터럴은 자동 캐스팅되지만 파라미터 바인딩은 안 됨
- **해결**: `LocalDate.parse(runDate)`로 변환 후 바인딩

---

## 변경된 파일 목록

### 신규 파일 (4개)

| 파일 | 줄 수 | 설명 |
|------|-------|------|
| `constants/BatchConstants.java` | 36 | Job Parameter 키, 기본값, 테이블명 상수 |
| `constants/ValidationSql.java` | 41 | 검증 WHERE 절 중앙 관리 |
| `config/BatchInfraConfig.java` | 38 | ThreadPool, Partitioner 빈 |
| `config/CsvToStagingStepConfig.java` | 117 | CSV → Staging Step + Reader/Writer |
| `config/StagingToTargetStepConfig.java` | 131 | Staging → Target 파티셔닝 Step |
| `config/TaskletStepConfig.java` | 38 | Tasklet 기반 Step 3개 |
| `config/AbstractBatchTest.java` (테스트) | 82 | 테스트 기반 클래스 |

### 수정 파일 (10개)

| 파일 | 변경 내용 |
|------|----------|
| `config/CustomerImportJobConfig.java` | 367→51줄, Job Flow만 남김 |
| `listener/ErrorIsolationSkipListener.java` | `insertErr` 2개→1개 통합 |
| `processor/RestartableItemProcessor.java` | `volatile` 추가 |
| `partitioner/CustomerPartitioner.java` | `VALID_RECORD_FILTER` 상수 사용 |
| `tasklet/ValidateTasklet.java` | `INVALID_RECORD_WHERE` 상수 사용 |
| `tasklet/ErrorIsolateTasklet.java` | `INVALID_RECORD_WHERE` 상수 사용 |
| `config/CustomerImportJobTest.java` (테스트) | 115→67줄, AbstractBatchTest 상속 |
| `config/CustomerImportJobFlowTest.java` (테스트) | 217→129줄, AbstractBatchTest 상속 |
| `config/FaultToleranceTest.java` (테스트) | 193→134줄, AbstractBatchTest 상속 |
| `config/ParallelTuningTest.java` (테스트) | 280→232줄, AbstractBatchTest 상속 |
| `config/ParamsScopeTest.java` (테스트) | 193→147줄, AbstractBatchTest 상속 |

---

## 커밋 히스토리

| # | 해시 | Phase | 메시지 | 변경 |
|---|------|-------|--------|------|
| 1 | `d5f2afa` | Phase 1 | `refactor(constants): JobParameter 키와 테이블명을 BatchConstants로 추출` | 12파일, +150/-99 |
| 2 | `2f6d3da` | Phase 2 | `refactor(sql): 검증 WHERE 절을 ValidationSql로 중앙화` | 5파일, +64/-41 |
| 3 | `4518c58` | Phase 3 | `refactor(listener): ErrorIsolationSkipListener의 중복 insertErr 통합` | 1파일, +4/-9 |
| 4 | `bce6a46` | Phase 4 | `refactor(config): CustomerImportJobConfig를 5개 설정 파일로 분리` | 5파일, +328/-320 |
| 5 | `f0c1d17` | Phase 5 | `refactor(test): AbstractBatchTest 기반 클래스로 테스트 중복 제거` | 6파일, +143/-350 |
| 6 | `f9082c5` | Phase 6 | `fix(processor): effectiveFailAt에 volatile 추가 — 멀티스레드 가시성 보장` | 1파일, +1/-1 |

**전체**: 17 files changed, +537 / -718 (순 -181줄)

---

## 회고

### 잘한 점
- 6개 Phase를 독립적인 커밋으로 분리하여, 각 리팩토링 단계를 개별 리뷰/롤백 가능하게 함
- 기능 변경 없이 구조 개선에만 집중 — 모든 기존 테스트가 수정 없이(또는 최소 수정으로) 통과
- `HippyMethodInvoker` 충돌, `@JobScope` + 파티셔닝 충돌 등 Spring Batch 프레임워크 내부 동작의 제약을 파악하고 우회

### 개선할 점
- Phase 4(Config 분할)에서 순수 이동이라 총 줄 수는 비슷하지만, 각 파일의 역할이 명확해져 유지보수가 쉬워짐. 초기부터 분리했다면 더 좋았을 것
- `RetryTest`는 `@SpringBatchTest`의 `StepScopeTestExecutionListener` 충돌로 `AbstractBatchTest`를 상속하지 못함. 근본적 해결이 필요

---

## 참고 링크

### Spring 공식 문서
- [Spring Batch Reference](https://docs.spring.io/spring-batch/reference/)
- [Configuring a Step](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing.html)
- [Testing a Step](https://docs.spring.io/spring-batch/reference/testing.html)

### Java Concurrency
- [Java volatile keyword](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.3.1.4) — JLS 8.3.1.4
- [AtomicInteger vs volatile](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/package-summary.html)
