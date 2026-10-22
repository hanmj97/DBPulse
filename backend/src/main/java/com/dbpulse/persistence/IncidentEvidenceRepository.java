package com.dbpulse.persistence;

import com.dbpulse.persistence.ImportedTimestampReader;
import com.dbpulse.persistence.MetadataTimestampValue;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL read-only time correlation, scoped to one configured target. */
@Repository
public class IncidentEvidenceRepository {
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


    public IncidentEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Evidence read(String targetId, Instant from, Instant to) {
        Object begin = MetadataTimestampValue.value(sqlite(), from);
        Object end = MetadataTimestampValue.value(sqlite(), to);
        MetricAggregate aggregate = jdbc.queryForObject("""
            SELECT COUNT(*) AS samples,
                   COALESCE(MAX(blocked_requests),0) AS peak_blocked,
                   COALESCE(MAX(long_running_requests),0) AS peak_long_running,
                   COALESCE(MAX(max_elapsed_ms),0) AS peak_elapsed_ms
              FROM metric_snapshots
             WHERE target_id = ? AND collected_at >= ? AND collected_at <= ?
            """, (rs, i) -> new MetricAggregate(
                rs.getLong("samples"), rs.getLong("peak_blocked"),
                rs.getLong("peak_long_running"), rs.getLong("peak_elapsed_ms")),
            targetId, begin, end);

        List<BlockingObservation> blocking = jdbc.query("""
            SELECT s.collected_at, e.blocked_session_id, e.blocking_session_id,
                   e.wait_type, e.wait_ms, e.root_candidate_session_id, e.chain_depth
              FROM blocking_chain_edges e
              JOIN blocking_chain_samples s ON s.id = e.sample_id
             WHERE s.target_id = ? AND s.collected_at >= ? AND s.collected_at <= ?
             ORDER BY s.collected_at DESC, e.wait_ms DESC
             LIMIT 12
            """, (rs, i) -> new BlockingObservation(
                ImportedTimestampReader.instant(rs, "collected_at"),
                rs.getInt("blocked_session_id"), rs.getInt("blocking_session_id"),
                rs.getString("wait_type"), rs.getLong("wait_ms"),
                (Integer) rs.getObject("root_candidate_session_id"),
                rs.getInt("chain_depth")), targetId, begin, end);

        List<DeadlockObservation> deadlocks = jdbc.query("""
            SELECT id, occurred_at, local_participant_count, victim_session_id
              FROM deadlock_events
             WHERE target_id = ? AND occurred_at >= ? AND occurred_at <= ?
             ORDER BY occurred_at DESC, id DESC
             LIMIT 8
            """, (rs, i) -> new DeadlockObservation(
                rs.getLong("id"), ImportedTimestampReader.instant(rs, "occurred_at"),
                rs.getInt("local_participant_count"),
                (Integer) rs.getObject("victim_session_id")), targetId, begin, end);

        return new Evidence(aggregate, blocking, deadlocks);
    }

    public record MetricAggregate(long samples, long peakBlocked,
                                  long peakLongRunning, long peakElapsedMs) {}
    public record BlockingObservation(Instant collectedAt, int blockedSessionId,
                                      int blockingSessionId, String waitType,
                                      long waitMs, Integer rootCandidateSessionId,
                                      int chainDepth) {}
    public record DeadlockObservation(long id, Instant occurredAt,
                                      int localParticipantCount, Integer victimSessionId) {}
    public record Evidence(MetricAggregate metrics, List<BlockingObservation> blocking,
                           List<DeadlockObservation> deadlocks) {}
}
