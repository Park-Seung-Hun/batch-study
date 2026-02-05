# Week 00: 환경 세팅

> 작성일: 2025-02-05
> 상태: ✅ 완료

---

## 이번 주 목표

- [x] Embedded PostgreSQL 설정 (Docker 불필요)
- [x] Spring Batch 메타 테이블 스키마 자동 생성
- [x] 프로젝트 의존성 추가 (spring-boot-starter-batch, postgresql, embedded-postgres)
- [x] 기본 helloJob 실행 및 메타 테이블 확인
- [x] 비즈니스 테이블 DDL 생성 (customer_stg, customer, customer_err, customer_daily_stats)

---

## 핵심 개념 요약 (내 말로)

### Spring Batch 메타 테이블
> 한 줄 정의: Job/Step 실행 이력과 상태를 저장하는 Spring Batch의 내부 테이블

메타 테이블은 재시작, 멱등성, 실행 이력 추적의 핵심이다.
- `BATCH_JOB_INSTANCE`: Job + identifying parameters 조합 (논리적 실행 단위)
- `BATCH_JOB_EXECUTION`: 실제 실행 기록 (시작/종료 시간, 상태)
- `BATCH_JOB_EXECUTION_PARAMS`: 실행 파라미터
- `BATCH_STEP_EXECUTION`: Step별 실행 기록 (read/write/skip count)
- `BATCH_STEP_EXECUTION_CONTEXT`: Step의 ExecutionContext (재시작용)

### DataSource 구성
> 한 줄 정의: Spring Batch는 메타 테이블용 DataSource가 필수

Spring Batch는 기본적으로 트랜잭션 관리를 위해 DataSource를 요구한다.
개발 환경에서는 Embedded PostgreSQL을 사용하여 Docker 없이도 실행 가능.

### Embedded PostgreSQL
> 한 줄 정의: JVM 프로세스 내에서 실행되는 PostgreSQL 인스턴스

`io.zonky.test:embedded-postgres` 라이브러리를 사용하면 Docker 없이도 PostgreSQL을 사용할 수 있다.
애플리케이션 시작 시 자동으로 PostgreSQL이 시작되고, 종료 시 함께 정리된다.

---

## 실습 시나리오

### 입력
- 없음 (환경 세팅 단계)

### 처리
- Embedded PostgreSQL 자동 시작
- 메타 스키마 자동 생성 (Batch 스키마 SQL을 `spring.sql.init.schema-locations`에 포함)
- 비즈니스 테이블 DDL 자동 실행 (`spring.sql.init.mode: always`)
- helloJob 실행

### 출력
- Embedded PostgreSQL 시작 로그 확인
- 메타 테이블 생성 확인
- 비즈니스 테이블 4개 생성 확인
- BATCH_JOB_EXECUTION에 COMPLETED 레코드 1건

### 성공 기준
- [x] 빌드 성공 (`./gradlew build`)
- [x] Embedded PostgreSQL 자동 시작
- [x] 메타 테이블 자동 생성
  - 확인: `./gradlew test --tests 'com.test.batchstudy.SchemaInitializationTest'`
- [x] 비즈니스 테이블 4개 생성 확인 (customer_stg, customer, customer_err, customer_daily_stats)
  - 확인: `./gradlew test --tests 'com.test.batchstudy.SchemaInitializationTest'`
- [x] helloJob 실행 후 COMPLETED 상태 확인

---

## 구현 체크리스트

### 의존성 추가 (build.gradle)
- [x] `spring-boot-starter-batch`
- [x] `spring-boot-starter-jdbc`
- [x] `org.postgresql:postgresql`
- [x] `org.projectlombok:lombok`
- [x] `io.zonky.test:embedded-postgres:2.1.0`
- [x] `io.zonky.test.postgres:embedded-postgres-binaries-bom:16.6.0`

### application.yml 설정
- [x] `spring.batch.job.enabled: false` (수동 실행 모드)
- [x] `spring.sql.init.mode: always` (메타/비즈니스 DDL 자동 실행)

### 설정 클래스
- [x] `EmbeddedPostgresConfig.java` - Embedded PostgreSQL DataSource 설정

### DDL 스크립트
- [x] `schema/V001__create_business_tables.sql`

### 기본 Job 구성
- [x] `HelloJobConfig.java` - Tasklet 기반 helloJob 생성

---

## 프로젝트 구조

```
batchstudy/
├── build.gradle                                          # 의존성 설정
├── src/main/java/com/test/batchstudy/
│   ├── BatchstudyApplication.java                        # 메인 클래스
│   └── config/
│       ├── EmbeddedPostgresConfig.java                   # Embedded PostgreSQL 설정
│       └── HelloJobConfig.java                           # helloJob 설정
└── src/main/resources/
    ├── application.yml                                   # 애플리케이션 설정
    └── schema/
        └── V001__create_business_tables.sql              # 비즈니스 테이블 DDL
```

---

## 실행 방법

### 1. 빌드
```bash
./gradlew build
```

### 2. helloJob 실행
```bash
./gradlew bootRun --args='--spring.batch.job.name=helloJob --spring.batch.job.enabled=true'
```

### 3. 실행 로그 확인
```
Embedded PostgreSQL started on port: 51233
Job: [SimpleJob: [name=helloJob]] launched with the following parameters: [{}]
Executing step: [helloStep]
========================================
Hello, Spring Batch!
Week 00 환경 세팅 완료!
========================================
Step: [helloStep] executed in 9ms
Job: [SimpleJob: [name=helloJob]] completed with the following parameters: [{}] and the following status: [COMPLETED] in 10ms
```

---

## 구현 코드

### build.gradle

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.2'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.test'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-batch'
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    runtimeOnly 'org.postgresql:postgresql'

    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    // Embedded PostgreSQL (개발/테스트 환경)
    implementation enforcedPlatform('io.zonky.test.postgres:embedded-postgres-binaries-bom:16.6.0')
    implementation 'io.zonky.test:embedded-postgres:2.1.0'

    testCompileOnly 'org.projectlombok:lombok'
    testAnnotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.batch:spring-batch-test'
    testImplementation 'io.zonky.test:embedded-database-spring-test:2.6.0'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

### application.yml

```yaml
spring:
  application:
    name: batchstudy

  batch:
    # Job 자동 실행 비활성화 (명시적 실행만 허용)
    job:
      enabled: false
    # 메타 테이블 자동 생성
    jdbc:
      initialize-schema: always

  sql:
    init:
      # 비즈니스 테이블 DDL 자동 실행
      mode: always
      schema-locations:
        - classpath:org/springframework/batch/core/schema-postgresql.sql
        - classpath:schema/*.sql

logging:
  level:
    org.springframework.batch: INFO
    com.test.batchstudy: DEBUG
```

### EmbeddedPostgresConfig.java

```java
package com.test.batchstudy.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;

@Slf4j
@Configuration
public class EmbeddedPostgresConfig {

    private EmbeddedPostgres embeddedPostgres;

    @Bean
    public DataSource dataSource() throws IOException {
        log.info("Starting Embedded PostgreSQL...");
        embeddedPostgres = EmbeddedPostgres.builder()
                .start();
        log.info("Embedded PostgreSQL started on port: {}", embeddedPostgres.getPort());
        return embeddedPostgres.getPostgresDatabase();
    }

    @PreDestroy
    public void stop() throws IOException {
        if (embeddedPostgres != null) {
            log.info("Stopping Embedded PostgreSQL...");
            embeddedPostgres.close();
        }
    }
}
```

### HelloJobConfig.java

```java
package com.test.batchstudy.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Slf4j
@Configuration
public class HelloJobConfig {

    @Bean
    public Job helloJob(JobRepository jobRepository, Step helloStep) {
        return new JobBuilder("helloJob", jobRepository)
                .start(helloStep)
                .build();
    }

    @Bean
    public Step helloStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("helloStep", jobRepository)
                .tasklet(helloTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Tasklet helloTasklet() {
        return (contribution, chunkContext) -> {
            log.info("========================================");
            log.info("Hello, Spring Batch!");
            log.info("Week 00 환경 세팅 완료!");
            log.info("========================================");
            return RepeatStatus.FINISHED;
        };
    }
}
```

---

## 비즈니스 테이블 DDL

**파일**: `src/main/resources/schema/V001__create_business_tables.sql`

```sql
-- 스테이징 테이블: CSV 원본 데이터 적재
CREATE TABLE IF NOT EXISTS customer_stg (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(50) NOT NULL,
    email           VARCHAR(255),
    name            VARCHAR(100),
    phone           VARCHAR(20),
    run_date        DATE NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_customer_stg_run_date ON customer_stg(run_date);
CREATE INDEX IF NOT EXISTS idx_customer_stg_customer_id ON customer_stg(customer_id);

-- 타깃 테이블: 정제/업서트 결과
CREATE TABLE IF NOT EXISTS customer (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(50) NOT NULL UNIQUE,
    email           VARCHAR(255),
    name            VARCHAR(100),
    phone           VARCHAR(20),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_customer_email ON customer(email);

-- 오류 레코드 격리 테이블
CREATE TABLE IF NOT EXISTS customer_err (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(50),
    email           VARCHAR(255),
    name            VARCHAR(100),
    phone           VARCHAR(20),
    error_message   TEXT,
    run_date        DATE NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_customer_err_run_date ON customer_err(run_date);

-- 일별 집계 테이블 (선택)
CREATE TABLE IF NOT EXISTS customer_daily_stats (
    id              BIGSERIAL PRIMARY KEY,
    run_date        DATE NOT NULL UNIQUE,
    total_count     INTEGER DEFAULT 0,
    success_count   INTEGER DEFAULT 0,
    error_count     INTEGER DEFAULT 0,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## Spring Batch 6.0 패키지 변경 사항

Spring Batch 6.0에서 주요 클래스의 패키지가 변경되었다:

| 클래스 | 기존 (5.x) | 변경 (6.0) |
|--------|-----------|-----------|
| `Job` | `org.springframework.batch.core.Job` | `org.springframework.batch.core.job.Job` |
| `Step` | `org.springframework.batch.core.Step` | `org.springframework.batch.core.step.Step` |
| `JobBuilder` | `org.springframework.batch.core.job.builder.JobBuilder` | 동일 |
| `StepBuilder` | `org.springframework.batch.core.step.builder.StepBuilder` | 동일 |
| `Tasklet` | `org.springframework.batch.core.step.tasklet.Tasklet` | 동일 |
| `RepeatStatus` | `org.springframework.batch.repeat.RepeatStatus` | `org.springframework.batch.infrastructure.repeat.RepeatStatus` |

---

## 트러블슈팅 로그

### 이슈 1: Spring Batch 6.0 import 오류
- **현상**: `cannot find symbol: class Job` 컴파일 오류
- **원인**: Spring Batch 6.0에서 패키지 구조 변경됨
- **해결**:
  - `org.springframework.batch.core.Job` → `org.springframework.batch.core.job.Job`
  - `org.springframework.batch.core.Step` → `org.springframework.batch.core.step.Step`
  - `org.springframework.batch.repeat.RepeatStatus` → `org.springframework.batch.infrastructure.repeat.RepeatStatus`

### 이슈 2: Job이 실행되지 않음
- **현상**: 애플리케이션은 시작되지만 Job이 실행되지 않음
- **원인**: `spring.batch.job.enabled: false` 설정
- **해결**: 실행 시 `--spring.batch.job.enabled=true` 추가
  ```bash
  ./gradlew bootRun --args='--spring.batch.job.name=helloJob --spring.batch.job.enabled=true'
  ```

---

## 회고

### 잘한 점
- Docker 없이 Embedded PostgreSQL로 간편하게 환경 구성
- Spring Batch 6.0 패키지 변경 사항 파악 및 적용

### 개선할 점
- 프로파일별 설정 분리 (dev/prod)
- 외부 PostgreSQL 연결 옵션 추가

### 다음 주 준비
- Job/Step/Execution 개념 정리
- JobParameters 이해
- CSV 파일 읽기 (FlatFileItemReader)

---

## 참고 링크

### Spring 공식 문서
- [Spring Batch Reference](https://docs.spring.io/spring-batch/reference/)
- [Schema Appendix (메타 테이블 구조)](https://docs.spring.io/spring-batch/reference/schema-appendix.html)
- [Spring Boot Batch Auto-configuration](https://docs.spring.io/spring-boot/reference/io/batch.html)

### Embedded PostgreSQL
- [zonky embedded-postgres GitHub](https://github.com/zonkyio/embedded-postgres)
- [embedded-database-spring-test](https://github.com/zonkyio/embedded-database-spring-test)
