package com.dbpulse.persistence;

import com.dbpulse.domain.IncidentRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** No SQL text, user data or credentials are persisted. */
@Repository
public class IncidentRepository {
    private final JdbcTemplate jdbc;
    public IncidentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void upsert(String target, String type, Instant at, long value, String message) {
        jdbc.update("""
            INSERT INTO incidents (target_id, type, severity, status, started_at,
                                   last_observed_at, observed_value, summary)
            VALUES (?, ?, 'WARNING', 'OPEN', ?, ?, ?, ?)
            ON CONFLICT (target_id, type) WHERE status = 'OPEN'
            DO UPDATE SET last_observed_at = EXCLUDED.last_observed_at,
                          observed_value = EXCLUDED.observed_value
            """, target, type, Timestamp.from(at), Timestamp.from(at), value, message);
    }

    public void resolve(String target, String type, Instant at) {
        jdbc.update("""
            UPDATE incidents SET status = 'RESOLVED', resolved_at = ?
            WHERE target_id = ? AND type = ? AND status = 'OPEN'
            """, Timestamp.from(at), target, type);
    }

    public Optional<IncidentRecord> byId(String target, long id) {
        List<IncidentRecord> rows = jdbc.query("""
            SELECT id, target_id, type, severity, status, started_at, last_observed_at,
                   resolved_at, observed_value, summary
              FROM incidents WHERE target_id = ? AND id = ?
            """, (rs, i) -> {
                Timestamp resolved = rs.getTimestamp("resolved_at");
                return new IncidentRecord(rs.getLong("id"), rs.getString("target_id"),
                    rs.getString("type"), rs.getString("severity"), rs.getString("status"),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("last_observed_at").toInstant(),
                    resolved == null ? null : resolved.toInstant(),
                    rs.getLong("observed_value"), rs.getString("summary"));
            }, target, id);
        return rows.stream().findFirst();
    }

    public List<IncidentRecord> latest(String target, int limit) {
        return jdbc.query("""
            SELECT id, target_id, type, severity, status, started_at, last_observed_at,
                   resolved_at, observed_value, summary FROM incidents
            WHERE target_id = ?
            ORDER BY CASE WHEN status='OPEN' THEN 0 ELSE 1 END,
                     last_observed_at DESC, id DESC LIMIT ?
            """, (rs, i) -> {
                Timestamp resolved = rs.getTimestamp("resolved_at");
                return new IncidentRecord(rs.getLong("id"), rs.getString("target_id"),
                    rs.getString("type"), rs.getString("severity"), rs.getString("status"),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("last_observed_at").toInstant(),
                    resolved == null ? null : resolved.toInstant(),
                    rs.getLong("observed_value"), rs.getString("summary"));
            }, target, limit);
    }
}
