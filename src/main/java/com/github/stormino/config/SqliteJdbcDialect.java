package com.github.stormino.config;

import org.springframework.data.jdbc.core.dialect.JdbcArrayColumns;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.relational.core.dialect.AnsiDialect;

/**
 * Spring Data JDBC dialect for SQLite, which has none of its own: standard ANSI SQL is enough for
 * the CRUD and {@code @Query} operations this app uses. Being a {@link JdbcDialect} it replaces
 * Spring Boot's auto-detected dialect (which does not recognise SQLite).
 */
final class SqliteJdbcDialect extends AnsiDialect implements JdbcDialect {

    static final SqliteJdbcDialect INSTANCE = new SqliteJdbcDialect();

    private SqliteJdbcDialect() {
    }

    @Override
    public JdbcArrayColumns getArraySupport() {
        return JdbcArrayColumns.unsupported();
    }
}
