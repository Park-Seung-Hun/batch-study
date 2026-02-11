# Week 02: CSV → Staging (Chunk 처리)

> 작성일: 2025-02-06
> 상태: ✅ 완료

---

## 이번 주 목표

- [x] Chunk 기반 처리 모델 이해 (Reader-Processor-Writer)
- [x] FlatFileItemReader로 CSV 파일 읽기 구현
- [x] JdbcBatchItemWriter로 DB 적재 구현
- [x] Chunk Size와 Commit Interval 이해
- [x] 트랜잭션 경계와 롤백 단위 이해

---

## 핵심 개념 요약 (내 말로)

### Chunk 처리 모델
> 한 줄 정의: N개 아이템을 읽고(Read), 변환하고(Process), 한 번에 쓰는(Write) 패턴

```
[Read] → [Read] → [Read] → ... (N번)
            ↓
[Process] → [Process] → [Process] → ... (N번)
            ↓
      [Write] (1번, N개 일괄)
            ↓
        [Commit]
```

- Chunk Size = Commit Interval = 트랜잭션 단위
- 실패 시 해당 Chunk만 롤백 (이전 Chunk는 이미 커밋됨)

### FlatFileItemReader
> 한 줄 정의: 플랫 파일(CSV, 고정폭 등)을 한 줄씩 읽어 객체로 매핑하는 Reader

구성 요소:
- `Resource`: 읽을 파일 지정
- `LineMapper`: 한 줄 → 객체 변환
- `LineTokenizer`: 한 줄 → 필드 분리
- `FieldSetMapper`: 필드 → 객체 매핑

### JdbcBatchItemWriter
> 한 줄 정의: JDBC Batch를 사용해 여러 레코드를 한 번에 DB에 적재하는 Writer

내부적으로 `PreparedStatement.addBatch()` + `executeBatch()` 사용.
Chunk Size만큼 모아서 한 번에 실행하므로 성능 우수.

### Commit Interval
> 한 줄 정의: 트랜잭션 커밋 주기 = Chunk Size

- Chunk Size 100 = 100개 처리 후 커밋
- 너무 작으면: 커밋 오버헤드 증가
- 너무 크면: 롤백 시 손실 증가, 메모리 사용량 증가

### @StepScope
> 한 줄 정의: Step 실행 시점에 Bean을 생성하여 JobParameters를 Late Binding으로 주입받는 스코프

- Bean 생성 시점을 Step 실행 시점으로 지연
- `#{jobParameters['inputFile']}` 같은 SpEL 표현식 사용 가능
- 각 Step 실행마다 새로운 Bean 인스턴스 생성

---

## 실습 시나리오

### 입력
- 파일: `input/customers_20250205.csv`
- 형식:
```csv
customerId,email,name,phone
C001,c001@naver.com,강지현,010-8998-8514
C002,c002@gmail.com,김수빈,010-7234-8818
C003,c003@naver.com,최지우,010-9013-6269
```

### 처리
```
csvToStagingStep (Chunk 기반, size=100)
├── Reader: FlatFileItemReader (CSV → CustomerCsv record)
├── Processor: CustomerCsv → CustomerStg 변환 (runDate 추가)
└── Writer: JdbcBatchItemWriter (customer_stg INSERT)
```

### 출력
- `customer_stg` 테이블에 데이터 적재
- `run_date` 컬럼에 실행일자 기록

### 성공 기준
- [x] CSV 파일의 모든 레코드가 `customer_stg`에 적재됨 (100건)
- [x] `run_date`가 정확히 기록됨
- [x] BATCH_STEP_EXECUTION의 READ_COUNT = WRITE_COUNT 일치
- [x] Chunk Size(100) 기준 COMMIT_COUNT = 1 확인

---

## 구현 체크리스트

### DTO 클래스
- [x] `CustomerCsv` (CSV 레코드 매핑용 record)
- [x] `CustomerStg` (스테이징 테이블 매핑용 record)

### Reader 구현
- [x] FlatFileItemReader 빈 생성 (`@StepScope`)
- [x] DelimitedLineTokenizer 설정 (컬럼 매핑)
- [x] `targetType(CustomerCsv.class)`로 record 매핑
- [x] 헤더 스킵 설정 (`linesToSkip=1`)
- [x] UTF-8 인코딩 설정

### Processor 구현
- [x] CustomerCsv → CustomerStg 변환
- [x] runDate 파라미터 주입 (`@StepScope` + Late Binding)

### Writer 구현
- [x] JdbcBatchItemWriter 빈 생성
- [x] INSERT SQL 작성 (Named Parameter)
- [x] `.beanMapped()` 설정

### Step/Job 구성
- [x] csvToStagingStep 생성 (Chunk Size = 100)
- [x] customerImportJob 정의

---

## 구현 코드

### 파일 구조
```
src/main/java/com/test/batchstudy/
├── config/
│   └── CustomerImportJobConfig.java   # Job/Step/Reader/Processor/Writer
└── domain/
    ├── CustomerCsv.java               # CSV 매핑용 record
    └── CustomerStg.java               # Staging 테이블 매핑용 record

input/
└── customers_20250205.csv             # 테스트 CSV (100건)

src/test/java/com/test/batchstudy/config/
└── CustomerImportJobTest.java         # 테스트
```

### DTO
```java
// CustomerCsv.java - CSV 파일 매핑용
public record CustomerCsv(
    String customerId,
    String email,
    String name,
    String phone
) {}

// CustomerStg.java - 스테이징 테이블 매핑용
public record CustomerStg(
    String customerId,
    String email,
    String name,
    String phone,
    LocalDate runDate
) {}
```

### Reader 설정
```java
@Bean
@StepScope
public FlatFileItemReader<CustomerCsv> customerCsvReader(
        @Value("#{jobParameters['inputFile']}") String inputFile) {

    return new FlatFileItemReaderBuilder<CustomerCsv>()
        .name("customerCsvReader")
        .resource(new FileSystemResource(inputFile))
        .encoding("UTF-8")
        .linesToSkip(1)  // 헤더 스킵
        .delimited()
        .names("customerId", "email", "name", "phone")
        .targetType(CustomerCsv.class)
        .build();
}
```

### Processor 설정
```java
@Bean
@StepScope
public ItemProcessor<CustomerCsv, CustomerStg> customerCsvProcessor(
        @Value("#{jobParameters['runDate']}") String runDate) {

    LocalDate parsedRunDate = LocalDate.parse(runDate);

    // ItemProcessor는 함수형 인터페이스 → 람다로 구현
    return csv -> new CustomerStg(
        csv.customerId(),
        csv.email(),
        csv.name(),
        csv.phone(),
        parsedRunDate
    );
}
```

### Writer 설정
```java
@Bean
public JdbcBatchItemWriter<CustomerStg> customerStgWriter() {
    String sql = """
        INSERT INTO customer_stg (customer_id, email, name, phone, run_date)
        VALUES (:customerId, :email, :name, :phone, :runDate)
        """;

    return new JdbcBatchItemWriterBuilder<CustomerStg>()
        .dataSource(dataSource)
        .sql(sql)
        .beanMapped()  // record 필드명 → Named Parameter 자동 매핑
        .build();
}
```

### Step/Job 설정
```java
@Bean
public Step csvToStagingStep(JobRepository jobRepository,
                             FlatFileItemReader<CustomerCsv> customerCsvReader,
                             ItemProcessor<CustomerCsv, CustomerStg> customerCsvProcessor,
                             JdbcBatchItemWriter<CustomerStg> customerStgWriter) {
    return new StepBuilder("csvToStagingStep", jobRepository)
        .<CustomerCsv, CustomerStg>chunk(CHUNK_SIZE)  // 100
        .reader(customerCsvReader)
        .processor(customerCsvProcessor)
        .writer(customerStgWriter)
        .build();
}

@Bean
public Job customerImportJob(JobRepository jobRepository, Step csvToStagingStep) {
    return new JobBuilder("customerImportJob", jobRepository)
        .start(csvToStagingStep)
        .build();
}
```

---

## 변경된 파일

| 파일 | 변경 내용 |
|------|----------|
| `config/CustomerImportJobConfig.java` | `csvToStagingStep`, `customerImportJob` 신규. Reader/Processor/Writer Bean 정의 |
| `domain/CustomerCsv.java` | CSV 매핑용 record 신규 (4필드: customerId, email, name, phone) |
| `domain/CustomerStg.java` | 스테이징 매핑용 record 신규 (5필드, runDate 추가) |
| `input/customers_20250205.csv` | 100건 테스트 데이터 신규 |
| `test/CustomerImportJobTest.java` | 적재 건수/READ_COUNT/COMMIT_COUNT 검증 테스트 3건 |

---

## 실행 방법

```bash
# Job 실행
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250205.csv runDate=2025-02-05'

# 테스트 실행
./gradlew test --tests "*CustomerImportJobTest*"
```

---

## 검증 방법

### 적재 결과 확인
```sql
-- 건수 확인
SELECT COUNT(*) FROM customer_stg WHERE run_date = '2025-02-05';

-- 샘플 데이터 확인
SELECT * FROM customer_stg WHERE run_date = '2025-02-05' LIMIT 10;
```

### Step 실행 통계 확인
```sql
SELECT
    step_name,
    read_count,
    write_count,
    commit_count,
    rollback_count,
    status
FROM batch_step_execution
WHERE job_execution_id = (
    SELECT MAX(job_execution_id) FROM batch_job_execution
);
```

### Chunk Size 검증
```
Chunk Size = 100, 총 100건 → COMMIT_COUNT = 1
Chunk Size = 100, 총 1000건 → COMMIT_COUNT = 10
Chunk Size = 50,  총 1000건 → COMMIT_COUNT = 20
```

---

## 테스트 결과

### 테스트 시나리오
| 테스트 | 검증 내용 | 결과 |
|--------|----------|------|
| CSV파일_스테이징_적재_성공 | 100건 CSV → customer_stg 적재 | ✅ PASS |
| READ_COUNT_WRITE_COUNT_일치 | READ_COUNT = WRITE_COUNT = 100 | ✅ PASS |
| ChunkSize_기반_COMMIT_COUNT_검증 | Chunk 100, 데이터 100건 → COMMIT_COUNT = 1 | ✅ PASS |

### 실행 로그
```
Job: [SimpleJob: [name=customerImportJob]] launched with the following parameters:
  [{inputFile=input/customers_20250205.csv, runDate=2025-02-05}]

Executing step: [csvToStagingStep]
Creating customerCsvReader with inputFile: input/customers_20250205.csv
Creating customerCsvProcessor with runDate: 2025-02-05
Step: [csvToStagingStep] executed in 29ms

Job completed with status: [COMPLETED] in 30ms
```

---

## 트러블슈팅 로그

### 이슈 1: 파일을 찾을 수 없음
- **현상**: `FileNotFoundException`
- **원인**: 상대 경로 문제 또는 파일 미존재
- **해결**: `FileSystemResource` 사용, 프로젝트 루트 기준 경로 확인

### 이슈 2: CSV 컬럼 매핑 오류
- **현상**: `IncorrectTokenCountException`
- **원인**: CSV 컬럼 수와 names 배열 불일치
- **해결**: `names()` 배열과 CSV 헤더 일치 확인

### 이슈 3: 한글 깨짐
- **현상**: DB에 한글이 ??? 로 저장됨
- **원인**: Reader의 인코딩 설정 누락
- **해결**: `.encoding("UTF-8")` 추가

### 이슈 4: ItemProcessor 람다 구현
- **현상**: `return () -> new CustomerStg()` 컴파일 에러
- **원인**: ItemProcessor는 입력을 받아 출력을 반환하는 함수형 인터페이스
- **해결**: `return csv -> new CustomerStg(...)` 형태로 입력 파라미터 추가

---

## 회고

### 잘한 점
- Chunk 처리 모델의 Read-Process-Write 흐름 이해
- @StepScope와 Late Binding 개념 학습
- Java record를 활용한 간결한 DTO 구현

### 개선할 점
- ItemProcessor 함수형 인터페이스 패턴 더 익숙해지기
- Chunk Size 튜닝 기준 학습 필요

### 다음 주 준비
- 데이터 검증 로직 추가 (유효성 검사)
- customer 타깃 테이블로 UPSERT
- Step Flow 분기 (성공/실패에 따른 분기)

---

## 참고 링크

### Spring 공식 문서
- [Chunk-oriented Processing](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing.html)
- [Configuring a Step (Commit Interval)](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/commit-interval.html)
- [FlatFileItemReader](https://docs.spring.io/spring-batch/reference/readers-and-writers/flat-files.html)
- [Database ItemWriters](https://docs.spring.io/spring-batch/reference/readers-and-writers/database.html)

### 추가 자료
- [ItemReader/ItemWriter 구현체 목록](https://docs.spring.io/spring-batch/reference/readers-and-writers/item-reader-writer-implementations.html)
- [Late Binding of Job and Step Attributes](https://docs.spring.io/spring-batch/reference/step/late-binding.html)
