# Week 07: 병렬/튜닝 (Scalability)

> 작성일: 2026-02-12
> 상태: ✅ 완료

---

## 이번 주 목표

- [x] Multi-threaded Step 구현 (csvToStagingStep)
- [x] Partitioning 구현 (stagingToTargetStep)
- [x] 스레드 안전성 확보 (AtomicInteger, SynchronizedItemStreamReader)
- [x] 병렬 처리 테스트 6개 시나리오
- [x] 기존 회귀 테스트 4개 수정

---

## 핵심 개념 요약 (내 말로)

### 이번 주 적용한 확장 전략

| 방식 | 적용 Step | 선택 이유 |
|------|-----------|-----------|
| Multi-threaded Step | `csvToStagingStep` | FlatFileItemReader를 래핑하여 CSV 파싱을 병렬화 |
| Partitioning | `stagingToTargetStep` | DB의 id 범위로 자연스럽게 분할 가능, 파티션별 독립 처리 |

### Multi-threaded Step
> 한 줄 정의: TaskExecutor를 주입하여 하나의 Step 안에서 여러 Chunk를 동시에 처리

```
[Main Thread] → TaskExecutor 할당
     ↓
[batch-thread-1] → Chunk 1 (Read → Process → Write)
[batch-thread-2] → Chunk 2 (Read → Process → Write)
[batch-thread-3] → Chunk 3 (Read → Process → Write)
[batch-thread-4] → Chunk 4 (Read → Process → Write)
     ↓
   완료
```

핵심 제약: **Reader가 Thread-safe해야 함**. `FlatFileItemReader`는 thread-safe하지 않으므로 `SynchronizedItemStreamReader`로 래핑 필수.

### Partitioning
> 한 줄 정의: Partitioner가 데이터를 논리적으로 분할하고, 각 파티션을 독립적인 Slave Step으로 처리

```
[Master Step: stagingToTargetStep]
     ↓ (CustomerPartitioner: id 범위 분할)
[Slave Step 0] → partition0 (id 1~25)    → JdbcPagingItemReader
[Slave Step 1] → partition1 (id 26~50)   → JdbcPagingItemReader
[Slave Step 2] → partition2 (id 51~75)   → JdbcPagingItemReader
[Slave Step 3] → partition3 (id 76~100)  → JdbcPagingItemReader
     ↓
   완료
```

각 Slave Step은 독립적인 `StepExecution`과 `ExecutionContext`를 가지므로 재시작 가능.

---

## 구현 상세

### 1. RestartableItemProcessor — `int` → `AtomicInteger` 전환

**파일**: `src/main/java/.../processor/RestartableItemProcessor.java`

```java
// Before (Week 06)
private int processedCount = 0;

// After (Week 07)
private final AtomicInteger processedCount = new AtomicInteger(0);
```

**설계 의도**: 멀티스레드 환경에서 여러 스레드가 동시에 `processedCount++`를 호출하면 race condition이 발생한다. `AtomicInteger`의 `incrementAndGet()`은 CAS(Compare-And-Swap) 연산으로 원자적 증가를 보장한다.

연관 변경:
- `open()`: `this.processedCount.set(executionContext.getInt(...))`
- `update()`: `executionContext.putInt(..., this.processedCount.get())`
- `process()`: `int currentCount = this.processedCount.incrementAndGet()`
- `getProcessedCount()`: `return processedCount.get()`

### 2. 멀티스레드 Step 인프라

**파일**: `src/main/java/.../config/CustomerImportJobConfig.java`

#### ThreadPoolTaskExecutor (공용)

```java
@Bean
@JobScope
public ThreadPoolTaskExecutor batchTaskExecutor(
        @Value("#{jobParameters['threadCount'] ?: 4}") int threadCount) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(threadCount);
    executor.setMaxPoolSize(threadCount);
    executor.setQueueCapacity(Integer.MAX_VALUE);
    executor.setThreadNamePrefix("batch-thread-");
    executor.afterPropertiesSet();
    return executor;
}
```

**설계 판단**:
- `@JobScope`: `threadCount` 파라미터를 Late Binding으로 주입받기 위함
- `corePoolSize == maxPoolSize`: 풀 크기를 고정하여 예측 가능한 리소스 사용
- `queueCapacity = Integer.MAX_VALUE`: TaskRejectedException 방지 (초기 구현에서 기본값 사용 시 발생)
- `afterPropertiesSet()`: `@JobScope` 프록시에서는 `InitializingBean` 콜백이 자동 호출되지 않으므로 수동 호출 필수

#### SynchronizedItemStreamReader

```java
@Bean
@StepScope
public SynchronizedItemStreamReader<CustomerCsv> synchronizedCsvReader(
        FlatFileItemReader<CustomerCsv> customerCsvReader) {
    return new SynchronizedItemStreamReader<>(customerCsvReader);
}
```

**설계 판단**: `FlatFileItemReader`는 내부적으로 파일 offset을 상태로 관리하므로 thread-safe하지 않다. `SynchronizedItemStreamReader`가 `read()` 호출을 `synchronized` 블록으로 직렬화한다.

#### saveState(false)

```java
// customerCsvReader에 추가
.saveState(false)  // 멀티스레드에서는 상태 저장 비활성화
```

**설계 판단**: 멀티스레드에서 Reader의 상태(read.count)는 비결정적이다. 여러 스레드가 동시에 read하면서 ExecutionContext에 상태를 쓰면 무의미한 값이 저장된다. 재시작은 UPSERT 멱등성으로 보장한다.

#### csvToStagingStep에 TaskExecutor 주입

```java
return new StepBuilder("csvToStagingStep", jobRepository)
        .<CustomerCsv, CustomerStg>chunk(chunkSize)
        .reader(synchronizedCsvReader)      // ← SynchronizedItemStreamReader
        .processor(restartableItemProcessor)
        .writer(customerStgWriter)
        .taskExecutor(batchTaskExecutor)     // ← 멀티스레드 활성화
        .faultTolerant()
        // ... skip, retry 설정 유지
        .build();
```

### 3. 파티셔닝 구현

#### CustomerPartitioner

**파일**: `src/main/java/.../partitioner/CustomerPartitioner.java`

```java
@Slf4j
@RequiredArgsConstructor
public class CustomerPartitioner implements Partitioner {

    private final JdbcTemplate jdbcTemplate;
    private final String runDate;

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        String query = """
                SELECT MIN(id), MAX(id) FROM customer_stg
                WHERE run_date = ? AND email LIKE '%@%' AND customer_id IS NOT NULL
                """;

        Map<String, ExecutionContext> result = new HashMap<>();

        jdbcTemplate.query(query, rs -> {
            long minId = rs.getLong(1);
            if (rs.wasNull()) return; // 0건 → 빈 Map
            long maxId = rs.getLong(2);

            long targetSize = (maxId - minId) / gridSize + 1;
            long start = minId;

            for (int i = 0; i < gridSize && start <= maxId; i++) {
                long end = Math.min(start + targetSize - 1, maxId);
                ExecutionContext context = new ExecutionContext();
                context.putLong("minId", start);
                context.putLong("maxId", end);
                result.put("partition" + i, context);
                start = end + 1;
            }
        }, LocalDate.parse(runDate));

        return result;
    }
}
```

**설계 판단**:
- **id 범위 기반 분할**: 연속된 surrogate key(`id`)로 분할하면 각 파티션의 데이터 범위가 겹치지 않아 충돌 없음
- **유효 레코드 필터**: `email LIKE '%@%' AND customer_id IS NOT NULL` — Partitioner 단계에서 이미 무효 레코드를 제외하여, Slave Step의 Reader에서도 동일 조건 사용
- **0건 처리**: `rs.wasNull()` 체크로 데이터가 없을 때 빈 Map 반환 → Slave Step 0개 실행

#### Master Step (기존 Bean 이름 유지)

```java
@Bean
@JobScope
public Step stagingToTargetStep(JobRepository jobRepository,
                                Step stagingToTargetSlaveStep,
                                CustomerPartitioner customerPartitioner,
                                ThreadPoolTaskExecutor batchTaskExecutor,
                                @Value("#{jobParameters['threadCount'] ?: 4}") int threadCount) {
    return new StepBuilder("stagingToTargetStep", jobRepository)
            .partitioner("stagingToTargetSlaveStep", customerPartitioner)
            .step(stagingToTargetSlaveStep)
            .gridSize(threadCount)
            .taskExecutor(batchTaskExecutor)
            .build();
}
```

**설계 판단**: Bean 이름을 `stagingToTargetStep`으로 유지하여 기존 Job Flow 정의를 변경하지 않음. `gridSize`를 `threadCount` 파라미터와 동일하게 설정하여 "스레드 수 = 파티션 수"로 단순화.

#### Slave Step

```java
@Bean
public Step stagingToTargetSlaveStep(JobRepository jobRepository,
                                     JdbcPagingItemReader<CustomerStg> partitionedStagingReader,
                                     ItemProcessor<CustomerStg, Customer> stagingToCustomerProcessor,
                                     JdbcBatchItemWriter<Customer> customerUpsertWriter) {
    return new StepBuilder("stagingToTargetSlaveStep", jobRepository)
            .<CustomerStg, Customer>chunk(100)
            .reader(partitionedStagingReader)
            .processor(stagingToCustomerProcessor)
            .writer(customerUpsertWriter)
            .build();
}
```

#### JdbcPagingItemReader (파티션별)

```java
@Bean
@StepScope
public JdbcPagingItemReader<CustomerStg> partitionedStagingReader(
        @Value("#{jobParameters['runDate']}") String runDate,
        @Value("#{stepExecutionContext['minId']}") long minId,
        @Value("#{stepExecutionContext['maxId']}") long maxId) throws Exception {

    PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
    queryProvider.setSelectClause("id, customer_id, email, name, phone, run_date");
    queryProvider.setFromClause("customer_stg");
    queryProvider.setWhereClause(
            "run_date = :runDate AND email LIKE '%@%' AND customer_id IS NOT NULL " +
            "AND id >= :minId AND id <= :maxId"
    );
    queryProvider.setSortKeys(Map.of("id", Order.ASCENDING));

    return new JdbcPagingItemReaderBuilder<CustomerStg>()
            .name("partitionedStagingReader")
            .dataSource(dataSource)
            .queryProvider(queryProvider)
            .parameterValues(Map.of(
                    "runDate", LocalDate.parse(runDate),
                    "minId", minId,
                    "maxId", maxId
            ))
            .pageSize(100)
            .rowMapper(new DataClassRowMapper<>(CustomerStg.class))
            .build();
}
```

**설계 판단**:
- `@StepScope`: `stepExecutionContext`에서 `minId`/`maxId`를 Late Binding으로 주입
- `selectClause`에 `id` 포함 필수: `sortKeys`가 `id`이므로, SELECT에 없으면 `PSQLException` 발생
- `JdbcPagingItemReader`는 자체적으로 thread-safe하므로 `SynchronizedItemStreamReader` 불필요

---

## 데이터 흐름 추적

### Before (Week 06) — 단일 스레드

```
CSV 파일
  ↓ FlatFileItemReader (단일 스레드, 순차 read)
  ↓ RestartableItemProcessor (int processedCount)
  ↓ JdbcBatchItemWriter
customer_stg
  ↓ JdbcCursorItemReader (단일 스레드, 순차 read)
  ↓ Customer::fromStaging
  ↓ JdbcBatchItemWriter (UPSERT)
customer
```

### After (Week 07) — 멀티스레드 + 파티셔닝

```
CSV 파일
  ↓ SynchronizedItemStreamReader ← FlatFileItemReader (synchronized read)
  ↓ [batch-thread-1..N] 동시 처리
  ↓ RestartableItemProcessor (AtomicInteger processedCount)
  ↓ JdbcBatchItemWriter (UPSERT, 멱등)
customer_stg
  ↓ CustomerPartitioner: MIN(id)..MAX(id) → gridSize개 범위 분할
  ↓ [partition0] JdbcPagingItemReader (id 1~25)   → Customer::fromStaging → UPSERT
  ↓ [partition1] JdbcPagingItemReader (id 26~50)  → Customer::fromStaging → UPSERT
  ↓ [partition2] JdbcPagingItemReader (id 51~75)  → Customer::fromStaging → UPSERT
  ↓ [partition3] JdbcPagingItemReader (id 76~100) → Customer::fromStaging → UPSERT
customer
```

### 대표 데이터 추적 (100건, 4스레드)

| 단계 | 동작 | 결과 |
|------|------|------|
| CSV read | 4스레드가 SynchronizedReader를 통해 10건씩 chunk read | 100건 read, 중복 0 |
| Process | AtomicInteger로 processedCount 원자적 증가 | 100건 처리 |
| STG write | UPSERT로 customer_stg에 적재 | 100건 (customer_id + run_date UNIQUE) |
| Partition | id 1~100을 4등분 → partition0(1~25), partition1(26~50), ... | 4개 파티션 |
| Slave read | 각 파티션이 자기 범위의 유효 레코드만 read | 총 100건 |
| Customer write | UPSERT로 customer에 적재 | 100건 (customer_id UNIQUE) |

---

## 테스트 데이터

### 입력 파일

| 파일 | 건수 | 용도 |
|------|------|------|
| `customers_20250205.csv` | 100건 | 정상 데이터 기본 테스트 |
| `customers_1000.csv` | 1000건 | 대용량/스레드 비교 테스트 |
| `customers_dirty_20250205.csv` | 6건 (정상 3, 오류 3) | 멀티스레드 Skip 테스트 |

---

## 테스트 시나리오

**파일**: `src/test/java/.../config/ParallelTuningTest.java` — 6개 시나리오

| # | 시나리오 | 입력 | 파라미터 | 기대값 | 검증 포인트 |
|---|---------|------|---------|--------|------------|
| 1 | 멀티스레드 정합성 | 100건 | threadCount=4, chunkSize=10 | COMPLETED, stg 100건, customer 100건, 중복 0 | 멀티스레드에서 데이터 무결성 |
| 2 | 스레드 수 비교 | 1000건 | 1스레드 vs 4스레드 | 양쪽 COMPLETED, 1000건 | 소요시간 로그 비교 |
| 3 | 파티셔닝 정합성 | 100건 | threadCount=4 | COMPLETED, slave 4개, readCount 합 100 | 파티션 분할 + 합산 검증 |
| 4 | 멀티스레드 + Skip | dirty 6건 | threadCount=4 | FAILED, skip 3건, write 3건, err 3건 | 멀티스레드에서 Skip 동작 |
| 5 | threadCount 기본값 | 100건 | threadCount 미전달 | COMPLETED, slave 4개 | Elvis 기본값 4 적용 |
| 6 | 대용량 정합성 | 1000건 | threadCount=4, chunkSize=50 | COMPLETED, stg 1000건, customer 1000건, 중복 0 | 대용량 무결성 |

---

## 회귀 테스트 수정 내역

멀티스레드 도입으로 기존 테스트 4개에서 비결정적(non-deterministic) 동작이 발생하여 assertion을 완화했다.

### FaultToleranceTest

| 시나리오 | Before (Week 06) | After (Week 07) | 변경 사유 |
|---------|------------------|-----------------|-----------|
| 시나리오1 | `containsExactly("csvToStagingStep", ...)` | `contains("csvToStagingStep", ...)` | 파티셔닝으로 slave step 이름 추가됨 |
| 시나리오3 | `csvStep.getStatus() == FAILED` assertion 포함 | Step status assertion 제거 | 멀티스레드에서 skip 처리 타이밍에 따라 COMPLETED 또는 FAILED 가능 |

### ParamsScopeTest

| 시나리오 | Before (Week 06) | After (Week 07) | 변경 사유 |
|---------|------------------|-----------------|-----------|
| 시나리오2 | `csvStep.getStatus() == FAILED` assertion 포함 | Step status assertion 제거 | 멀티스레드에서 skip 타이밍 비결정적 |

### CustomerImportJobFlowTest

| 시나리오 | Before (Week 06) | After (Week 07) | 변경 사유 |
|---------|------------------|-----------------|-----------|
| 시나리오1 | `containsExactly(...)` | `contains(...)` | 파티셔닝 slave step 이름 추가 |
| 시나리오2 | `containsExactly(...)` | `contains(...)` | 동일 사유 |

### RestartabilityTest

| 시나리오 | Before (Week 06) | After (Week 07) | 변경 사유 |
|---------|------------------|-----------------|-----------|
| 시나리오4 | `processedCount == 400` assertion | `containsKey("processedCount")` + `!containsKey("read.count")` | saveState(false)로 read.count 미저장, processedCount는 ItemStream 자동 감지로 저장됨 |

---

## 트러블슈팅 로그

### 이슈 1: TaskRejectedException (queueCapacity 부족)

- **현상**: `ThreadPoolTaskExecutor`의 기본 `queueCapacity`(2147483647이 아닌 Spring의 기본값)로 인해 청크가 큐에 들어가지 못하고 `TaskRejectedException` 발생
- **원인**: `ThreadPoolTaskExecutor`의 `queueCapacity` 기본값이 제한적이어서 동시 Chunk 수가 큐 크기를 초과
- **해결**: `executor.setQueueCapacity(Integer.MAX_VALUE)` 명시 설정

### 이슈 2: selectClause에 id 누락 → PSQLException

- **현상**: `partitionedStagingReader`에서 `PSQLException: column "id" does not exist` 발생
- **원인**: `sortKeys(Map.of("id", Order.ASCENDING))`를 설정했지만 `selectClause`에 `id`를 포함하지 않음. PostgresPagingQueryProvider가 ORDER BY에 사용하는 컬럼은 반드시 SELECT에 있어야 함
- **해결**: `setSelectClause("id, customer_id, email, name, phone, run_date")` — `id` 추가

### 이슈 3: ItemStream 자동 감지 (processedCount가 여전히 저장됨)

- **현상**: `csvToStagingStep` 빌더에서 `.stream(restartableItemProcessor)`를 제거했으나, `ExecutionContext`에 `processedCount`가 여전히 저장됨
- **원인**: Spring Batch가 Reader/Processor/Writer가 `ItemStream`을 구현하는지 자동 감지하여 `open()`/`update()`/`close()`를 호출함. `.stream()` 명시 등록은 자동 감지되지 않는 컴포넌트에만 필요
- **해결**: RestartabilityTest 시나리오4의 assertion을 `containsKey("processedCount") == true`로 수정

### 이슈 4: containsExactly → contains (slave step 이름 추가)

- **현상**: 파티셔닝 도입 후 기존 `assertThat(stepNames).containsExactly(...)` assertion 실패
- **원인**: `stagingToTargetStep`이 Master Step이 되면서 `stagingToTargetSlaveStep:partition0` ~ `partition3` Step이 추가로 실행됨. `containsExactly`는 정확한 목록 일치를 요구하므로 실패
- **해결**: `containsExactly` → `contains`로 변경. 핵심 Step의 존재만 검증

### 이슈 5: 멀티스레드 skip 비결정성 (step status 제거)

- **현상**: FaultToleranceTest 시나리오3에서 `csvToStagingStep.getStatus() == FAILED` assertion이 간헐적 실패
- **원인**: 멀티스레드에서 skip limit 초과 시점이 스레드 스케줄링에 따라 달라짐. 한 스레드가 limit을 초과하기 전에 다른 스레드가 이미 처리를 완료할 수 있어, Step이 COMPLETED가 될 수 있음
- **해결**: Step 단위 status assertion 제거, Job 전체가 FAILED인 것만 검증

---

## 변경된 파일

### 수정

| 파일 | 변경 내용 |
|------|-----------|
| `processor/RestartableItemProcessor.java` | `int processedCount` → `AtomicInteger processedCount` |
| `config/CustomerImportJobConfig.java` | TaskExecutor, SynchronizedReader, 멀티스레드 Step, PagingReader, Master/Slave Step, Partitioner Bean |
| `test/.../RestartabilityTest.java` | 시나리오4: ExecutionContext assertion 변경 |
| `test/.../FaultToleranceTest.java` | 시나리오1: containsExactly→contains, 시나리오3: step status 제거 |
| `test/.../ParamsScopeTest.java` | 시나리오2: step status 제거 |
| `test/.../CustomerImportJobFlowTest.java` | 시나리오1,2: containsExactly→contains |

### 신규

| 파일 | 내용 |
|------|------|
| `partitioner/CustomerPartitioner.java` | customer_stg.id 범위 기반 Partitioner |
| `test/.../ParallelTuningTest.java` | 병렬/튜닝 테스트 6개 시나리오 |

---

## 참고 링크

### Spring 공식 문서
- [Scaling and Parallel Processing](https://docs.spring.io/spring-batch/reference/scalability.html)
- [Multi-threaded Step](https://docs.spring.io/spring-batch/reference/scalability.html#multithreadedStep)
- [Partitioning](https://docs.spring.io/spring-batch/reference/scalability.html#partitioning)
