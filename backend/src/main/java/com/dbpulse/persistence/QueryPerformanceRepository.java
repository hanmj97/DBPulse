package com.dbpulse.persistence;

import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QueryPerformanceRepository {
    private final JdbcTemplate jdbc;
    private volatile Boolean sqliteDialect;
    private boolean sqlite() {
        Boolean value = sqliteDialect;
        if (value == null) {
            value = MetadataTimestampValue.isSqlite(jdbc);
            sqliteDialect = value;
        }
        return value;
    }


    public QueryPerformanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(String targetId, List<QueryPerformanceSnapshot> rows) {
        if (rows.isEmpty()) return;
        // IMPORTANT: resolve the JDBC dialect BEFORE starting batchUpdate.
        // The batch setter runs while Hikari holds its only SQLite connection
        // (maximum-pool-size=1 on Windows). sqlite() internally uses JdbcTemplate
        // to inspect metadata; calling it inside the setter would request a
        // SECOND connection and eventually time out. SingleConnectionDataSource
        // tests masked this cold-start deadlock.
        final boolean sqliteBatch = sqlite();
        jdbc.batchUpdate("""
            INSERT INTO query_performance_snapshots
              (target_id, collected_at, query_id, plan_id, execution_count,
               avg_duration_ms, avg_cpu_ms, avg_logical_reads_pages,
               last_executed_at, diagnosis_code, source_category)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, rows, rows.size(), (ps, row) -> {
                ps.setString(1, targetId);
                ps.setObject(2, MetadataTimestampValue.value(sqliteBatch, row.collectedAt()));
                ps.setLong(3, row.queryId());
                ps.setLong(4, row.planId());
                ps.setLong(5, row.executionCount());
                ps.setDouble(6, row.avgDurationMs());
                ps.setDouble(7, row.avgCpuMs());
                ps.setDouble(8, row.avgLogicalReadsPages());
                ps.setObject(9, MetadataTimestampValue.value(sqliteBatch, row.lastExecutedAt()));
                ps.setString(10, row.diagnosisCode());
                ps.setString(11, row.sourceCategory());
            });
    }

    /** Bounded local storage; call from the daily scheduled cleanup. */
    public int purgeOlderThanSevenDays() {
        return jdbc.update("DELETE FROM query_performance_snapshots WHERE collected_at < ?",
            MetadataTimestampValue.value(sqlite(), java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS)));
    }

    /** Rows from one exact collection snapshot; controller enforces adaptive freshness. */
    public List<QueryPerformanceSnapshot> latest(String targetId, java.time.Instant sampledAt, int limit) {
        return jdbc.query("""
            SELECT collected_at, query_id, plan_id, execution_count, avg_duration_ms,
                   avg_cpu_ms, avg_logical_reads_pages, last_executed_at, diagnosis_code, source_category
            FROM query_performance_snapshots
            WHERE target_id = ?
              AND collected_at = ?
            ORDER BY avg_duration_ms DESC
            LIMIT ?
            """, (rs, i) -> new QueryPerformanceSnapshot(
                ImportedTimestampReader.instant(rs, "collected_at"),
                rs.getLong("query_id"), rs.getLong("plan_id"),
                rs.getLong("execution_count"), rs.getDouble("avg_duration_ms"),
                rs.getDouble("avg_cpu_ms"), rs.getDouble("avg_logical_reads_pages"),
                ImportedTimestampReader.instant(rs, "last_executed_at"),
                rs.getString("diagnosis_code"), rs.getString("source_category")
            ), targetId, MetadataTimestampValue.value(sqlite(), sampledAt), limit);
    }
}
