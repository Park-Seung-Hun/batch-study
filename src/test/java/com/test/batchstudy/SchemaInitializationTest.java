package com.test.batchstudy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SchemaInitializationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void metaTablesAreCreated() {
        List<String> tableNames = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );

        Set<String> names = tableNames.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        assertThat(names).containsAll(Set.of(
                "batch_job_instance",
                "batch_job_execution",
                "batch_job_execution_params",
                "batch_step_execution",
                "batch_step_execution_context",
                "batch_job_execution_context"
        ));
    }

    @Test
    void businessTablesAreCreated() {
        List<String> tableNames = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );

        Set<String> names = tableNames.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        assertThat(names).containsAll(Set.of(
                "customer_stg",
                "customer",
                "customer_err",
                "customer_daily_stats"
        ));
    }
}
