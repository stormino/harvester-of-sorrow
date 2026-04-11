package com.github.stormino.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.dialect.AnsiDialect;
import org.springframework.data.relational.core.dialect.Dialect;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;

@Configuration
@Slf4j
public class DatabaseConfig {

    /**
     * Creates the parent directory for the SQLite database file before the
     * DataSource is initialized (and before Flyway runs migrations).
     * Also enables WAL journal mode outside of any transaction (required by SQLite).
     */
    @Bean
    DataSource dataSource(DataSourceProperties properties) throws Exception {
        String url = properties.getUrl();
        String filePath = url.replace("jdbc:sqlite:", "");
        Path dbPath = Paths.get(filePath);
        if (dbPath.getParent() != null) {
            Files.createDirectories(dbPath.getParent());
            log.info("Ensured SQLite database directory exists: {}", dbPath.getParent());
        }
        DataSource ds = properties.initializeDataSourceBuilder().build();
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            log.info("SQLite WAL journal mode enabled");
        }
        return ds;
    }

    /**
     * Registers AnsiDialect for SQLite, which lacks a dedicated Spring Data JDBC dialect.
     * AnsiDialect covers all standard CRUD and @Modifying @Query operations used in this app.
     */
    @Bean
    Dialect jdbcDialect() {
        return AnsiDialect.INSTANCE;
    }
}
