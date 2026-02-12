package com.test.batchstudy.config;

import com.test.batchstudy.domain.Customer;
import com.test.batchstudy.domain.CustomerStg;
import com.test.batchstudy.partitioner.CustomerPartitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Map;

import static com.test.batchstudy.constants.ValidationSql.VALID_RECORD_FILTER;

/**
 * Staging → Target Step 설정 (파티셔닝)
 * <p>
 * Week 03: 기본 UPSERT
 * Week 07: 파티셔닝 (Master-Slave)
 * Week 08: Config 분리
 */
@Configuration
@RequiredArgsConstructor
public class StagingToTargetStepConfig {

    private final DataSource dataSource;

    /**
     * Partitioned Master Step
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
     * Slave Step — 파티션별 chunk 처리
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
                "run_date = :runDate AND " + VALID_RECORD_FILTER + " AND id >= :minId AND id <= :maxId"
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

    @Bean
    public ItemProcessor<CustomerStg, Customer> stagingToCustomerProcessor() {
        return Customer::fromStaging;
    }

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
