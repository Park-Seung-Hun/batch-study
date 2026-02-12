# Week 06: 파라미터 + Scope (Late Binding)

> 작성일: 2025-02-12
> 상태: ✅ 완료

---

## 이번 주 목표

- [x] @JobScope vs @StepScope 차이 이해 및 적용
- [x] SpEL Elvis operator(`?:`)로 기본값 정의
- [x] identifying vs non-identifying 파라미터 동작 차이 검증
- [x] JobParametersValidator 구현
- [x] 하드코딩된 chunkSize, skipLimit, retryLimit을 동적 파라미터로 전환

---

## 핵심 개념 요약

### @JobScope vs @StepScope

| Scope | 생성 시점 | 적용 대상 | 용도 |
|-------|----------|-----------|------|
| singleton | 애플리케이션 시작 시 | 일반 Bean | JobParameters 접근 불가 |
| @JobScope | Job 실행 시작 시 | **Step 빈** | Step 구조(chunkSize 등)를 동적으로 결정 |
| @StepScope | Step 실행 시작 시 | Reader/Processor/Writer/Tasklet | Step 내부 컴포넌트에 JobParameter 주입 |

**핵심 판단 기준**: Step의 **구조**(트랜잭션 경계)를 결정하는 값이면 `@JobScope`, Step **내부** 컴포넌트에 주입하는 값이면 `@StepScope`.

> **Q. Reader가 `jobParameters['inputFile']`을 쓰는데 왜 @JobScope가 아닌 @StepScope인가?**
>
> `@JobScope`와 `@StepScope` **모두** `jobParameters`에 접근 가능하다. Scope 선택은 파라미터 출처가 아니라 **Bean의 생명주기**로 결정한다.
>
> | 기준 | @JobScope | @StepScope |
> |------|-----------|------------|
> | 인스턴스 수 | Job당 1개 | Step 실행당 1개 |
> | 재시작 시 | 같은 인스턴스 재사용 | **새 인스턴스** 생성 → `ItemStream.open()`에서 상태 복원 |
>
> Reader는 재시작 시 **새 인스턴스가 생성되어야** 이전 ExecutionContext(read.count 등)를 깨끗하게 복원할 수 있다.
> `@JobScope`였다면 같은 Job 내에서 Reader가 하나뿐이므로, restart 시 이전 실행의 내부 상태(커서 위치)가 꼬일 수 있다.
> 따라서 Reader/Processor/Writer는 항상 `@StepScope`가 올바른 선택이다.

### SpEL Elvis Operator (`?:`)

```java
@Value("#{jobParameters['chunkSize'] ?: 100}") int chunkSize
```

- `chunkSize` 파라미터가 없으면 기본값 `100` 사용
- 기존 테스트 코드 변경 없이 호환성 유지

### Identifying vs Non-identifying Parameters

| 구분 | Identifying (`true`) | Non-identifying (`false`) |
|------|---------------------|--------------------------|
| JobInstance 구분 | O | X |
| 용도 | 실행 식별 (runDate, inputFile) | 동작 제어 (chunkSize, skipLimit) |
| 재실행 | 값 변경 시 새 JobInstance | 값 변경해도 같은 JobInstance |

---

## 구현 상세

### 1. JobParametersValidator 구현

**파일**: `src/main/java/com/test/batchstudy/validator/CustomerImportJobParametersValidator.java` (신규)

```java
@Component
public class CustomerImportJobParametersValidator implements JobParametersValidator {

    @Override
    public void validate(JobParameters parameters) throws InvalidJobParametersException {
        String inputFile = parameters.getString("inputFile");
        if (inputFile == null || inputFile.isBlank()) {
            throw new InvalidJobParametersException("inputFile is null or blank");
        }

        String runDate = parameters.getString("runDate");
        if (runDate == null || runDate.isBlank()) {
            throw new InvalidJobParametersException("runDate is null or blank");
        }

        try {
            LocalDate.parse(runDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (DateTimeParseException e) {
            throw new InvalidJobParametersException("runDate pattern is yyyy-mm-dd");
        }
    }
}
```

**설계 판단**: `DefaultJobParametersValidator`는 파라미터 이름 존재만 검증하지만, 커스텀 Validator는 **값의 형식**(날짜 패턴)까지 검증 가능. `DateTimeParseException`을 `InvalidJobParametersException`으로 번역하여 Spring Batch가 이해하는 예외로 변환.

**동작 특성**: Validator 예외는 `AbstractJob.execute()` 내에서 발생하지만 호출자에게 **직접 전파**됨. Step이 시작되기 전에 fail-fast.

### 2. @JobScope + 동적 파라미터 적용

**파일**: `src/main/java/com/test/batchstudy/config/CustomerImportJobConfig.java` (수정)

#### Before (Week 05)

```java
private static final int CHUNK_SIZE = 100;

@Bean
public Step csvToStagingStep(...) {
    return new StepBuilder("csvToStagingStep", jobRepository)
            .<CustomerCsv, CustomerStg>chunk(CHUNK_SIZE)     // 하드코딩
            ...
            .skipLimit(10)                                    // 하드코딩
            .retryLimit(3)                                    // 하드코딩
            .build();
}
```

#### After (Week 06)

```java
// CHUNK_SIZE 상수 제거

@Bean
@JobScope  // Job 실행 시점에 Step 빈 생성 → chunk() 인자를 Late Binding
public Step csvToStagingStep(JobRepository jobRepository,
                             ...,
                             @Value("#{jobParameters['chunkSize'] ?: 100}") int chunkSize,
                             @Value("#{jobParameters['skipLimit'] ?: 10}") int skipLimit,
                             @Value("#{jobParameters['retryLimit'] ?: 3}") int retryLimit) {
    return new StepBuilder("csvToStagingStep", jobRepository)
            .<CustomerCsv, CustomerStg>chunk(chunkSize)
            ...
            .skipLimit(skipLimit)
            .skipListener(errorIsolationSkipListener)
            .retry(DeadlockLoserDataAccessException.class)
            .retryLimit(retryLimit)
            .retryListener(retryLoggingListener)
            .stream(customerCsvReader)
            .stream(restartableItemProcessor)
            .build();
}
```

**왜 @StepScope가 아닌 @JobScope인가?**: `chunk(chunkSize)`는 Step의 트랜잭션 경계를 결정하는 값이다. Step 빈 자체의 구조가 이 값에 의존하므로, Step 빈이 **Job 실행 시점**에 생성되어야 한다. Reader/Processor 같은 Step 내부 컴포넌트는 `@StepScope`로 충분하지만, Step 빈 자체는 `@JobScope`가 필요하다.

#### Validator 등록

```java
private final CustomerImportJobParametersValidator validator;

@Bean
public Job customerImportJob(JobRepository jobRepository, ...) {
    return new JobBuilder("customerImportJob", jobRepository)
            .validator(validator)  // Job 실행 전 파라미터 검증
            .start(csvToStagingStep)
            ...
}
```

#### stagingToTargetStep에도 @JobScope 적용

```java
@Bean
@JobScope
public Step stagingToTargetStep(JobRepository jobRepository,
                                ...,
                                @Value("#{jobParameters['chunkSize'] ?: 100}") int chunkSize) {
    return new StepBuilder("stagingToTargetStep", jobRepository)
            .<CustomerStg, Customer>chunk(chunkSize)
            ...
}
```

---

## 데이터 흐름 추적

### 동적 chunkSize=2, 100건 데이터

```
JobParameters: inputFile=customers_20250205.csv, runDate=2025-06-01, chunkSize=2

@JobScope → csvToStagingStep 빈 생성 (chunk=2)
  @StepScope → customerCsvReader 빈 생성 (inputFile=...)
  @StepScope → restartableItemProcessor 빈 생성 (runDate=...)

[CSV 100건]
  → chunk 1: 레코드 1~2 → commit   ─┐
  → chunk 2: 레코드 3~4 → commit    │
  → ...                              ├→ 50 commits 총
  → chunk 50: 레코드 99~100 → commit─┘

  commitCount = 50  (100건 / chunkSize 2)
```

### Validator 실패 시 흐름

```
JobParameters: runDate=20250301 (형식 오류)

JobBuilder.validator(validator)
  → validate() 호출
  → InvalidJobParametersException("runDate pattern is yyyy-mm-dd")
  → Step 실행 없이 즉시 실패 (fail-fast)
```

---

## Scope 계층 구조

현재 프로젝트의 3단계 Late Binding:

```
Application Start
  ↓
[Singleton] DataSource, ErrorIsolationSkipListener, RetryLoggingListener, Validator
  ↓
Job 실행 시점
  ↓
[@JobScope] csvToStagingStep(chunkSize, skipLimit, retryLimit)
[@JobScope] stagingToTargetStep(chunkSize)
  ↓
Step 실행 시점
  ↓
[@StepScope] customerCsvReader(inputFile)
[@StepScope] restartableItemProcessor(runDate, failAt)
[@StepScope] stagingReader(runDate)
[@StepScope] validateTasklet(runDate)
[@StepScope] statsTasklet(runDate)
[@StepScope] errorIsolateTasklet(runDate)
```

---

## 테스트 시나리오

| # | 시나리오 | 입력 | 기대 결과 | 핵심 검증 |
|---|----------|------|-----------|----------|
| 1 | 동적 chunkSize | 100건 + chunkSize=2 | COMPLETED | commitCount=50 |
| 2 | 동적 skipLimit | dirty 6건 + skipLimit=2 | FAILED | 오류3 > limit2, csvToStagingStep FAILED |
| 3 | Non-identifying 재사용 | 같은 identifying + 다른 chunkSize | 예외 | "complete" 메시지 |
| 4 | Validator — inputFile 누락 | runDate만 전달 | 예외 | "inputFile" 메시지 |
| 5 | Validator — runDate 형식 오류 | runDate=20250301 | 예외 | "runDate" 메시지 |
| 6 | 기본값 동작 | 필수만 전달 | COMPLETED | commitCount=1 (기본 chunkSize=100) |

### 테스트 데이터

| 파일 | 레코드 수 | 용도 |
|------|-----------|------|
| `customers_20250205.csv` | 100건 (정상) | 시나리오 1, 3, 6 |
| `customers_dirty_20250205.csv` | 6건 (정상 3 + 오류 3) | 시나리오 2 |

### 시나리오 3 상세: Non-identifying 재사용

```java
// 1차 실행: COMPLETED
params1 = {inputFile="...", runDate="2025-06-03"}  // identifying만
→ JobInstance #1 생성 → COMPLETED

// 2차 실행: 같은 identifying + 다른 chunkSize(non-identifying)
params2 = {inputFile="...", runDate="2025-06-03", chunkSize=50L(non-identifying)}
→ 같은 JobInstance #1 → 이미 COMPLETED → 예외!
```

**핵심**: `addLong("chunkSize", 50L, false)` — 세 번째 인자 `false`가 non-identifying. 값이 달라도 JobInstance 해시에 포함되지 않으므로 같은 JobInstance로 인식.

---

## 트러블슈팅 로그

### 이슈 1: Validator 예외 전파 방식

- **현상**: 시나리오 4, 5에서 `JobExecution.getStatus() == FAILED`로 검증했더니 `InvalidJobParametersException`이 `startJob()`에서 직접 던져짐
- **원인**: Spring Batch 6.x의 `AbstractJob.execute()`에서 Validator 예외가 `catch (Exception t)` 블록에 잡히지만, 실제로는 호출 스택 상위로 전파됨
- **해결**: `assertThatThrownBy(() -> startJob(params)).hasMessageContaining("inputFile")` 방식으로 변경

---

## 변경된 파일

| 파일 | 작업 | 내용 |
|------|------|------|
| `validator/CustomerImportJobParametersValidator.java` | **신규** | inputFile 필수 + runDate 필수/형식 검증 |
| `config/CustomerImportJobConfig.java` | **수정** | CHUNK_SIZE 상수 제거, @JobScope 적용, Validator 등록, 동적 파라미터 |
| `config/ParamsScopeTest.java` (test) | **신규** | 6개 시나리오 (동적 파라미터, Validator, Non-identifying) |

---

## 참고 링크

### Spring 공식 문서
- [Late Binding of JobParameters and Execution Context](https://docs.spring.io/spring-batch/reference/step/late-binding.html)
- [Step Scope](https://docs.spring.io/spring-batch/reference/step/late-binding.html#step-scope)
- [Job Scope](https://docs.spring.io/spring-batch/reference/step/late-binding.html#job-scope)
- [JobParameters](https://docs.spring.io/spring-batch/reference/job/configuring.html#jobparameters)
- [Validating Job Parameters](https://docs.spring.io/spring-batch/reference/job/configuring.html#jobparametersvalidator)
