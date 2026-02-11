# Week 04: 재시작 (Restartability)

> 작성일: 2025-02-10
> 상태: ✅ 완료

---

## 이번 주 목표

- [x] ExecutionContext의 역할과 동작 이해
- [x] ItemStream 인터페이스 이해
- [x] 재시작 시 "이어서 처리" 구현
- [x] 실패 후 재시작 시나리오 테스트

---

## 핵심 개념 요약 (내 말로)

### ExecutionContext
> 한 줄 정의: Step/Job 실행 중 상태를 저장하는 Key-Value 저장소

- **StepExecutionContext**: Step 범위, Step 재시작 시 복원
- **JobExecutionContext**: Job 범위, Step 간 데이터 공유

재시작 시 마지막 커밋된 상태가 복원되어 "이어서 처리" 가능.

### ItemStream
> 한 줄 정의: Reader/Writer의 상태를 ExecutionContext에 저장/복원하는 인터페이스

```java
public interface ItemStream {
    void open(ExecutionContext executionContext);   // Step 시작 시 호출
    void update(ExecutionContext executionContext); // 청크 커밋 직전 호출
    void close();                                   // Step 종료 시 호출
}
```

| 메서드 | 호출 시점 | 용도 |
|--------|----------|------|
| `open()` | Step 시작 시 | 이전 상태 복원 (재시작 시) |
| `update()` | 청크 커밋 직전 | 현재 상태 저장 |
| `close()` | Step 종료 시 | 리소스 정리 |

### .processor() vs .stream()
> 한 줄 정의: 하나의 Bean이 두 역할을 하면 둘 다 등록해야 함

```java
.processor(restartableItemProcessor)  // process() 호출됨
.stream(restartableItemProcessor)     // open/update/close 호출됨
```

- `ItemProcessor`와 `ItemStream` 두 인터페이스를 구현한 경우 **둘 다 등록 필요**
- Reader/Writer는 대부분 ItemStream을 자동 구현하여 자동 등록됨
- **Processor는 명시적으로 `.stream()` 등록 필수**

---

## 실습 결과

### 입력
- `input/customers_1000.csv` (1000건)

### 테스트 시나리오

| 시나리오 | 설명 | 검증 내용 | 결과 |
|----------|------|----------|------|
| 시나리오1 | 500건에서 강제 실패 (failAt=500) | 청크 단위 롤백 확인 | 400건 적재, FAILED |
| 시나리오2 | ItemStream update() 호출 확인 | 청크 커밋 시점에 상태 저장 | 300건 적재 (350에서 실패) |
| 시나리오3 | 정상 실행 1000건 완료 | 전체 처리 성공 | 1000건 적재, COMPLETED |
| 시나리오4 | ExecutionContext 저장 확인 | processedCount 저장 | processedCount=400 저장됨 |
| 시나리오5 | **restart() + UPSERT 멱등성** | 1차 FAILED → 2차 restart → 중복 없이 1000건 | ✅ 최종 1000건 |

### 성공 기준 달성
- [x] 강제 실패 후 400건 적재 확인 (청크 100 × 4 커밋)
- [x] update()가 청크마다 호출되어 상태 저장
- [x] 정상 실행 시 1000건 전체 적재
- [x] **실제 restart() 호출 후 UPSERT로 멱등성 보장** ✅

---

## Week 03 → Week 04 변경 비교

| 항목 | Week 03 | Week 04 |
|------|---------|---------|
| Processor | 람다 `@StepScope` (`CustomerCsv → CustomerStg`) | `RestartableItemProcessor` (`ItemProcessor` + `ItemStream`) |
| 상태 저장 | 없음 | `ExecutionContext`에 `processedCount` 저장 |
| Step 등록 | `.processor()` 만 | `.processor()` + `.stream()` 둘 다 필요 |
| Writer (stg) | INSERT | UPSERT (`ON CONFLICT customer_id, run_date`) |
| 스키마 | - | `customer_stg`에 `(customer_id, run_date)` UNIQUE 인덱스 추가 |
| Reader | `saveState` 미설정 | `.saveState(true)` 명시 |
| 재시작 | 불가 | `failAt` + restart + `effectiveFailAt` 패턴 |

---

## 구현 코드

### RestartableItemProcessor.java

`@RequiredArgsConstructor`로 `JdbcTemplate`을 생성자 주입받는다. `failAt`(강제 실패 지점)과 별도로 `effectiveFailAt` 필드를 두어, **재시작 시 failAt을 무시**하는 3단계 전략을 구현한다:

1. `open()`: DB에 해당 `run_date` 데이터가 이미 존재하면 재시작으로 감지 → `effectiveFailAt = 0`
2. `process()`: `effectiveFailAt` 기준으로 실패 판단 → 재시작 시에는 실패 없이 통과
3. Writer의 UPSERT가 기존 데이터를 UPDATE하여 멱등성 보장

```java
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class RestartableItemProcessor
        implements ItemProcessor<CustomerCsv, CustomerStg>, ItemStream {

    private static final String PROCESSED_COUNT_KEY = "processedCount";

    private final JdbcTemplate jdbcTemplate;

    private int processedCount = 0;

    @Value("#{jobParameters['failAt'] ?: 0}")
    private int failAt;

    /** 실제 적용될 failAt 값 (재시작 시 0으로 설정됨) */
    private int effectiveFailAt;

    @Value("#{jobParameters['runDate']}")
    private String runDate;

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
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
            this.effectiveFailAt = 0;
            log.info("Restart detected - {} records already exist, disabling failAt", existingCount);
        } else {
            this.effectiveFailAt = this.failAt;
            log.info("First execution - failAt={}", this.effectiveFailAt);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt(PROCESSED_COUNT_KEY, this.processedCount);
        log.debug("Updated processedCount: {}", this.processedCount);
    }

    @Override
    public CustomerStg process(CustomerCsv csv) {
        this.processedCount += 1;
        if (effectiveFailAt > 0 && processedCount == effectiveFailAt) {
            throw new RuntimeException("Forced failure at " + effectiveFailAt);
        }
        LocalDate parsedRunDate = LocalDate.parse(runDate);
        return new CustomerStg(
                csv.customerId(), csv.email(), csv.name(), csv.phone(), parsedRunDate
        );
    }

    @Override
    public void close() throws ItemStreamException {
        log.info("Closing RestartableItemProcessor - final processedCount: {}", processedCount);
    }

    public int getProcessedCount() {
        return processedCount;
    }
}
```

### CustomerImportJobConfig.java (Step 설정)

```java
@Bean
public Step csvToStagingStep(JobRepository jobRepository,
                             FlatFileItemReader<CustomerCsv> customerCsvReader,
                             RestartableItemProcessor restartableItemProcessor,
                             JdbcBatchItemWriter<CustomerStg> customerStgWriter) {
    return new StepBuilder("csvToStagingStep", jobRepository)
            .<CustomerCsv, CustomerStg>chunk(CHUNK_SIZE)
            .reader(customerCsvReader)
            .processor(restartableItemProcessor)
            .writer(customerStgWriter)
            .stream(restartableItemProcessor)  // ItemStream 등록 필수!
            .build();
}
```

### customerStgWriter (UPSERT로 멱등성 보장)

```java
@Bean
public JdbcBatchItemWriter<CustomerStg> customerStgWriter() {
    String sql = """
            INSERT INTO customer_stg (customer_id, email, name, phone, run_date)
            VALUES (:customerId, :email, :name, :phone, :runDate)
            ON CONFLICT (customer_id, run_date)
            DO UPDATE SET
                email = EXCLUDED.email,
                name = EXCLUDED.name,
                phone = EXCLUDED.phone
            """;

    return new JdbcBatchItemWriterBuilder<CustomerStg>()
            .dataSource(dataSource)
            .sql(sql)
            .beanMapped()
            .build();
}
```

---

## 청크 단위 트랜잭션과 재시작

### 동작 원리

```
청크 1 (1-100)   → update(100) → 커밋 ✓
청크 2 (101-200) → update(200) → 커밋 ✓
청크 3 (201-300) → update(300) → 커밋 ✓
청크 4 (301-400) → update(400) → 커밋 ✓
청크 5 (401-500) → 500에서 예외 → 롤백 ✗

ExecutionContext에는 400이 저장됨
재시작 시 401건부터 처리 (이론적으로)
```

### 1차 실행 → FAILED → 2차 실행(restart) 전체 흐름

```
=== 1차 실행 (failAt=500) ===
open(): DB에 0건 → effectiveFailAt=500 설정
청크1 (1-100)   → INSERT 100건 → update(100) → 커밋 ✓
청크2 (101-200) → INSERT 100건 → update(200) → 커밋 ✓
청크3 (201-300) → INSERT 100건 → update(300) → 커밋 ✓
청크4 (301-400) → INSERT 100건 → update(400) → 커밋 ✓
청크5 (401-500) → 500에서 RuntimeException → 롤백 ✗
→ DB: customer_stg 400건, Job FAILED

=== 2차 실행 (restart, effectiveFailAt=0) ===
open(): DB에 400건 존재 감지 → effectiveFailAt=0 설정 (failAt 무시)
청크1 (1-100)   → UPSERT 100건 (기존 → UPDATE) → 커밋 ✓
청크2 (101-200) → UPSERT 100건 (기존 → UPDATE) → 커밋 ✓
청크3 (201-300) → UPSERT 100건 (기존 → UPDATE) → 커밋 ✓
청크4 (301-400) → UPSERT 100건 (기존 → UPDATE) → 커밋 ✓
청크5 (401-500) → UPSERT 100건 (신규 → INSERT) → 커밋 ✓
...
청크10 (901-1000) → UPSERT 100건 (신규 → INSERT) → 커밋 ✓
→ DB: customer_stg 1000건 (중복 없음), Job COMPLETED
```

> **핵심**: 1차에서 커밋된 400건은 UPSERT의 `ON CONFLICT DO UPDATE`로 덮어쓰고, 나머지 600건은 새로 INSERT. `effectiveFailAt=0`이므로 실패 지점 없이 전체 통과.

---

## 파일 변경 목록

| 파일 | 변경 내용 |
|------|----------|
| `input/customers_1000.csv` | 1000건 테스트 데이터 신규 생성 |
| `processor/RestartableItemProcessor.java` | ItemStream 구현, ExecutionContext 로깅 추가 |
| `config/CustomerImportJobConfig.java` | `.stream()` 등록, `saveState(true)`, UPSERT Writer |
| `schema/V001__create_business_tables.sql` | customer_stg에 UNIQUE 인덱스 추가 |
| `test/RestartabilityTest.java` | 재시작 테스트, UPSERT 멱등성 테스트 추가 |

---

## 트러블슈팅 로그

### 이슈 1: Spring Batch 6.0.2에서 재시작 시 ExecutionContext 복원 안 됨 ⚠️

**현상**:
- 1차 실행 후 `StepExecution.getExecutionContext()`에 `processedCount=400` 저장됨
- 2차 실행(재시작) 시 `open()`에 전달된 ExecutionContext에는 `batch.version=6.0.2`만 있음
- "Starting fresh - processedCount: 0" 출력
- Reader도 처음부터 다시 읽어 중복 데이터 발생 (1400건)

**원인 분석**:
```
1차 실행 후 ExecutionContext:
  - processedCount=400 ← 저장됨!
  - customerCsvReader.read.count=400 ← 저장됨!

2차 실행 시 open()에 전달된 ExecutionContext:
  - batch.version=6.0.2 ← 이것만 있음!
```

**관련 이슈**:
- [Spring Batch #5117](https://github.com/spring-projects/spring-batch/issues/5117): ExecutionContext not loaded (6.0.1에서 수정됨)
- [Spring Batch #5182](https://github.com/spring-projects/spring-batch/issues/5182): ChunkOrientedStep ExecutionContext 문제

**조사 결과**:
- 같은 JobInstance (ID=1) 확인됨
- Step BatchStatus: FAILED, ExitStatus: FAILED 확인됨
- 하지만 재시작 시 이전 ExecutionContext가 새 StepExecution에 복원되지 않음
- Spring Batch 6.0.2 버그로 추정 (추가 조사 필요)

### 이슈 2: 워크어라운드 - UPSERT로 멱등성 보장 ✅

**해결 방법**: `customer_stg` 테이블에 UPSERT 적용

```sql
-- 스키마에 UNIQUE 제약 추가
CREATE UNIQUE INDEX IF NOT EXISTS idx_customer_stg_unique
ON customer_stg(customer_id, run_date);
```

```java
// customerStgWriter에서 UPSERT 사용
String sql = """
    INSERT INTO customer_stg (customer_id, email, name, phone, run_date)
    VALUES (:customerId, :email, :name, :phone, :runDate)
    ON CONFLICT (customer_id, run_date)
    DO UPDATE SET
        email = EXCLUDED.email,
        name = EXCLUDED.name,
        phone = EXCLUDED.phone
    """;
```

**테스트 검증 (시나리오5)**:
```
1차 실행: failAt=500 → 400건 적재 → FAILED
2차 실행: JobOperator.restart(executionId) → 처음부터 다시 처리 → UPSERT로 중복 방지
최종 결과: 1000건 (중복 없음) ✅
```

**결과**:
- ExecutionContext 복원이 안 되어 Reader가 처음부터 다시 읽어도
- UPSERT가 중복을 방지하여 최종 1000건 적재 (중복 없음)
- 멱등성(Idempotency) 보장

### 이슈 3: Spring Batch 6.x에서 startJob()과 restart() 동작

**현상**:
- `startJob()`을 같은 파라미터로 두 번 호출하면 **기존 FAILED JobExecution을 반환**
- `JobOperator.restart(executionId)`도 **같은 JobExecutionId 반환**

**분석**:
```
1차 startJob() → JobExecution ID: 1, Status: FAILED
2차 startJob() → JobExecution ID: 1 (동일!)
restart(1) → JobExecution ID: 1 (동일!)
```

Spring Batch 6.x에서 재시작 메커니즘이 이전 버전과 다르게 동작합니다.

### 결론: 실무 권장사항

Spring Batch 6.0.2에서 발견된 재시작 관련 이슈들로 인해:

1. **멱등성 보장이 더 중요**: "이어서 처리"보다 "처음부터 다시 처리해도 결과 동일"
2. **UPSERT 패턴 권장**: INSERT 대신 UPSERT로 중복 방지
3. **DELETE-INSERT 패턴**: 재시작 전 해당 run_date 데이터 삭제 후 재적재
4. **검증 로직 강화**: 중복 데이터 검증을 스테이징 단계에서 수행

---

## 회고

### 잘한 점
- ItemStream의 open/update/close 생명주기 이해
- `.processor()`와 `.stream()` 둘 다 등록해야 하는 이유 학습
- 청크 단위 트랜잭션과 상태 저장 시점 이해
- Spring Batch 6.x 버그 발견 시 워크어라운드(UPSERT) 적용
- **UPSERT로 멱등성을 보장하는 방식이 실무에서 더 안정적**이라는 것을 학습

### 개선할 점
- Spring Batch 6.x의 재시작 API 변경사항 추가 조사 필요
- ExecutionContext 복원 이슈 근본 원인 파악
- Spring Batch 팀에 이슈 리포트 고려

### 핵심 교훈
> **재시작 시 "이어서 처리"보다 "UPSERT로 멱등성 보장"이 더 실용적**
>
> 이론적으로는 ExecutionContext를 통해 이어서 처리하는 것이 효율적이지만,
> 실무에서는 UPSERT나 DELETE-INSERT 패턴으로 멱등성을 보장하는 것이
> 버그나 엣지 케이스에 더 강건함

### 다음 주 준비 (Week 05)
- Skip/Retry를 활용한 내결함성(Fault Tolerance) 구현
- SkipPolicy, RetryPolicy 설정
- 오류 레코드 처리 전략

---

## 참고 링크

### Spring 공식 문서
- [ExecutionContext](https://docs.spring.io/spring-batch/reference/domain.html#executionContext)
- [ItemStream](https://docs.spring.io/spring-batch/reference/readers-and-writers/item-stream.html)
- [Configuring a Step (Restartability)](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring.html)
- [Restarting a Job](https://docs.spring.io/spring-batch/reference/job/configuring.html#restartability)
