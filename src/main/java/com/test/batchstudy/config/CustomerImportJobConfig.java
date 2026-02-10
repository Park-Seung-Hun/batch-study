package com.test.batchstudy.config;

import com.test.batchstudy.domain.Customer;
import com.test.batchstudy.domain.CustomerCsv;
import com.test.batchstudy.domain.CustomerStg;
import com.test.batchstudy.processor.RestartableItemProcessor;
import com.test.batchstudy.tasklet.ErrorIsolateTasklet;
import com.test.batchstudy.tasklet.StatsTasklet;
import com.test.batchstudy.tasklet.ValidateTasklet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.DataClassRowMapper;

import javax.sql.DataSource;
import java.time.LocalDate;

/**
 * Week 03: 검증 + 업서트 + Flow 분기 Job 설정
 * <p>
 * JobParameters:
 * - inputFile (String, identifying): 입력 CSV 파일 경로
 * - runDate (String, identifying): 실행 기준일 (yyyy-MM-dd)
 * <p>
 * 처리 흐름:
 * 1. csvToStagingStep: CSV → customer_stg
 * 2. validateStep: 스테이징 데이터 검증
 * 3. validationDecider: VALID/INVALID 분기 결정
 * - VALID → stagingToTargetStep → statsStep
 * - INVALID → errorIsolateStep → statsStep → FAILED
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CustomerImportJobConfig {

    private static final int CHUNK_SIZE = 100;

    private final DataSource dataSource;

    @Bean
    public Job customerImportJob(JobRepository jobRepository,
                                 Step csvToStagingStep,
                                 Step validateStep,
                                 Step stagingToTargetStep,
                                 Step errorIsolateStep,
                                 Step statsStep) {
        // ValidateTasklet의 ExitStatus 기반 분기
        // - COMPLETED: 정상 → stagingToTargetStep → statsStep → 완료
        // - INVALID: 오류 → errorIsolateStep → statsStep → FAILED
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
                // INVALID 경로에서 온 경우 statsStep 완료 후 FAILED 처리
                .from(statsStep).on("FAILED").fail()
                .from(statsStep).on("*").end()
                .end()
                .build();
    }

    /**
     * 통계 집계 Step (Tasklet)
     */
    @Bean
    public Step statsStep(JobRepository jobRepository, StatsTasklet statsTasklet) {
        return new StepBuilder("statsStep", jobRepository)
                .tasklet(statsTasklet)
                .build();
    }

    /**
     * CSV → Staging Step (Week 04: 재시작 지원)
     * <p>
     * RestartableItemProcessor는 ItemStream을 구현하여 처리 상태를 저장합니다.
     * Step에 .stream(processor)를 추가해야 ExecutionContext에 상태가 저장됩니다.
     */
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
                .stream(customerCsvReader)  // Reader 상태 저장 명시적 등록
                .stream(restartableItemProcessor)
                .build();
    }

    /**
     * CSV 파일을 읽어 CustomerCsv로 변환하는 Reader
     *
     * @StepScope: Step 실행 시점에 Bean 생성 → JobParameter Late Binding 가능
     */
    @Bean
    @StepScope
    public FlatFileItemReader<CustomerCsv> customerCsvReader(
            @Value("#{jobParameters['inputFile']}") String inputFile) {
        log.info("Creating customerCsvReader with inputFile: {}", inputFile);

        return new FlatFileItemReaderBuilder<CustomerCsv>()
                .name("customerCsvReader")
                .resource(new FileSystemResource(inputFile))
                .encoding("UTF-8")
                .linesToSkip(1)  // 헤더 스킵
                .delimited()
                .names("customerId", "email", "name", "phone")
                .targetType(CustomerCsv.class)
                .saveState(true)
                .build();
    }

    // Week 04: customerCsvProcessor는 RestartableItemProcessor로 대체됨
    // RestartableItemProcessor는 @Component + @StepScope로 자동 등록됨

    /**
     * CustomerStg를 customer_stg 테이블에 UPSERT하는 Writer
     *
     * Week 04: 재시작 시 UPSERT로 멱등성 보장
     * - (customer_id, run_date) 복합 UNIQUE 제약으로 중복 방지
     * - 같은 데이터를 다시 처리해도 최종 결과는 동일
     */
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

    // ========== Week 03: 검증 + Flow ==========

    /**
     * 검증 Step (Tasklet)
     */
    @Bean
    public Step validateStep(JobRepository jobRepository, ValidateTasklet validateTasklet) {
        return new StepBuilder("validateStep", jobRepository)
                .tasklet(validateTasklet)
                .build();
    }

    /**
     * 오류 격리 Step (Tasklet)
     */
    @Bean
    public Step errorIsolateStep(JobRepository jobRepository, ErrorIsolateTasklet errorIsolateTasklet) {
        return new StepBuilder("errorIsolateStep", jobRepository)
                .tasklet(errorIsolateTasklet)
                .build();
    }

    /**
     * Staging → Target UPSERT Step (Chunk)
     */
    @Bean
    public Step stagingToTargetStep(JobRepository jobRepository,
                                    JdbcCursorItemReader<CustomerStg> stagingReader,
                                    ItemProcessor<CustomerStg, Customer> stagingToCustomerProcessor,
                                    JdbcBatchItemWriter<Customer> customerUpsertWriter) {
        return new StepBuilder("stagingToTargetStep", jobRepository)
                .<CustomerStg, Customer>chunk(CHUNK_SIZE)
                .reader(stagingReader)
                .processor(stagingToCustomerProcessor)
                .writer(customerUpsertWriter)
                .build();
    }

    /**
     * 스테이징 테이블에서 유효한 레코드 읽기
     */
    @Bean
    @StepScope
    public JdbcCursorItemReader<CustomerStg> stagingReader(
            @Value("#{jobParameters['runDate']}") String runDate) {
        log.info("Creating stagingReader with runDate: {}", runDate);

        String sql = """
                SELECT customer_id, email, name, phone, run_date
                FROM customer_stg
                WHERE run_date = ?
                  AND email LIKE '%@%'
                  AND customer_id IS NOT NULL
                  AND customer_id NOT IN (
                      SELECT customer_id FROM customer_stg
                      WHERE run_date = ?
                      GROUP BY customer_id HAVING COUNT(*) > 1
                  )
                """;

        return new JdbcCursorItemReaderBuilder<CustomerStg>()
                .name("stagingReader")
                .dataSource(dataSource)
                .sql(sql)
                .preparedStatementSetter(ps -> {
                    LocalDate parsedDate = LocalDate.parse(runDate);
                    ps.setObject(1, parsedDate);
                    ps.setObject(2, parsedDate);
                })
                .rowMapper(new DataClassRowMapper<>(CustomerStg.class))
                .build();
    }

    /**
     * CustomerStg → Customer 변환 Processor
     */
    @Bean
    public ItemProcessor<CustomerStg, Customer> stagingToCustomerProcessor() {
        return Customer::fromStaging;
    }

    /**
     * Customer UPSERT Writer
     * 힌트:
     * - customer_id가 UNIQUE 제약 (충돌 기준)
     * - 충돌 시 email, name, phone, updated_at만 갱신
     * - created_at은 최초 INSERT 시에만 설정 (DEFAULT)
     * - PostgreSQL: ON CONFLICT (customer_id) DO UPDATE SET ...
     * - EXCLUDED 키워드로 INSERT하려던 값 참조
     */
    @Bean
    public JdbcBatchItemWriter<Customer> customerUpsertWriter() {
        String sql = """
                INSERT INTO customer (customer_id, email, name, phone)
                VALUES (:customerId, :email, :name, :phone)
                ON CONFLICT (customer_id) 
                DO UPDATE SET
                email = :email,
                name = :name,
                phone = :phone,
                updated_at = CURRENT_TIMESTAMP
                """;

        return new JdbcBatchItemWriterBuilder<Customer>()
                .dataSource(dataSource)
                .sql(sql)
                .beanMapped()
                .build();
    }
}
