package com.test.batchstudy.config;

import com.test.batchstudy.domain.CustomerCsv;
import com.test.batchstudy.domain.CustomerStg;
import com.test.batchstudy.listener.ErrorIsolationSkipListener;
import com.test.batchstudy.listener.RetryLoggingListener;
import com.test.batchstudy.processor.RestartableItemProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.item.support.SynchronizedItemStreamReader;
import org.springframework.batch.infrastructure.item.validator.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;

/**
 * CSV → Staging Step 설정
 * <p>
 * Week 02: 기본 CSV 읽기
 * Week 04: 재시작 지원 (ItemStream)
 * Week 05: Skip/Retry/Listener
 * Week 06: 동적 chunkSize/skipLimit/retryLimit
 * Week 07: 멀티스레드 (SynchronizedItemStreamReader)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CsvToStagingStepConfig {

    private final DataSource dataSource;

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

    @Bean
    @StepScope
    public FlatFileItemReader<CustomerCsv> customerCsvReader(
            @Value("#{jobParameters['inputFile']}") String inputFile) {
        log.info("Creating customerCsvReader with inputFile: {}", inputFile);

        return new FlatFileItemReaderBuilder<CustomerCsv>()
                .name("customerCsvReader")
                .resource(new FileSystemResource(inputFile))
                .encoding("UTF-8")
                .linesToSkip(1)
                .delimited()
                .names("customerId", "email", "name", "phone")
                .targetType(CustomerCsv.class)
                .saveState(false)
                .build();
    }

    @Bean
    @StepScope
    public SynchronizedItemStreamReader<CustomerCsv> synchronizedCsvReader(
            FlatFileItemReader<CustomerCsv> customerCsvReader) {
        return new SynchronizedItemStreamReader<>(customerCsvReader);
    }

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
}
