package com.dbpulse.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbpulse.domain.QueryPerformanceSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Regression for Phase19 Windows. In production SQLite's Hikari pool is ONE.
 * A nested JdbcTemplate dialect lookup inside batchUpdate's setter tries to
 * borrow a second connection and fails (old tests used SingleConnectionDataSource).
 * Uses only a disposable local test DB, never a user's DBPulse data.
 */
class QueryPerformanceSingleConnectionSqliteTest {
    @TempDir Path scratch;

    @Test void firstQueryStoreBatchSaveWorksWithOnlyOnePooledSqliteConnection() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + scratch.resolve("querystore-sample.sqlite"));
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(500);
        config.setPoolName("phase19-sqlite-single-connection-test");
        try (var pool = new HikariDataSource(config)) {
            try (var connection = pool.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-sqlite.sql"));
            }

            var jdbc = new JdbcTemplate(pool);
            var repo = new QueryPerformanceRepository(jdbc);
            String target = "cold-query-store-target";
            Instant sampledAt = Instant.parse("2026-10-22T02:42:01Z");
            var rows = List.of(
                new QueryPerformanceSnapshot(sampledAt, 101, 201, 5, 15.5,
                    4.2, 17.0, sampledAt, "NORMAL", "OTHER_OR_UNKNOWN"),
                new QueryPerformanceSnapshot(sampledAt, 102, 202, 8, 4.0,
                    1.8, 7.0, sampledAt, "NORMAL", "DBPULSE_PATTERN")
            );

            // IMPORTANT: no previous repo call should initialize sqliteDialect.
            repo.save(target, rows);
            var saved = repo.latest(target, sampledAt, 10);
            assertEquals(2, saved.size());
            assertTrue(saved.stream().anyMatch(r -> r.queryId() == 101));
            assertTrue(saved.stream().anyMatch(r -> r.queryId() == 102));
            assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM query_performance_snapshots WHERE target_id = ?",
                Integer.class, target));
        }
    }
}
