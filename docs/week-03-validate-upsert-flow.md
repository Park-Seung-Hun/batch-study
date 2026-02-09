# Week 03: 검증 + 업서트 + Flow

> 작성일: 2025-02-09
> 상태: ✅ 완료

---

## 이번 주 목표

- [x] 멀티 Step Job 구성 이해
- [x] Tasklet으로 검증/집계 로직 구현
- [x] 업서트(UPSERT) 패턴 구현
- [x] ExitStatus 기반 Step Flow 분기 구현
- [x] 조건부 분기 (on/to/from) 사용

---

## 핵심 개념 요약 (내 말로)

### Tasklet vs Chunk
> 한 줄 정의: Tasklet은 단일 작업, Chunk는 반복 처리

| 구분 | Tasklet | Chunk |
|------|---------|-------|
| 용도 | 단일 작업 (검증, 집계, 클린업) | 대량 데이터 반복 처리 |
| 반환 | `RepeatStatus.FINISHED` / `CONTINUABLE` | 자동 (데이터 소진까지) |
| 트랜잭션 | 전체가 하나의 트랜잭션 | Chunk 단위 트랜잭션 |

### Step Flow
> 한 줄 정의: Step 간 실행 순서와 조건부 분기를 정의하는 구성

```java
.start(stepA)
    .on("COMPLETED").to(stepB)
    .from(stepA).on("FAILED").to(stepC)
.end()
```

### ExitStatus 기반 분기
> 한 줄 정의: Step의 ExitStatus를 기준으로 다음 Step을 결정하는 Flow 패턴

`contribution.setExitStatus(new ExitStatus("INVALID"))`로 커스텀 상태 설정 후,
`.on("INVALID").to(nextStep)` 패턴으로 분기.

> 💡 **참고**: `JobExecutionDecider`도 분기에 사용 가능하지만,
> 본 실습에서는 더 간단한 ExitStatus 기반 분기를 사용했습니다.

### UPSERT 패턴
> 한 줄 정의: 존재하면 UPDATE, 없으면 INSERT

PostgreSQL에서는 `INSERT ... ON CONFLICT ... DO UPDATE` 사용.

---

## 실습 시나리오

### 입력
- CSV 파일 (`input/customers_20250205.csv`)
- JobParameters: `inputFile`, `runDate`

### Job Flow 시각화

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           customerImportJob                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────┐                                                       │
│  │ csvToStagingStep │  CSV → customer_stg (Chunk 처리)                      │
│  │     (Chunk)      │                                                       │
│  └────────┬─────────┘                                                       │
│           │                                                                  │
│           ▼                                                                  │
│  ┌──────────────────┐                                                       │
│  │   validateStep   │  스테이징 데이터 검증                                   │
│  │    (Tasklet)     │  - 이메일 형식 (@)                                     │
│  └────────┬─────────┘  - customer_id NULL                                   │
│           │            - customer_id 중복                                    │
│           │                                                                  │
│     ┌─────┴─────┐                                                           │
│     │ ExitStatus│                                                           │
│     └─────┬─────┘                                                           │
│           │                                                                  │
│   ┌───────┴───────┐                                                         │
│   │               │                                                         │
│   ▼               ▼                                                         │
│ "COMPLETED"    "INVALID"                                                    │
│   │               │                                                         │
│   ▼               ▼                                                         │
│ ┌─────────────┐ ┌─────────────────┐                                         │
│ │stagingTo    │ │errorIsolateStep │  오류 레코드 → customer_err             │
│ │TargetStep   │ │    (Tasklet)    │                                         │
│ │  (Chunk)    │ └────────┬────────┘                                         │
│ └──────┬──────┘          │                                                  │
│        │                 │                                                  │
│        └────────┬────────┘                                                  │
│                 │  양쪽 경로 합류                                            │
│                 ▼                                                           │
│        ┌──────────────┐                                                     │
│        │   statsStep   │  집계 기록 + ExitStatus 결정                        │
│        │   (Tasklet)   │                                                    │
│        └───────┬───────┘                                                    │
│                │                                                            │
│          ┌─────┴─────┐                                                      │
│          │ ExitStatus│                                                      │
│          └─────┬─────┘                                                      │
│          ┌─────┴─────┐                                                      │
│          │           │                                                      │
│          ▼           ▼                                                      │
│    "COMPLETED"   "FAILED"                                                   │
│          │           │                                                      │
│          ▼           ▼                                                      │
│   ╔═══════════╗ ╔═══════════╗                                               │
│   ║ COMPLETED ║ ║  FAILED   ║                                               │
│   ╚═══════════╝ ╚═══════════╝                                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 데이터 흐름

```
┌─────────┐    ┌─────────────┐    ┌──────────────────────┐    ┌──────────┐
│  CSV    │───▶│customer_stg │───▶│  validateStep 검증   │───▶│ customer │
│  파일   │    │ (스테이징)   │    │                      │    │ (타깃)   │
└─────────┘    └─────────────┘    └──────────┬───────────┘    └──────────┘
                                             │
                                    오류 시  │
                                             ▼
                                  ┌──────────────────┐
                                  │  customer_err    │
                                  │ (오류 격리)       │
                                  └──────────────────┘
```

### 출력
- `customer` 테이블에 업서트 완료
- `customer_daily_stats` 테이블에 집계 기록
- (오류 시) `customer_err` 테이블에 오류 레코드 격리

---

## 예시 케이스

### Case 1: 정상 데이터 처리

**입력 CSV** (`customers_20250205.csv`)
```csv
customerId,email,name,phone
C001,kim@example.com,김철수,010-1234-5678
C002,lee@example.com,이영희,010-2345-6789
C003,park@example.com,박지민,010-3456-7890
```

**실행 흐름**
```
csvToStagingStep → validateStep [COMPLETED] → stagingToTargetStep → statsStep
```

**결과**
| 테이블 | 내용 |
|--------|------|
| `customer_stg` | 3건 INSERT |
| `customer` | 3건 UPSERT |
| `customer_err` | 0건 |
| `customer_daily_stats` | total=3, success=3, error=0 |

**Job 상태**: `COMPLETED`

---

### Case 2: 오류 데이터 포함

**입력 CSV** (`customers_invalid.csv`)
```csv
customerId,email,name,phone
C001,kim@example.com,정상데이터,010-1234-5678
C002,invalid-email,잘못된이메일,010-2345-6789
C003,park@example.com,정상데이터2,010-3456-7890
C004,lee@example.com,중복ID,010-4567-8901
C004,lee2@example.com,중복ID2,010-5678-9012
```

**실행 흐름**
```
csvToStagingStep → validateStep [INVALID] → errorIsolateStep → statsStep [FAILED]
```

**오류 감지**
| 오류 유형 | 레코드 | 사유 |
|----------|--------|------|
| 이메일 형식 | C002 | '@' 없음 |
| 중복 ID | C004 (2건) | 동일 customer_id |

**결과**
| 테이블 | 내용 |
|--------|------|
| `customer_stg` | 5건 INSERT |
| `customer` | 0건 (INVALID 경로) |
| `customer_err` | 3건 (오류 레코드) |
| `customer_daily_stats` | total=5, success=2, error=3 |

**Job 상태**: `FAILED`

---

### Case 3: UPSERT 동작 확인 (재실행)

**1차 실행**
```csv
customerId,email,name,phone
C001,kim@example.com,김철수,010-1234-5678
```

**결과**
```sql
SELECT * FROM customer WHERE customer_id = 'C001';
-- customer_id: C001
-- email: kim@example.com
-- name: 김철수
-- created_at: 2025-02-05 10:00:00
-- updated_at: 2025-02-05 10:00:00
```

**2차 실행** (이메일 변경)
```csv
customerId,email,name,phone
C001,kim_new@example.com,김철수,010-1234-5678
```

**결과**
```sql
SELECT * FROM customer WHERE customer_id = 'C001';
-- customer_id: C001
-- email: kim_new@example.com  ← 변경됨
-- name: 김철수
-- created_at: 2025-02-05 10:00:00  ← 유지됨
-- updated_at: 2025-02-05 11:00:00  ← 갱신됨
```

**핵심**: `created_at`은 최초 INSERT 시에만 설정, 이후 UPDATE에서는 `updated_at`만 갱신

---

### Case 4: Step 실행 로그 확인

**정상 케이스 로그**
```
INFO  o.s.batch.core.job.SimpleStepHandler : Executing step: [csvToStagingStep]
INFO  c.t.b.config.CustomerImportJobConfig : Creating customerCsvReader with inputFile: input/customers.csv
INFO  o.s.batch.core.step.AbstractStep     : Step: [csvToStagingStep] executed in 61ms

INFO  o.s.batch.core.job.SimpleStepHandler : Executing step: [validateStep]
INFO  c.t.batchstudy.tasklet.ValidateTasklet : === ValidateTasklet 시작: runDate=2025-02-05 ===
INFO  c.t.batchstudy.tasklet.ValidateTasklet : 검증 성공: 모든 데이터 유효
INFO  o.s.batch.core.step.AbstractStep     : Step: [validateStep] executed in 13ms

INFO  o.s.batch.core.job.SimpleStepHandler : Executing step: [stagingToTargetStep]
INFO  o.s.batch.core.step.AbstractStep     : Step: [stagingToTargetStep] executed in 30ms

INFO  o.s.batch.core.job.SimpleStepHandler : Executing step: [statsStep]
INFO  c.test.batchstudy.tasklet.StatsTasklet : 통계 저장 완료 - 전체: 100, 성공: 100, 실패: 0
```

**오류 케이스 로그**
```
INFO  o.s.batch.core.job.SimpleStepHandler : Executing step: [validateStep]
WARN  c.t.batchstudy.tasklet.ValidateTasklet : 검증 실패: 3 건의 오류 발견

INFO  o.s.batch.core.job.SimpleStepHandler : Executing step: [errorIsolateStep]
INFO  c.t.b.tasklet.ErrorIsolateTasklet    : 오류 레코드 3 건을 customer_err로 이동 완료

INFO  o.s.batch.core.job.SimpleStepHandler : Executing step: [statsStep]
INFO  c.test.batchstudy.tasklet.StatsTasklet : 통계 저장 완료 - 전체: 5, 성공: 2, 실패: 3
WARN  c.test.batchstudy.tasklet.StatsTasklet : 오류 레코드 존재 → ExitStatus.FAILED 설정
```

### 성공 기준
- [x] 스테이징 검증 통과 시 타깃 테이블 업서트 완료
- [x] 검증 실패 시 errorIsolateStep으로 분기 후 Job FAILED
- [x] 동일 데이터 재실행 시 UPDATE 발생 (created_at 유지, updated_at 갱신)
- [x] 일별 집계 테이블에 통계 기록

---

## 구현 체크리스트

### Tasklet 구현
- [x] `ValidateTasklet`: 스테이징 데이터 검증
  - 이메일에 '@' 포함 여부 체크
  - customer_id NULL 체크
  - 중복 customer_id 체크 (GROUP BY HAVING)
- [x] `ErrorIsolateTasklet`: 오류 레코드 격리
  - customer_stg → customer_err 이동
  - 오류 사유 기록
- [x] `StatsTasklet`: 일별 집계
  - 성공/실패 건수 계산
  - customer_daily_stats UPSERT

### Step Flow 구현
- [x] validateStep COMPLETED → stagingToTargetStep → statsStepValid
- [x] validateStep INVALID → errorIsolateStep → statsStepInvalid → failStep → FAILED
- [x] ExitStatus 기반 분기 구현 (Decider 대신)

### UPSERT Writer 구현
- [x] PostgreSQL `ON CONFLICT (customer_id)` 사용
- [x] 충돌 시 email, name, phone, updated_at만 갱신
- [x] created_at은 최초 INSERT 시에만 설정

### Step 구성
- [x] validateStep (Tasklet) - ExitStatus: COMPLETED/INVALID
- [x] stagingToTargetStep (Chunk + UPSERT)
- [x] errorIsolateStep (Tasklet)
- [x] statsStep (Tasklet) - ExitStatus: COMPLETED/FAILED (오류 시 Job FAILED)

---

## 예상 코드 구조

### Tasklet 예시
```java
@Component
public class ValidateTasklet implements Tasklet {

    @Override
    public RepeatStatus execute(StepContribution contribution,
                                ChunkContext chunkContext) throws Exception {
        // 검증 로직
        long invalidCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM customer_stg WHERE email NOT LIKE '%@%'",
            Long.class
        );

        if (invalidCount > 0) {
            contribution.setExitStatus(new ExitStatus("INVALID"));
        } else {
            contribution.setExitStatus(ExitStatus.COMPLETED);
        }

        return RepeatStatus.FINISHED;
    }
}
```

### Job Flow 구성 (ExitStatus 기반 분기 + 합류)
```java
@Bean
public Job customerImportJob(JobRepository jobRepository,
                             Step csvToStagingStep,
                             Step validateStep,
                             Step stagingToTargetStep,
                             Step errorIsolateStep,
                             Step statsStep) {
    return new JobBuilder("customerImportJob", jobRepository)
        .start(csvToStagingStep)
        .next(validateStep)
            .on("COMPLETED").to(stagingToTargetStep)
        .from(validateStep)
            .on("INVALID").to(errorIsolateStep)
        .from(validateStep)
            .on("*").fail()
        // 양쪽 경로 모두 statsStep으로 합류
        .from(stagingToTargetStep).on("*").to(statsStep)
        .from(errorIsolateStep).on("*").to(statsStep)
        // statsStep에서 오류 건수 확인 후 ExitStatus 결정
        .from(statsStep).on("FAILED").fail()
        .from(statsStep).on("*").end()
        .end()
        .build();
}
```

> 💡 **핵심 패턴**:
> 1. `validateStep`에서 `ExitStatus("INVALID")` 설정 → 분기
> 2. 양쪽 경로가 `statsStep`으로 **합류**
> 3. `statsStep`에서 오류 건수 확인 → `ExitStatus.FAILED` 설정 시 Job FAILED

### UPSERT SQL (PostgreSQL)
```sql
INSERT INTO customer (customer_id, email, name, phone, created_at, updated_at)
VALUES (:customerId, :email, :name, :phone, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (customer_id) DO UPDATE SET
    email = EXCLUDED.email,
    name = EXCLUDED.name,
    phone = EXCLUDED.phone,
    updated_at = CURRENT_TIMESTAMP
```

---

## 실행 방법

```bash
# 정상 데이터로 실행
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250205.csv runDate=2025-02-05'

# 재실행 (UPSERT 동작 확인)
# 먼저 CSV 수정 후 재실행
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250206.csv runDate=2025-02-06'
```

---

## 검증 방법

### Flow 분기 확인
```sql
-- Step 실행 순서와 상태 확인
SELECT step_name, status, exit_code, start_time
FROM batch_step_execution
WHERE job_execution_id = ?
ORDER BY step_execution_id;
```

### UPSERT 동작 확인
```sql
-- 동일 customer_id로 재실행 후
SELECT customer_id, email, name, created_at, updated_at
FROM customer
WHERE customer_id = 'C001';

-- created_at은 유지, updated_at만 변경되어야 함
```

### 집계 확인
```sql
SELECT * FROM customer_daily_stats WHERE run_date = '2025-02-05';
```

---

## Flow 패턴 정리

### 순차 실행
```java
.start(stepA).next(stepB).next(stepC).end()
```

### 조건부 분기
```java
.start(stepA)
    .on("COMPLETED").to(stepB)
    .from(stepA).on("FAILED").to(stepC)
.end()
```

### Decider 사용
```java
.start(stepA)
.next(decider)
    .on("OPTION_A").to(stepB)
    .from(decider).on("OPTION_B").to(stepC)
.end()
```

### ExitStatus 패턴
| 패턴 | 의미 |
|------|------|
| `*` | 모든 상태 |
| `COMPLETED` | 정상 완료 |
| `FAILED` | 실패 |
| `CUSTOM_STATUS` | 커스텀 상태 |

---

## 트러블슈팅 로그

### 이슈 1: Spring Batch 6.x에서 `fail()` 메서드 사용 불가
- **현상**: `fail()` 또는 `fail(String)` 호출 시 컴파일 오류
- **원인**: Spring Batch 6.x에서 `FlowBuilder.fail()`이 private으로 변경됨
- **해결**: Job을 FAILED로 종료하려면 별도의 `failStep` Tasklet에서 예외 발생

### 이슈 2: Flow 분기 후에도 다른 경로의 Step이 실행됨
- **현상**: VALID 경로에서도 failStep이 실행됨
- **원인**: Flow의 각 분기가 명시적으로 종료되지 않아 다음 Step으로 흐름 이동
- **해결**: 각 경로에서 별도의 statsStep Bean 사용 (statsStepValid, statsStepInvalid)

### 이슈 3: CSV에서 빈 필드가 NULL이 아닌 빈 문자열로 읽힘
- **현상**: `customer_id IS NULL` 검증이 빈 customer_id를 감지하지 못함
- **원인**: `FlatFileItemReader`가 빈 필드를 `""`(빈 문자열)로 변환
- **해결**: 검증 조건에 `customer_id = '' OR customer_id IS NULL` 추가 또는 테스트 기대값 수정

### 이슈 4: UPSERT 시 created_at도 갱신됨
- **현상**: 기존 레코드의 created_at이 변경됨
- **원인**: ON CONFLICT DO UPDATE에 created_at 포함
- **해결**: DO UPDATE SET에서 created_at 제외, updated_at만 CURRENT_TIMESTAMP로 갱신

---

## 회고

### 잘한 점
- 검증 SQL 직접 구현으로 GROUP BY HAVING 패턴 학습
- UPSERT ON CONFLICT 구문 직접 작성으로 PostgreSQL 패턴 이해
- ExitStatus 기반 Flow 분기로 조건부 처리 구현

### 개선할 점
- Spring Batch 6.x API 변경사항 사전 확인 필요
- Flow 분기 시 각 경로의 명시적 종료 처리 중요
- CSV 파싱 시 NULL vs 빈 문자열 차이 고려

### 다음 주 준비
- ExecutionContext 이해
- 재시작 메커니즘 학습
- ItemStream 인터페이스 이해

---

## 참고 링크

### Spring 공식 문서
- [Tasklet Step](https://docs.spring.io/spring-batch/reference/step/tasklet.html)
- [Controlling Step Flow](https://docs.spring.io/spring-batch/reference/step/controlling-flow.html)
- [JobExecutionDecider](https://docs.spring.io/spring-batch/reference/step/controlling-flow.html#programmaticFlowDecisions)
- [Batch Status vs Exit Status](https://docs.spring.io/spring-batch/reference/step/controlling-flow.html#batchStatusVsExitStatus)

### PostgreSQL
- [INSERT ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT)