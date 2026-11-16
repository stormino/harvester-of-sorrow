package com.github.stormino.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;

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
        Path legacy = legacyDatabase(dbPath);
        if (legacy != null) {
            log.warn("Database {} not found; using the pre-4.0 database {} instead. "
                    + "Rename it (or set SQLITE_DB_PATH) to silence this warning.", dbPath, legacy);
            dbPath = legacy;
            properties.setUrl("jdbc:sqlite:" + legacy);
        }
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

    /** Pre-4.0 (VixsrcDownloader) default database location. */
    static final Path LEGACY_DB = Paths.get("data", "vixsrc.db");

    /**
     * Returns the legacy {@code data/vixsrc.db} when the configured database file does not
     * exist yet and the legacy one does; otherwise {@code null}.
     */
    static Path legacyDatabase(Path configured) {
        if (configured.toString().isBlank() || configured.toString().startsWith(":memory:")
                || Files.exists(configured)) {
            return null;
        }
        return Files.isRegularFile(LEGACY_DB) && !LEGACY_DB.toAbsolutePath().equals(configured.toAbsolutePath())
                ? LEGACY_DB : null;
    }

    /** ANSI dialect for SQLite, which lacks a dedicated Spring Data JDBC dialect. */
    @Bean
    JdbcDialect jdbcDialect() {
        return SqliteJdbcDialect.INSTANCE;
    }
}
