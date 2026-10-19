package com.dbpulse.persistence;

import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QueryPerformanceRepository {
    private final JdbcTemplate jdbc;

    public QueryPerformanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(String targetId, List<QueryPerformanceSnapshot> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
            INSERT INTO query_performance_snapshots
              (target_id, collected_at, query_id, plan_id, execution_count,
               avg_duration_ms, avg_cpu_ms, avg_logical_reads_pages,
               last_executed_at, diagnosis_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, rows, rows.size(), (ps, row) -> {
                ps.setString(1, targetId);
                ps.setTimestamp(2, Timestamp.from(row.collectedAt()));
                ps.setLong(3, row.queryId());
                ps.setLong(4, row.planId());
                ps.setLong(5, row.executionCount());
                ps.setDouble(6, row.avgDurationMs());
                ps.setDouble(7, row.avgCpuMs());
                ps.setDouble(8, row.avgLogicalReadsPages());
                ps.setTimestamp(9, Timestamp.from(row.lastExecutedAt()));
                ps.setString(10, row.diagnosisCode());
            });
    }

    /** Bounded local storage; call from the daily scheduled cleanup. */
    public int purgeOlderThanSevenDays() {
        return jdbc.update("DELETE FROM query_performance_snapshots WHERE collected_at < now() - INTERVAL '7 days'");
    }

    /** Only fresh rows from the most recent successful run; no stale 'live' values. */
    public List<QueryPerformanceSnapshot> latest(String targetId, java.time.Instant sampledAt, int limit) {
        return jdbc.query("""
            SELECT collected_at, query_id, plan_id, execution_count, avg_duration_ms,
                   avg_cpu_ms, avg_logical_reads_pages, last_executed_at, diagnosis_code
            FROM query_performance_snapshots
            WHERE target_id = ?
              AND collected_at = ?
              AND collected_at > now() - INTERVAL '2 minutes' 
            ORDER BY avg_duration_ms DESC
            LIMIT ?
            """, (rs, i) -> new QueryPerformanceSnapshot(
                rs.getTimestamp("collected_at").toInstant(),
                rs.getLong("query_id"), rs.getLong("plan_id"),
                rs.getLong("execution_count"), rs.getDouble("avg_duration_ms"),
                rs.getDouble("avg_cpu_ms"), rs.getDouble("avg_logical_reads_pages"),
                rs.getTimestamp("last_executed_at").toInstant(),
                rs.getString("diagnosis_code")
            ), targetId, Timestamp.from(sampledAt), limit);
    }
}
