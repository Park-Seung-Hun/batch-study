# Spring Batch 실습 스터디

> Java 21 + Spring Boot 4.0.2 + Spring Batch + PostgreSQL 기반
> CSV → DB(스테이징 → 타깃) ETL 실습을 통한 Spring Batch 완전 정복

## 스터디 개요

### 목표
- Spring Batch의 핵심 개념과 도메인 언어 이해
- 실무 수준의 ETL 파이프라인 구축 경험
- 재시작, 내결함성, 병렬 처리 등 운영 관점 학습
- 테스트 및 관측성 확보

### ETL 시나리오
```
[CSV 파일] → [파싱] → [정제/검증] → [스테이징] → [업서트] → [타깃]
                              ↓
                         [오류 격리]
```

### 대상 테이블
| 테이블 | 용도 |
|--------|------|
| `customer_stg` | 스테이징 (원본 추적) |
| `customer` | 타깃 (정제/업서트 결과) |
| `customer_err` | 오류 레코드 격리 |
| `customer_daily_stats` | 일별 집계 (선택) |

---

## 주차별 학습 문서

| 주차 | 주제 | 핵심 키워드 | 상태 |
|------|------|-------------|------|
| [Week 00](week-00-setup.md) | 환경 세팅 | PostgreSQL, 메타 스키마, Gradle | ✅ |
| [Week 01](week-01-domain-language.md) | 배치 도메인 언어 | Job, Step, Execution, JobRepository | ✅ |
| [Week 02](week-02-csv-to-staging.md) | CSV → Staging | Chunk, FlatFileItemReader, JdbcBatchItemWriter | ✅ |
| [Week 03](week-03-validate-upsert-flow.md) | 검증 + 업서트 + Flow | Multi-Step, Tasklet, Decider | ⬜ |
| [Week 04](week-04-restartability.md) | 재시작 | ExecutionContext, ItemStream, 멱등성 | ⬜ |
| [Week 05](week-05-fault-tolerance.md) | 내결함성 | Skip, Retry, Listener, 오류 격리 | ⬜ |
| [Week 06](week-06-params-scope.md) | 파라미터 + Scope | JobScope, StepScope, Late Binding | ⬜ |
| [Week 07](week-07-parallel-tuning.md) | 병렬/튜닝 | Multi-thread, Partitioning, 성능 측정 | ⬜ |
| [Week 08](week-08-testing-ops.md) | 테스트 + 운영 | spring-batch-test, Actuator, Micrometer | ⬜ |

**상태**: ⬜ 예정 / 🟡 진행중 / ✅ 완료

---

## Job/Step 구성

### domainStudyJob (Week 01: 도메인 학습용)
```
domainStudyJob                    ✅ 구현 완료
├── domainStep1 (Tasklet)         ✅ runDate 파라미터 로깅
└── domainStep2 (Tasklet)         ✅ chunkSize 파라미터 로깅
```

### customerImportJob (Week 02~08: ETL 파이프라인)
```
customerImportJob
├── initStep (Tasklet)           ⬜ Week 03 예정 - 초기화/검증
├── csvToStagingStep (Chunk)     ✅ Week 02 완료 - CSV → customer_stg
├── validateStep (Tasklet)       ⬜ Week 03 예정 - 스테이징 검증
├── stagingToTargetStep (Chunk)  ⬜ Week 03 예정 - customer_stg → customer (업서트)
├── errorIsolateStep (Tasklet)   ⬜ Week 05 예정 - 오류 레코드 격리
└── statsStep (Tasklet)          ⬜ 선택 - 일별 집계
```

### Step Flow
```
initStep → csvToStagingStep → validateStep
                                   ↓
                    [VALID] → stagingToTargetStep → statsStep
                    [INVALID] → errorIsolateStep → FAILED
```

---

## 권장 파라미터 표준

### Identifying Parameters (Job Instance 구분)
| 파라미터 | 타입 | 설명 | 예시 |
|----------|------|------|------|
| `inputFile` | String | 입력 파일 경로 | `input/customers_20250205.csv` |
| `runDate` | String | 실행 기준일 (YYYY-MM-DD) | `2025-02-05` |

### Non-Identifying Parameters (동작 제어)
| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| `chunkSize` | Long | 100 | 청크 크기 |
| `skipLimit` | Long | 10 | 스킵 허용 건수 |
| `retryLimit` | Long | 3 | 재시도 횟수 |

---

## 실행 방법

```bash
# 기본 실행
./gradlew bootRun --args='inputFile=input/customers_20250205.csv runDate=2025-02-05'

# 청크 크기 지정
./gradlew bootRun --args='inputFile=input/customers_20250205.csv runDate=2025-02-05 chunkSize=500'

# 재시작 (FAILED 상태에서)
./gradlew bootRun --args='inputFile=input/customers_20250205.csv runDate=2025-02-05'
```

---

## 검증 쿼리

```sql
-- 최근 Job 실행 이력
SELECT JOB_INSTANCE_ID, JOB_NAME, STATUS, START_TIME, END_TIME
FROM BATCH_JOB_EXECUTION
ORDER BY JOB_EXECUTION_ID DESC
LIMIT 10;

-- Step별 처리 건수
SELECT STEP_NAME, READ_COUNT, WRITE_COUNT, SKIP_COUNT, STATUS
FROM BATCH_STEP_EXECUTION
WHERE JOB_EXECUTION_ID = ?;

-- 데이터 적재 현황
SELECT 'customer_stg' AS tbl, COUNT(*) AS cnt FROM customer_stg WHERE run_date = ?
UNION ALL
SELECT 'customer' AS tbl, COUNT(*) AS cnt FROM customer
UNION ALL
SELECT 'customer_err' AS tbl, COUNT(*) AS cnt FROM customer_err WHERE run_date = ?;
```

---

## 리소스

- [주차 문서 템플릿](_template-week.md)
- [프로젝트 루트 CLAUDE.md](../CLAUDE.md)

### Spring 공식 문서
- [Spring Batch Reference](https://docs.spring.io/spring-batch/reference/)
- [Spring Boot Batch](https://docs.spring.io/spring-boot/reference/io/batch.html)
- [Domain Language of Batch](https://docs.spring.io/spring-batch/reference/domain.html)
- [Schema Appendix](https://docs.spring.io/spring-batch/reference/schema-appendix.html)