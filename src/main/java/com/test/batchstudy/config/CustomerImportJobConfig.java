package com.test.batchstudy.config;

import com.test.batchstudy.domain.Customer;
import com.test.batchstudy.partitioner.CustomerPartitioner;
import com.test.batchstudy.domain.CustomerCsv;
import com.test.batchstudy.domain.CustomerStg;
import com.test.batchstudy.listener.ErrorIsolationSkipListener;
import com.test.batchstudy.listener.RetryLoggingListener;
import com.test.batchstudy.processor.RestartableItemProcessor;
import com.test.batchstudy.tasklet.ErrorIsolateTasklet;
import com.test.batchstudy.tasklet.StatsTasklet;
import com.test.batchstudy.tasklet.ValidateTasklet;
import com.test.batchstudy.validator.CustomerImportJobParametersValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.item.database.support.PostgresPagingQueryProvider;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.item.support.SynchronizedItemStreamReader;
import org.springframework.batch.infrastructure.item.validator.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Map;

/**
 * Week 06: 파라미터 + Scope 실습
 * <p>
 * JobParameters:
 * - inputFile (String, identifying): 입력 CSV 파일 경로
 * - runDate (String, identifying): 실행 기준일 (yyyy-MM-dd)
 * - chunkSize (Long, non-identifying): 청크 크기 (기본값 100)
 * - skipLimit (Long, non-identifying): 스킵 허용 건수 (기본값 10)
 * - retryLimit (Long, non-identifying): 재시도 횟수 (기본값 3)
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

    private final DataSource dataSource;
    private final CustomerImportJobParametersValidator validator;

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
                .validator(validator)
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

    // ========== Week 07: 병렬/튜닝 ==========

    /**
     * Week 07: 배치 스레드 풀 (멀티스레드 Step + 파티셔닝 공용)
     */
    @Bean
    @JobScope
    public ThreadPoolTaskExecutor batchTaskExecutor(
            @Value("#{jobParameters['threadCount'] ?: 4}") int threadCount) {
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setCorePoolSize(threadCount);
        threadPoolTaskExecutor.setMaxPoolSize(threadCount);
        threadPoolTaskExecutor.setQueueCapacity(Integer.MAX_VALUE);
        threadPoolTaskExecutor.setThreadNamePrefix("batch-thread-");
        threadPoolTaskExecutor.afterPropertiesSet();
        return threadPoolTaskExecutor;
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
     * CSV → Staging Step (Week 06: @JobScope + 동적 파라미터)
     * <p>
     * Week 04: 재시작 지원 (ItemStream)
     * Week 05: Skip/Retry/Listener 추가
     * Week 06: chunkSize, skipLimit, retryLimit을 JobParameters에서 주입
     */
    @Bean
    @JobScope
    public Step csvToStagingStep(JobRepository jobRepository,
                                 SynchronizedItemStreamReader<CustomerCsv> synchronizedCsvReader,
                                 RestartableItemProcessor restartableItemProcessor,
                                 JdbcBatchItemWriter<CustomerStg> customerStgWriter,
                                 ErrorIsolationSkipListener errorIsolationSkipListener,
                                 RetryLoggingListener retryLoggingListener,
                                 ThreadPoolTaskExecutor batchTaskExecutor,
                                 @Value("#{jobParameters['chunkSize'] ?: 100}") int chunkSize,
                                 @Value("#{jobParameters['skipLimit'] ?: 10}") int skipLimit,
                                 @Value("#{jobParameters['retryLimit'] ?: 3}") int retryLimit) {
        return new StepBuilder("csvToStagingStep", jobRepository)
                .<CustomerCsv, CustomerStg>chunk(chunkSize)
                .reader(synchronizedCsvReader)
                .processor(restartableItemProcessor)
                .writer(customerStgWriter)
                .taskExecutor(batchTaskExecutor)
                .faultTolerant()
                .skip(ValidationException.class)
                .skipLimit(skipLimit)
                .skipListener(errorIsolationSkipListener)
                .retry(DeadlockLoserDataAccessException.class)
                .retryLimit(retryLimit)
                .retryListener(retryLoggingListener)
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
                .saveState(false)  // Week 07: 멀티스레드에서는 상태 저장 비활성화
                .build();
    }

    /**
     * Week 07: FlatFileItemReader를 thread-safe하게 래핑
     */
    @Bean
    @StepScope
    public SynchronizedItemStreamReader<CustomerCsv> synchronizedCsvReader(
            FlatFileItemReader<CustomerCsv> customerCsvReader) {
        return new SynchronizedItemStreamReader<>(customerCsvReader);
    }

    // Week 04: customerCsvProcessor는 RestartableItemProcessor로 대체됨
    // RestartableItemProcessor는 @Component + @StepScope로 자동 등록됨

    /**
     * CustomerStg를 customer_stg 테이블에 UPSERT하는 Writer
     * <p>
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
     * Week 07: Partitioned Master Step — 기존 stagingToTargetStep을 파티셔닝으로 교체
     * Bean 이름 유지로 Job Flow 변경 없음
     */
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

    /**
     * Week 07: Slave Step — 파티션별 chunk 처리
     */
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

    /**
     * Week 07: CustomerPartitioner Bean (@StepScope, runDate 주입)
     */
    @Bean
    @StepScope
    public CustomerPartitioner customerPartitioner(
            JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['runDate']}") String runDate) {
        return new CustomerPartitioner(jdbcTemplate, runDate);
    }

    /**
     * Week 07: 파티션별 스테이징 데이터 읽기 (JdbcPagingItemReader)
     * <p>
     * Partitioner가 설정한 minId/maxId를 stepExecutionContext에서 주입받아
     * 해당 id 범위의 유효 레코드만 읽습니다.
     */
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
