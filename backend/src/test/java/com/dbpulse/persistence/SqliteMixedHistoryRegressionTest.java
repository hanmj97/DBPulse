package com.dbpulse.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.dbpulse.domain.MetricSnapshot;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import com.dbpulse.domain.IncidentRecord;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.security.DisabledTargetCredentialStore;
import com.dbpulse.service.LocalTargetService;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * No PostgreSQL/SQL Server connection; no real user SQLite file touched.
 * Simulates imported ISO rows plus live Java repository writes on a fresh DB.
 */
class SqliteMixedHistoryRegressionTest {
    @Test void historicRowsAndNewWritesKeepChronologyAndQueryStoreEquality() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");
            ScriptUtils.executeSqlScript(db, new ClassPathResource("schema-sqlite.sql"));
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(db, true));
            String target = UUID.randomUUID().toString();
            Instant first = Instant.parse("2026-10-21T04:04:56.524781Z");
            Instant second = first.plus(1, ChronoUnit.MICROS);
            assertTrue(MetadataTimestampValue.isSqlite(jdbc));
            assertEquals("2026-10-21T04:04:56.524782+00:00",
                MetadataTimestampValue.value(true, second));
            assertInstanceOf(Timestamp.class, MetadataTimestampValue.value(false, second));

            jdbc.update("""
                INSERT INTO metric_snapshots (target_id, collected_at, active_requests,
                    blocked_requests, long_running_requests, max_elapsed_ms, total_cpu_ms)
                VALUES (?, '2026-10-21T04:04:56.524781+00:00', 1, 0, 0, 10, 5)
                """, target);
            MetricRepository metrics = new MetricRepository(jdbc);
            metrics.save(target, new MetricSnapshot(second, 2, 0, 0, 11, 6));
            var metricHistory = metrics.history(target, 10);
            assertEquals(2, metricHistory.size());
            assertEquals(first, metricHistory.get(0).collectedAt());
            assertEquals(second, metricHistory.get(1).collectedAt());

            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var q = new QueryPerformanceSnapshot(now, 9, 8, 10, 5.1,
                3.2, 2.3, now, "NORMAL", "SELECT");
            QueryPerformanceRepository queries = new QueryPerformanceRepository(jdbc);
            queries.save(target, List.of(q));
            assertEquals(1, queries.latest(target, now, 10).size());
            assertEquals(now, queries.latest(target, now, 10).getFirst().lastExecutedAt());

            var incidents = new IncidentRepository(jdbc);
            incidents.upsert(target, "BLOCKING", first, 3, "test incident");
            incidents.resolve(target, "BLOCKING", second);
            IncidentRecord incident = incidents.latest(target, 10).getFirst();
            assertEquals(first, incident.startedAt());
            assertEquals(second, incident.resolvedAt());

            var block = new BlockingChainRepository(jdbc);
            long blockId = block.save(target,
                new com.dbpulse.collector.MssqlBlockingChainCollector.BlockingSample(second, List.of()));
            assertTrue(blockId > 0);
            var wait = new WaitRepository(jdbc);
            long waitId = wait.save(target,
                new com.dbpulse.collector.MssqlWaitCollector.WaitSample(second, List.of(), false));
            assertEquals(second, wait.readSnapshot(target, waitId).collectedAt());
            assertTrue(jdbc.query("PRAGMA foreign_key_check", (rs, row) -> 1).isEmpty());
        }
    }

    @Test void evidenceWindowAndOlderThanPruningUseIsoUtcBoundaries() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");
            ScriptUtils.executeSqlScript(db, new ClassPathResource("schema-sqlite.sql"));
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(db, true));
            String target = UUID.randomUUID().toString();
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Instant old = now.minus(10, ChronoUnit.DAYS);
            Instant current = now.minus(1, ChronoUnit.HOURS);
            MetricRepository metrics = new MetricRepository(jdbc);
            metrics.save(target, new MetricSnapshot(old, 1, 0, 0, 1, 1));
            metrics.save(target, new MetricSnapshot(current, 2, 1, 0, 1, 1));
            var ev = new IncidentEvidenceRepository(jdbc)
                .read(target, now.minus(2, ChronoUnit.HOURS), now);
            assertEquals(1, ev.metrics().samples());
            assertEquals(1, ev.metrics().peakBlocked());
            var queries = new QueryPerformanceRepository(jdbc);
            queries.save(target, List.of(new QueryPerformanceSnapshot(old, 1, 1, 1,
                1.0, 1.0, 1.0, old, "NORMAL", "SELECT")));
            queries.save(target, List.of(new QueryPerformanceSnapshot(current, 2, 2, 1,
                1.0, 1.0, 1.0, current, "NORMAL", "SELECT")));
            assertEquals(1, queries.purgeOlderThanSevenDays());
            assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM query_performance_snapshots", Integer.class));
            assertEquals(0, new IncidentWaitEvidenceRepository(jdbc)
                .read(target, now.minus(2, ChronoUnit.HOURS), now).sampleCount());
        }
    }
}
