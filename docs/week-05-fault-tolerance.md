# Week 05: 내결함성 (Fault Tolerance)

> 작성일: 2025-02-11
> 상태: ✅ 완료

---

## 이번 주 목표

- [x] Skip 정책 구현 (잘못된 데이터 건너뛰기)
- [x] Retry 정책 구현 (일시적 오류 재시도)
- [x] Listener로 오류 추적 및 로깅
- [x] 오류 레코드 격리 테이블 적재
- [x] 내결함성 테스트 작성 (4개 시나리오)

---

## 핵심 개념 요약 (내 말로)

### Skip
> 한 줄 정의: 특정 예외 발생 시 해당 아이템을 건너뛰고 계속 진행

- Read Skip: CSV 파싱 실패한 레코드 건너뛰기 (FlatFileParseException)
- Process Skip: 비즈니스 검증 실패한 레코드 건너뛰기 (ValidationException)
- Write Skip: DB 쓰기 실패한 레코드 건너뛰기

### Retry
> 한 줄 정의: 특정 예외 발생 시 지정 횟수만큼 재시도

- 일시적 오류(네트워크, DB 락)에 유용
- 재시도 횟수 초과 시 Skip 또는 실패

### Skip vs Retry
| 구분 | Skip | Retry |
|------|------|-------|
| 목적 | 잘못된 데이터 무시 | 일시적 오류 극복 |
| 대상 예외 | `ValidationException` | `DeadlockLoserDataAccessException` |
| 결과 | 건너뛰고 계속 진행 | 성공할 때까지 재시도 |

### SkipListener
> 한 줄 정의: Skip 발생 시 콜백을 받아 오류 레코드를 격리하는 Listener

| 메서드 | 시점 | 파라미터 |
|--------|------|----------|
| `onSkipInRead(Throwable)` | Reader에서 Skip 발생 시 | 예외만 (아이템 없음) |
| `onSkipInProcess(T, Throwable)` | Processor에서 Skip 발생 시 | 원본 아이템 + 예외 |
| `onSkipInWrite(S, Throwable)` | Writer에서 Skip 발생 시 | 변환된 아이템 + 예외 |

### RetryListener
> 한 줄 정의: Retry 발생 시 콜백을 받아 재시도 상황을 로깅하는 Listener

| 메서드 | 시점 | 로그 레벨 | 비고 |
|--------|------|-----------|------|
| `onRetryableExecution(RetryPolicy, Retryable, RetryState)` | 매 실행마다 호출 | WARN (retryCount > 0일 때만) | 첫 시도에는 예외 없으므로 retryCount 체크 필수 |
| `onRetryPolicyExhaustion(RetryPolicy, Retryable, RetryException)` | 재시도 횟수 소진 시 | ERROR | 최종 실패 원인 로깅 |

> **주의**: `onRetryableExecution`은 첫 시도(retryCount=0)에서도 호출된다. 이때 `state.getLastException()`을 호출하면 `IllegalStateException("No exception recorded")`이 발생하므로, 반드시 `state.getRetryCount() > 0`을 먼저 확인해야 한다.

> **패키지**: `org.springframework.core.retry.RetryListener` (Spring Framework 7.x — NOT `org.springframework.retry`)

---

## 내결함성 적용 전후 비교

### Week 03~04 (내결함성 없음)
```
CSV 파일 → csvToStagingStep → 전체가 stg에 적재 (오류 포함)
                                    ↓
                           validateStep에서 오류 감지
                                    ↓
                     INVALID → errorIsolateStep이 오류를 err로 이동
```
- 모든 레코드가 일단 stg에 들어감
- 사후 검증(validateStep)으로 오류 분류

### Week 05 (내결함성 적용)
```
CSV 파일 → csvToStagingStep
              ├── 정상 → stg에 적재
              └── 오류 → ValidationException → Skip
                           └── SkipListener → 즉시 err에 격리
```
- Processor 단계에서 사전 검증
- 오류 레코드는 stg에 도달하지 않음
- SkipListener가 즉시 `customer_err`에 격리

---

## 구현 상세

### 1. Processor 검증 로직 추가 (`RestartableItemProcessor`)

기존 Week 04의 `RestartableItemProcessor`에 `validate()` 메서드를 추가했다.
`process()` 진입 시 `validate()`를 먼저 호출하여, 검증 실패 시 `ValidationException`을 던진다.

```java
// RestartableItemProcessor.java

@Override
public CustomerStg process(CustomerCsv csv) {
    this.processedCount += 1;
    validate(csv);                       // ← Week 05 추가
    if (effectiveFailAt > 0 && processedCount == effectiveFailAt) {
        throw new RuntimeException("Forced failure at " + effectiveFailAt);
    }
    LocalDate parsedRunDate = LocalDate.parse(runDate);
    return new CustomerStg(csv.customerId(), csv.email(), csv.name(), csv.phone(), parsedRunDate);
}

private void validate(CustomerCsv csv) {
    // 1순위: customerId 필수
    if (csv.customerId() == null || csv.customerId().isBlank()) {
        throw new ValidationException("Customer ID is required");
    }
    // 2순위: email 필수
    if (csv.email() == null || csv.email().isBlank()) {
        throw new ValidationException("Email is required");
    }
    // 3순위: email 형식 (@ 포함)
    if (!csv.email().contains("@")) {
        throw new ValidationException("Email address must contain @");
    }
}
```

**검증 순서가 중요한 이유**: 빈 customerId 행이 동시에 빈 email이어도 `"Customer ID is required"`만 발생한다.
첫 번째 실패 조건에서 바로 예외를 던지므로, 오류 메시지가 가장 우선순위 높은 검증 규칙을 반영한다.

### 2. ErrorIsolationSkipListener 구현

Skip된 레코드를 `customer_err` 테이블에 격리하는 Listener이다.

```java
// ErrorIsolationSkipListener.java

@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorIsolationSkipListener implements SkipListener<CustomerCsv, CustomerStg> {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void onSkipInRead(Throwable t) {
        // Reader Skip 시 아이템 정보 없음 → null로 기록
        insertErr(getErrMsg(t));
    }

    @Override
    public void onSkipInProcess(CustomerCsv item, Throwable t) {
        // Processor Skip → 원본 CSV 데이터를 err에 기록
        insertErr(getErrMsg(t), item.customerId(), item.email(), item.name(), item.phone());
    }

    @Override
    public void onSkipInWrite(CustomerStg item, Throwable t) {
        // Writer Skip → 변환된 Stg 데이터를 err에 기록
        insertErr(getErrMsg(t), item.customerId(), item.email(), item.name(), item.phone());
    }

    // @StepScope 없이 JobParameter에 접근하는 방법
    private String getRunDate() {
        return StepSynchronizationManager.getContext()
                .getStepExecution()
                .getJobParameters()
                .getString("runDate");
    }
}
```

**`insertErr` 오버로딩**: Read Skip은 아이템 정보가 없으므로 null로 채우고, Process/Write Skip은 아이템 정보를 포함하여 기록한다.

### 4. RetryLoggingListener 구현

재시도 발생 시 WARN 로깅, 재시도 소진 시 ERROR 로깅을 수행하는 Listener이다.
Retry는 일시적 오류에 대한 자동 복구이므로, 성공하면 기록이 불필요하여 DB 저장 없이 로깅만 수행한다.

```java
// RetryLoggingListener.java

@Slf4j
@Component
public class RetryLoggingListener implements RetryListener {

    @Override
    public void onRetryableExecution(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
        if (state.getRetryCount() > 0) {
            log.warn("Retry attempt #{} — exception: {}",
                    state.getRetryCount(),
                    state.getLastException().getMessage());
        }
    }

    @Override
    public void onRetryPolicyExhaustion(RetryPolicy policy, Retryable<?> retryable, RetryException ex) {
        log.error("Retry exhausted after {} attempts — cause: {}",
                ex.getRetryCount(),
                ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
    }
}
```

**`retryCount > 0` 가드가 필수인 이유**: `onRetryableExecution`은 첫 시도(retryCount=0)에서도 호출된다.
이때 `state.getLastException()`을 호출하면 아직 기록된 예외가 없어 `IllegalStateException("No exception recorded")`이 발생한다.
이 가드 없이는 모든 정상 실행에서 Step이 FAILED된다.

### 3. csvToStagingStep 내결함성 설정 (`CustomerImportJobConfig`)

```java
// CustomerImportJobConfig.java

@Bean
public Step csvToStagingStep(JobRepository jobRepository,
                             FlatFileItemReader<CustomerCsv> customerCsvReader,
                             RestartableItemProcessor restartableItemProcessor,
                             JdbcBatchItemWriter<CustomerStg> customerStgWriter,
                             ErrorIsolationSkipListener errorIsolationSkipListener) {
    return new StepBuilder("csvToStagingStep", jobRepository)
            .<CustomerCsv, CustomerStg>chunk(CHUNK_SIZE)
            .reader(customerCsvReader)
            .processor(restartableItemProcessor)
            .writer(customerStgWriter)
            // ▼ Week 05: 내결함성 설정
            .faultTolerant()
            .skip(ValidationException.class)        // Skip 대상 예외
            .skipLimit(10)                           // 최대 10건까지 Skip 허용
            .skipListener(errorIsolationSkipListener) // SkipListener 등록 (주의: .listener() 아님!)
            .retry(DeadlockLoserDataAccessException.class) // Retry 대상 예외
            .retryLimit(3)                           // 최대 3회 재시도
            .retryListener(retryLoggingListener)     // RetryListener 등록
            // ▲ Week 05 추가분
            .stream(customerCsvReader)
            .stream(restartableItemProcessor)
            .build();
}
```

| 설정 | 값 | 의미 |
|------|-----|------|
| `faultTolerant()` | - | 내결함성 모드 활성화 (필수) |
| `skip(ValidationException.class)` | - | Processor 검증 실패 시 Skip |
| `skipLimit(10)` | 10 | 10건 초과 Skip 시 Step FAILED |
| `skipListener(...)` | ErrorIsolationSkipListener | Skip 콜백으로 customer_err 기록 |
| `retry(DeadlockLoserDataAccessException.class)` | - | DB 교착 상태 시 재시도 |
| `retryLimit(3)` | 3 | 최대 3회 재시도 |
| `retryListener(...)` | RetryLoggingListener | Retry 콜백으로 재시도 로깅 |

---

## 테스트 데이터

### `customers_dirty_20250205.csv` (6건: 정상 3, 오류 3)

| 행 | customerId | email | 검증 결과 | error_message |
|----|-----------|-------|----------|---------------|
| 1 | C001 | valid1@example.com | ✅ 통과 | - |
| 2 | C002 | *(빈값)* | ❌ Skip | `Email is required` |
| 3 | C003 | invalid-no-at | ❌ Skip | `Email address must contain @` |
| 4 | *(빈값)* | empty-id@example.com | ❌ Skip | `Customer ID is required` |
| 5 | C005 | valid2@example.com | ✅ 통과 | - |
| 6 | C006 | valid3@example.com | ✅ 통과 | - |

### `customers_very_dirty_20250205.csv` (15건: 정상 3, 오류 12)

| 행 | 오류 유형 | 비고 |
|----|----------|------|
| 1, 8, 12 | 정상 | C001, C008, C012 |
| 2, 5, 9, 13 | 빈 이메일 | `Email is required` × 4 |
| 3, 6, 10, 14 | @ 없는 이메일 | `Email address must contain @` × 4 |
| 4, 7, 11, 15 | 빈 customerId | `Customer ID is required` × 4 |

오류 12건 > skipLimit 10 → 11번째 Skip 시점에서 `SkipLimitExceededException` → Step FAILED

---

## 데이터 흐름 추적 (dirty 데이터)

```
입력: customers_dirty_20250205.csv (6건)
       ↓
┌─────────────────────────────────────────────────────────────┐
│ csvToStagingStep                                            │
│                                                             │
│  Reader: 6건 읽기                                           │
│       ↓                                                     │
│  Processor (validate → 변환):                                │
│    C001 ✅ → CustomerStg                                    │
│    C002 ❌ → ValidationException("Email is required")       │
│              → Skip → SkipListener.onSkipInProcess()        │
│              → customer_err INSERT                          │
│    C003 ❌ → ValidationException("Email address must...")   │
│              → Skip → customer_err INSERT                   │
│    빈ID ❌ → ValidationException("Customer ID is required") │
│              → Skip → customer_err INSERT                   │
│    C005 ✅ → CustomerStg                                    │
│    C006 ✅ → CustomerStg                                    │
│       ↓                                                     │
│  Writer: 3건 stg에 UPSERT                                   │
│                                                             │
│  결과: processSkipCount=3, writeCount=3, Step COMPLETED     │
└─────────────────────────────────────────────────────────────┘
       ↓
┌─────────────────────────────────────────────────────────────┐
│ validateStep                                                │
│  stg 3건 (C001, C005, C006) — 중복 없음 → ExitStatus COMPLETED │
└─────────────────────────────────────────────────────────────┘
       ↓
┌─────────────────────────────────────────────────────────────┐
│ stagingToTargetStep                                         │
│  stagingReader: stg에서 유효 레코드 조회 → 3건               │
│  customerUpsertWriter: customer 테이블에 3건 UPSERT          │
└─────────────────────────────────────────────────────────────┘
       ↓
┌─────────────────────────────────────────────────────────────┐
│ statsStep (StatsTasklet)                                    │
│  totalCount  = COUNT(customer_stg WHERE run_date) = 3       │
│  errorCount  = COUNT(customer_err WHERE run_date) = 3       │
│  successCount = 3 - 3 = 0                                  │
│                                                             │
│  errorCount > 0 → ExitStatus.FAILED                        │
└─────────────────────────────────────────────────────────────┘
       ↓
  Job Flow: statsStep "FAILED" → .fail() → Job FAILED
```

### 핵심 발견: SkipListener ↔ StatsTasklet 상호작용

| 항목 | Week 03~04 (Skip 없음) | Week 05 (Skip 있음) |
|------|----------------------|---------------------|
| customer_err 기록 시점 | errorIsolateStep (INVALID 경로만) | SkipListener (모든 경로) |
| 오류 데이터의 stg 진입 | 모든 레코드가 stg에 들어감 | 오류 레코드는 stg에 없음 |
| StatsTasklet 공식 | success = stg - err (정확) | success = stg - err (과소 계산) |
| dirty 데이터 시 Job 상태 | FAILED (INVALID 경로) | FAILED (COMPLETED 경로이지만 statsStep이 err 감지) |

**결론**: Skip이 도입되면서 `customer_err`에 기록하는 주체가 `errorIsolateStep` → `SkipListener`로 바뀌었다.
StatsTasklet은 `customer_err` 건수로 오류를 판단하므로, **Skip된 레코드가 있으면 항상 Job FAILED**가 된다.
이는 "오류가 하나라도 있으면 FAILED" 정책으로, csvToStagingStep 자체(COMPLETED)와 Job 상태(FAILED)가 분리되는 결과를 낳는다.

---

## 테스트 시나리오 (`FaultToleranceTest`)

### 시나리오 1: 스킵 동작 검증

| 항목 | 기대값 |
|------|--------|
| 입력 | `customers_dirty_20250205.csv` (6건) |
| Job 상태 | FAILED (StatsTasklet이 customer_err 감지) |
| csvToStagingStep 상태 | COMPLETED (Skip 허용 범위 내) |
| processSkipCount | 3 |
| writeCount | 3 |
| Step 실행 순서 | csvToStagingStep → validateStep → stagingToTargetStep → statsStep |

### 시나리오 2: 오류 격리 확인

| 항목 | 기대값 |
|------|--------|
| 입력 | `customers_dirty_20250205.csv` (6건) |
| customer_err 총 건수 | 3 |
| `"Email is required"` | 1건 (C002) |
| `"Email address must contain @"` | 1건 (C003) |
| `"Customer ID is required"` | 1건 (빈 ID 행) |

### 시나리오 3: skipLimit 초과

| 항목 | 기대값 |
|------|--------|
| 입력 | `customers_very_dirty_20250205.csv` (15건, 오류 12) |
| Job 상태 | FAILED |
| csvToStagingStep 상태 | FAILED (skipLimit 10 초과) |
| 후속 Step | 실행 안됨 (csvToStagingStep에서 중단) |

### 시나리오 4: 정상 데이터 회귀

| 항목 | 기대값 |
|------|--------|
| 입력 | `customers_20250205.csv` (100건) |
| Job 상태 | COMPLETED |
| processSkipCount | 0 |
| writeCount | 100 |
| customer 테이블 | 100건 |

### 시나리오 5: Retry 성공 (`RetryTest`)

| 항목 | 기대값 |
|------|--------|
| 입력 | `customers_clean_6.csv` (6건 정상) |
| Writer 동작 | 첫 write()만 DeadlockLoserDataAccessException → 이후 정상 |
| Step 상태 | COMPLETED |
| writeCount | 6 |
| WRITE_CALL_COUNT | 2 (1회 실패 + 1회 성공) |

### 시나리오 6: Retry 소진 (`RetryTest`)

| 항목 | 기대값 |
|------|--------|
| 입력 | `customers_clean_6.csv` (6건 정상) |
| Writer 동작 | 항상 DeadlockLoserDataAccessException (999회) |
| Step 상태 | FAILED (retryLimit 초과) |

---

## 트러블슈팅 로그

### 이슈 1: `.listener()`로 SkipListener가 등록되지 않음

- **현상**: `.listener(errorIsolationSkipListener)` 사용 시 Skip 콜백 미호출
- **원인**: Spring Batch 6.x에서 `chunk(int)`는 `ChunkOrientedStepBuilder`를 반환한다. 이 빌더의 `.listener(Object)`는 일반 `StepListener`만 등록하고 `SkipListener`는 무시한다.
- **해결**: `.skipListener(errorIsolationSkipListener)` 전용 메서드 사용

### 이슈 2: `@StepScope` 프록시와 `skipListener()` 호환 안됨

- **현상**: `@StepScope` + `@Component` 조합의 SkipListener를 `skipListener()`로 등록하면 런타임 에러
- **원인**: CGLIB 프록시 객체가 `ChunkOrientedStepBuilder.skipListener()` 내부에서 정상 동작하지 않음
- **해결**: `@StepScope` 제거 → `StepSynchronizationManager`로 JobParameter 직접 접근

```java
// ❌ 이렇게 하면 안됨
@Component @StepScope
public class ErrorIsolationSkipListener implements SkipListener<...> {
    @Value("#{jobParameters['runDate']}") private String runDate;  // Late Binding
}

// ✅ 이렇게 해야 함
@Component  // @StepScope 없음
public class ErrorIsolationSkipListener implements SkipListener<...> {
    private String getRunDate() {
        return StepSynchronizationManager.getContext()   // 런타임에 직접 접근
                .getStepExecution().getJobParameters().getString("runDate");
    }
}
```

### 이슈 3: dirty 데이터 Skip 후 Job이 COMPLETED가 아닌 FAILED

- **현상**: 3건 Skip 후 csvToStagingStep은 COMPLETED인데 Job은 FAILED
- **원인**: SkipListener가 `customer_err`에 3건 기록 → StatsTasklet이 `errorCount=3 > 0` 감지 → `ExitStatus.FAILED`
- **분석**: `StatsTasklet` 공식이 `successCount = stgCount - errCount`인데, Skip된 레코드는 stg에 없고 err에만 있어서 `success = 3 - 3 = 0`이 됨
- **결론**: 현재 설계상 의도된 동작. csvToStagingStep(COMPLETED)과 Job(FAILED)의 상태가 다를 수 있다는 것을 이해

### Spring Batch 6.x SkipListener 등록 요약

| API | 동작 | 결과 |
|-----|------|------|
| `.listener(Object)` | 일반 StepListener만 등록 | SkipListener 콜백 **안됨** |
| `.skipListener(SkipListener)` | SkipListener 전용 등록 | SkipListener 콜백 **됨** ✅ |
| `@StepScope` + `skipListener()` | CGLIB 프록시 호환 문제 | **런타임 에러** |
| `StepSynchronizationManager` + `skipListener()` | 프록시 없이 직접 접근 | **정상 동작** ✅ |

### 이슈 4: `onRetryableExecution`에서 `IllegalStateException("No exception recorded")`

- **현상**: `RetryLoggingListener` 추가 후 모든 Step이 FAILED — `state.getLastException()` 호출 시 예외
- **원인**: `onRetryableExecution`은 첫 시도(retryCount=0)에서도 호출된다. 이때 아직 기록된 예외가 없어서 `RetryState.getLastException()`이 `IllegalStateException`을 던짐
- **해결**: `state.getRetryCount() > 0` 가드 추가 — 실제 재시도(retryCount ≥ 1)에서만 로깅

### 이슈 5: `retryPolicy(Duration.ZERO)` vs `.retry().retryLimit()` backoff 차이

- **현상**: `.retry().retryLimit(3)` 사용 시 테스트가 수 분 동안 멈춤 (100건 데이터 기준)
- **원인**: 기본 backoff 지연 + scan mode에서 개별 아이템마다 retry → 수백 번의 retry 발생
- **해결**: 테스트에서 `RetryPolicy.builder().delay(Duration.ZERO).maxRetries(3).includes(...)` 사용하여 backoff 제거

### Spring Batch 6.x RetryListener 등록 요약

| API | 동작 | 비고 |
|-----|------|------|
| `.retryListener(RetryListener)` | RetryListener 등록 | `org.springframework.core.retry.RetryListener` 사용 |
| `.retryPolicy(RetryPolicy)` | 커스텀 retry 정책 설정 | backoff, maxRetries 등 세밀 제어 가능 |
| `.retry(Class).retryLimit(int)` | 간편 설정 (기본 backoff 포함) | 테스트에서는 느릴 수 있음 |

---

## Skip/Retry 동작 흐름

### Skip 흐름 (Processor 레벨)
```
[Chunk 처리 시작] → 아이템 N개를 한번에 처리 시도
     ↓
[Processor] → ValidationException 발생
     ↓
전체 Chunk 롤백 → 아이템을 1건씩 개별 재처리
     ↓
[개별 재처리] → ValidationException 발생
     ↓
Skip 대상 예외인가? → No → Step FAILED
     ↓ Yes
skipLimit 초과? → Yes → SkipLimitExceededException → Step FAILED
     ↓ No
SkipListener.onSkipInProcess(item, t) 호출 → customer_err INSERT
     ↓
processSkipCount++ → 다음 아이템 처리
```

### Retry 흐름 (Writer 레벨)
```
[Write Chunk] → DeadlockLoserDataAccessException 발생
     ↓
Retry 대상 예외인가? → No → Rollback
     ↓ Yes
retryLimit 초과? → Yes → Rollback → Skip 시도
     ↓ No
Chunk 전체 재시도 (Writer는 Chunk 단위)
```

---

## 변경된 파일

| 파일 | 종류 | 변경 내용 |
|------|------|----------|
| `RestartableItemProcessor.java` | 수정 | `validate()` 메서드 추가, `process()`에서 호출 |
| `CustomerImportJobConfig.java` | 수정 | csvToStagingStep에 faultTolerant/skip/retry/skipListener 설정 |
| `CustomerImportJobFlowTest.java` | 수정 | 시나리오 2, 5 기댓값 변경 (Skip 반영) |
| `ErrorIsolationSkipListener.java` | **신규** | SkipListener 구현, customer_err 격리 |
| `FaultToleranceTest.java` | **신규** | 내결함성 테스트 4개 시나리오 |
| `customers_dirty_20250205.csv` | **신규** | 6건 (정상 3, 오류 3) |
| `customers_very_dirty_20250205.csv` | **신규** | 15건 (정상 3, 오류 12) |
| `RetryLoggingListener.java` | **신규** | RetryListener 구현, 재시도 WARN/ERROR 로깅 |
| `RetryTest.java` | **신규** | Retry 통합 테스트 2개 시나리오 (Step 직접 빌드/실행 방식) |
| `customers_clean_6.csv` | **신규** | 6건 정상 데이터 (Retry 테스트용) |

---

## 회고

### 잘한 점


### 개선할 점


### 다음 주 준비
- Job/Step Scope 이해
- Late Binding 학습

---

## 참고 링크

### Spring 공식 문서
- [Configuring Skip Logic](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring-skip.html)
- [Configuring Retry Logic](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring-retry.html)
- [Controlling Rollback](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/controlling-rollback.html)
- [Intercepting Step Execution (Listeners)](https://docs.spring.io/spring-batch/reference/step/intercepting-execution.html)
- [SkipListener](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring-skip.html#skipListeners)
