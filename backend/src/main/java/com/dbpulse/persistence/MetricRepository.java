package com.dbpulse.persistence;

import com.dbpulse.domain.MetricSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricRepository {
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


    public MetricRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(String targetId, MetricSnapshot item) {
        jdbc.update("""
            INSERT INTO metric_snapshots
              (target_id, collected_at, active_requests, blocked_requests,
               long_running_requests, max_elapsed_ms, total_cpu_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, targetId, MetadataTimestampValue.value(sqlite(), item.collectedAt()), item.activeRequests(),
            item.blockedRequests(), item.longRunningRequests(), item.maxElapsedMs(), item.totalCpuMs());
    }

    public List<MetricSnapshot> history(String targetId, int limit) {
        List<MetricSnapshot> items = jdbc.query("""
            SELECT collected_at, active_requests, blocked_requests,
                   long_running_requests, max_elapsed_ms, total_cpu_ms
            FROM metric_snapshots WHERE target_id = ?
            ORDER BY collected_at DESC LIMIT ?
            """, (rs, n) -> map(rs), targetId, limit);
        Collections.reverse(items);
        return items;
    }

    private static MetricSnapshot map(ResultSet rs) throws SQLException {
        return new MetricSnapshot(
            ImportedTimestampReader.instant(rs, "collected_at"),
            rs.getLong("active_requests"), rs.getLong("blocked_requests"),
            rs.getLong("long_running_requests"), rs.getLong("max_elapsed_ms"),
            rs.getLong("total_cpu_ms"));
    }
}
