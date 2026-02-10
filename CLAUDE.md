# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

**Spring Batch 실습 스터디 프로젝트**
- Java 21 + Spring Boot 4.0.2 + Spring Batch + PostgreSQL
- ETL 시나리오: CSV 고객 데이터 → PostgreSQL (스테이징 → 타깃)
- 8주 점진적 확장 방식 학습

## 빌드 및 실행

```bash
# 빌드
./gradlew build

# 애플리케이션 실행 (기본)
./gradlew bootRun

# Job 실행 (파라미터 포함)
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250205.csv'

# 테스트 실행
./gradlew test

# 단일 테스트 실행
./gradlew test --tests "com.test.batchstudy.*Test"

# 클린 빌드
./gradlew clean build
```

## Spring Batch 테스트

### 테스트 실행
```bash
# 전체 테스트
./gradlew test

# 특정 테스트 클래스
./gradlew test --tests "com.test.batchstudy.config.DomainStudyJobTest"

# 특정 테스트 메서드
./gradlew test --tests "*.DomainStudyJobTest.시나리오1*"
```

### 테스트 유틸리티
| 유틸리티 | 용도 |
|----------|------|
| `JobOperatorTestUtils` | Job 실행 및 결과 검증 (`startJob()` 사용) |
| `JobRepositoryTestUtils` | 테스트 간 메타데이터 정리 |
| `@SpringBatchTest` | 테스트 유틸리티 자동 구성 |

### JobParameters 생성 예시
```java
// identifying 파라미터: JobInstance 구분에 사용
new JobParametersBuilder()
    .addString("runDate", "2025-02-05", true)  // identifying=true
    .addLong("chunkSize", 100L, false)         // identifying=false (non-identifying)
    .toJobParameters();
```

### 테스트 코드 규칙
- **`System.out.println` 금지**: 테스트 코드에 디버그용 출력문 사용하지 않음
- 디버깅이 필요한 경우 `System.out.println`을 임시로 사용할 수 있으나, **커밋 전 반드시 제거**
- 테스트 결과는 `assertThat().as("설명")` 형태로 assertion 메시지에 포함

## Spring Batch 6.x 참고사항

### 패키지 구조 변경 (5.x → 6.x)
| 클래스 | 이전 패키지 (5.x) | 현재 패키지 (6.x) |
|--------|------------------|------------------|
| `Job` | `org.springframework.batch.core` | `org.springframework.batch.core.job` |
| `JobExecution` | `org.springframework.batch.core` | `org.springframework.batch.core.job` |
| `JobParameters` | `org.springframework.batch.core` | `org.springframework.batch.core.job.parameters` |
| `JobParametersBuilder` | `org.springframework.batch.core` | `org.springframework.batch.core.job.parameters` |
| `Step` | `org.springframework.batch.core` | `org.springframework.batch.core.step` |

### Deprecated API 대체 (6.0 → 6.2 제거 예정)
| Deprecated | 대안 | 상태 |
|------------|------|------|
| `JobLauncher` | `JobOperator` | ✅ 적용 완료 |
| `JobLauncherTestUtils` | `JobOperatorTestUtils` | ✅ 적용 완료 |
| `launchJob()` | `startJob()` | ✅ 적용 완료 |

> ⚠️ **규칙**: 본 프로젝트에서는 deprecated API 대신 대안을 사용합니다.

### 마이그레이션 가이드
새로운 API나 변경사항 학습 시 참고:
- [Spring Batch 5.0 Migration Guide](https://github.com/spring-projects/spring-batch/wiki/Spring-Batch-5.0-Migration-Guide)
- [Spring Batch 5.1 Migration Guide](https://github.com/spring-projects/spring-batch/wiki/Spring-Batch-5.1-Migration-Guide)
- [Spring Batch 5.2 Migration Guide](https://github.com/spring-projects/spring-batch/wiki/Spring-Batch-5.2-Migration-Guide)
- [What's New in Spring Batch 5.0](https://docs.spring.io/spring-batch/reference/whatsnew.html)

> 학습 중 import 오류나 deprecated 경고 발생 시 위 가이드에서 변경사항 확인

## Spring Batch 구현 패턴

> Week 03 이후 학습한 Spring Batch 핵심 패턴을 정리합니다.

### Tasklet vs Chunk

| 구분 | Tasklet | Chunk |
|------|---------|-------|
| 용도 | 단발성 작업 (검증, 집계, 파일 이동) | 대량 데이터 반복 처리 |
| 트랜잭션 | 단일 트랜잭션 | 청크 단위 트랜잭션 분할 |
| 구현 | `Tasklet` 인터페이스 | Reader → Processor → Writer |

```java
// Tasklet 기본 구조
@Component
@StepScope
@RequiredArgsConstructor
public class MyTasklet implements Tasklet {

    @Value("#{jobParameters['runDate']}")
    private String runDate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // 작업 수행
        return RepeatStatus.FINISHED;
    }
}
```

### ExitStatus 기반 Flow 분기

Step의 ExitStatus를 커스텀하여 Flow 분기 조건으로 활용합니다.

```java
// Tasklet에서 ExitStatus 설정
if (hasErrors) {
    contribution.setExitStatus(new ExitStatus("INVALID"));
} else {
    contribution.setExitStatus(ExitStatus.COMPLETED);
}

// Job Flow에서 ExitStatus 기반 분기
new JobBuilder("myJob", jobRepository)
    .start(validateStep)
        .on("COMPLETED").to(successStep)  // COMPLETED → 정상 경로
    .from(validateStep)
        .on("INVALID").to(errorStep)      // INVALID → 오류 경로
    .from(validateStep)
        .on("*").fail()                   // 그 외 → 실패
    .end()
    .build();
```

### Flow 경로 합류 (Path Merging)

여러 분기 경로가 공통 Step으로 합류하는 패턴입니다.

```java
// 분기 후 공통 Step으로 합류
new JobBuilder("customerImportJob", jobRepository)
    .start(csvToStagingStep)
    .next(validateStep)
        .on("COMPLETED").to(stagingToTargetStep)
    .from(validateStep)
        .on("INVALID").to(errorIsolateStep)
    // 양쪽 경로 모두 statsStep으로 합류
    .from(stagingToTargetStep).on("*").to(statsStep)
    .from(errorIsolateStep).on("*").to(statsStep)
    // statsStep 결과에 따라 최종 상태 결정
    .from(statsStep).on("FAILED").fail()
    .from(statsStep).on("*").end()
    .end()
    .build();
```

### PostgreSQL UPSERT 패턴

`ON CONFLICT` 구문으로 INSERT/UPDATE를 단일 쿼리로 처리합니다.

```java
@Bean
public JdbcBatchItemWriter<Customer> customerUpsertWriter() {
    String sql = """
        INSERT INTO customer (customer_id, email, name, phone)
        VALUES (:customerId, :email, :name, :phone)
        ON CONFLICT (customer_id)
        DO UPDATE SET
            email = EXCLUDED.email,           -- EXCLUDED: INSERT하려던 값 참조
            name = EXCLUDED.name,
            phone = EXCLUDED.phone,
            updated_at = CURRENT_TIMESTAMP    -- 갱신 시간만 업데이트
        """;

    return new JdbcBatchItemWriterBuilder<Customer>()
        .dataSource(dataSource)
        .sql(sql)
        .beanMapped()  // DTO 필드 → Named Parameter 자동 매핑
        .build();
}
```

| 키워드 | 설명 |
|--------|------|
| `ON CONFLICT (column)` | 충돌 감지 기준 컬럼 (UNIQUE 제약) |
| `DO UPDATE SET` | 충돌 시 UPDATE 수행 |
| `EXCLUDED.column` | INSERT하려던 새 값 참조 |
| `DO NOTHING` | 충돌 시 무시 (INSERT 스킵) |

### StepScope와 Late Binding

`@StepScope`를 사용하면 Step 실행 시점에 Bean이 생성되어 JobParameter를 주입받을 수 있습니다.

```java
@Bean
@StepScope  // Step 실행 시점에 Bean 생성
public FlatFileItemReader<CustomerCsv> customerCsvReader(
        @Value("#{jobParameters['inputFile']}") String inputFile) {  // Late Binding
    return new FlatFileItemReaderBuilder<CustomerCsv>()
        .name("customerCsvReader")
        .resource(new FileSystemResource(inputFile))
        .build();
}
```

### 데이터 검증 SQL 패턴

스테이징 테이블에서 오류 레코드를 식별하는 검증 쿼리 패턴입니다.

```sql
-- 오류 건수 조회 (이메일 형식, NULL 체크, 중복 체크)
SELECT COUNT(*) FROM customer_stg
WHERE run_date = ?
  AND (
    email NOT LIKE '%@%'           -- 이메일 형식 오류
    OR customer_id IS NULL         -- 필수값 누락
    OR customer_id IN (            -- 중복 데이터
        SELECT customer_id FROM customer_stg
        WHERE run_date = ?
        GROUP BY customer_id HAVING COUNT(*) > 1
    )
  )
```

## 프로젝트 구조

```
batchstudy/
├── docs/                      # 주차별 학습 문서
│   ├── README.md              # 문서 인덱스
│   ├── _template-week.md      # 주차 문서 템플릿
│   └── week-XX-*.md           # 주차별 학습 정리
├── input/                     # 입력 CSV 파일
├── src/main/java/com/test/batchstudy/
│   ├── config/                # Batch Job/Step 설정
│   ├── domain/                # 엔티티/DTO
│   ├── reader/                # ItemReader 구현
│   ├── processor/             # ItemProcessor 구현
│   ├── writer/                # ItemWriter 구현
│   ├── listener/              # Listener 구현
│   └── tasklet/               # Tasklet 구현
└── src/main/resources/
    ├── application.yml
    └── schema/                # DDL 스크립트
```

## ETL 도메인

### 테이블 구조
| 테이블 | 용도 |
|--------|------|
| `customer_stg` | 스테이징 (원본 추적) |
| `customer` | 타깃 (정제/업서트 결과) |
| `customer_err` | 오류 레코드 격리 |
| `customer_daily_stats` | 일별 집계 (선택) |

### CSV 입력 형식
```
customerId,email,name,phone
C001,kim@example.com,김철수,010-1234-5678
```

### 권장 Job 파라미터 표준
| 파라미터 | 타입 | 설명 | 예시 |
|----------|------|------|------|
| `inputFile` | String (identifying) | 입력 파일 경로 | `input/customers_20250205.csv` |
| `runDate` | String (identifying) | 실행 기준일 | `2025-02-05` |
| `chunkSize` | Long (non-identifying) | 청크 크기 | `100` |
| `skipLimit` | Long (non-identifying) | 스킵 허용 건수 | `10` |

## 커밋 컨벤션

Conventional Commits 형식을 따른다.

### 형식
```
<type>(<scope>): <subject>

<body>
```

### Type
| Type | 설명 |
|------|------|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 수정 (README, CLAUDE.md, docs/*.md) |
| `style` | 코드 포맷팅 (세미콜론 등, 로직 변경 없음) |
| `refactor` | 코드 리팩토링 (기능 변경 없음) |
| `test` | 테스트 코드 추가/수정 |
| `chore` | 빌드, 설정 파일 변경 (gradle, yml 등) |

### Scope (선택)
- `job`: Job 관련
- `step`: Step 관련
- `reader`: Reader 관련
- `writer`: Writer 관련
- `config`: 설정 관련
- `doc`: 문서 관련

### 예시
```bash
# 기능 추가
feat(reader): CSV Reader 구현

# 버그 수정
fix(writer): UPSERT 시 updated_at 갱신 누락 수정

# 문서 수정
docs(week-02): 실습 결과 및 트러블슈팅 정리

# 설정 변경
chore: PostgreSQL 의존성 추가
```

---

## 운영 규칙

### 실행 전 체크리스트
- [ ] PostgreSQL 실행 중인지 확인
- [ ] 메타 테이블 존재 여부 확인 (`BATCH_JOB_INSTANCE` 등)
- [ ] 입력 파일 존재 여부 확인
- [ ] 이전 실행 상태 확인 (FAILED 재시작 여부)

### 실행 후 검증
```sql
-- Job 실행 결과 확인
SELECT * FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC LIMIT 5;

-- Step 실행 결과 확인
SELECT * FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ?;

-- 데이터 적재 결과
SELECT COUNT(*) FROM customer_stg WHERE run_date = '2025-02-05';
SELECT COUNT(*) FROM customer WHERE updated_at >= CURRENT_DATE;
SELECT COUNT(*) FROM customer_err WHERE run_date = '2025-02-05';
```

### 문서 동기화 규칙

> ⚠️ **중요**: 코드 변경 시 `docs/README.md`도 함께 업데이트해야 합니다.

| 변경 사항 | 업데이트 대상 |
|-----------|--------------|
| 주차별 학습 완료 | 주차별 상태 (⬜ → ✅) |
| Job/Step 추가/수정 | Job/Step 구성 섹션 |
| 새로운 파라미터 추가 | 권장 파라미터 표준 |

## 문서 인덱스

- [주차별 학습 문서](docs/README.md)
- [Week 00: 환경 세팅](docs/week-00-setup.md)
- [Week 01: 배치 도메인 언어](docs/week-01-domain-language.md)
- [Week 02: CSV → Staging](docs/week-02-csv-to-staging.md)
- [Week 03: 검증 + 업서트 + Flow](docs/week-03-validate-upsert-flow.md)
- [Week 04: 재시작](docs/week-04-restartability.md)
- [Week 05: 내결함성](docs/week-05-fault-tolerance.md)
- [Week 06: 파라미터 + Scope](docs/week-06-params-scope.md)
- [Week 07: 병렬/튜닝](docs/week-07-parallel-tuning.md)
- [Week 08: 테스트 + 운영](docs/week-08-testing-ops.md)

## Spring 공식 문서

- [Spring Batch Reference](https://docs.spring.io/spring-batch/reference/)
- [Spring Boot Batch](https://docs.spring.io/spring-boot/reference/io/batch.html)