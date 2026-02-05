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