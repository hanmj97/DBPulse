package com.dbpulse.persistence;

import com.dbpulse.collector.MssqlWaitCollector.WaitSample;
import com.dbpulse.domain.WaitObservation;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persist both empty and positive samples; expire observations after seven days. */
@Repository
public class WaitRepository {
    private final JdbcTemplate jdbc;

    public WaitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long save(String targetId, WaitSample sample) {
        Long id = jdbc.queryForObject("""
            INSERT INTO wait_samples (target_id, collected_at, observed_count, limit_reached)
            VALUES (?, ?, ?, ?) RETURNING id
            """, Long.class, targetId, Timestamp.from(sample.collectedAt()),
            sample.observations().size(), sample.limitReached());
        if (id == null) throw new IllegalStateException("WAIT_SAMPLE_ID_NOT_RETURNED");
        if (!sample.observations().isEmpty()) {
            jdbc.batchUpdate("""
                INSERT INTO wait_observations
                  (sample_id, session_id, wait_type, wait_category, resource_kind,
                   wait_ms, elapsed_ms, request_status, blocking_session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sample.observations(), sample.observations().size(), (ps, wait) -> {
                    ps.setLong(1, id);
                    ps.setInt(2, wait.sessionId());
                    ps.setString(3, wait.waitType());
                    ps.setString(4, wait.waitCategory());
                    ps.setString(5, wait.resourceKind());
                    ps.setLong(6, wait.waitMs());
                    ps.setLong(7, wait.elapsedMs());
                    ps.setString(8, wait.requestStatus());
                    if (wait.blockingSessionId() == null) {
                        ps.setNull(9, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(9, wait.blockingSessionId());
                    }
                });
        }
        return id;
    }

    public Snapshot readSnapshot(String targetId, long sampleId) {
        List<SampleHeader> headers = jdbc.query("""
            SELECT collected_at, observed_count, limit_reached
            FROM wait_samples WHERE id = ? AND target_id = ?
            """, (rs, i) -> new SampleHeader(
                rs.getTimestamp("collected_at").toInstant(),
                rs.getInt("observed_count"), rs.getBoolean("limit_reached")),
            sampleId, targetId);
        if (headers.isEmpty()) throw new IllegalStateException("WAIT_SAMPLE_MISSING");
        var header = headers.getFirst();
        List<WaitObservation> waits = jdbc.query("""
            SELECT o.session_id, o.wait_type, o.wait_category, o.resource_kind,
                   o.wait_ms, o.elapsed_ms, o.request_status, o.blocking_session_id
            FROM wait_observations o
            JOIN wait_samples s ON s.id = o.sample_id
            WHERE o.sample_id = ? AND s.target_id = ?
            ORDER BY o.wait_ms DESC, o.session_id ASC
            """, (rs, i) -> new WaitObservation(
                rs.getInt("session_id"), rs.getString("wait_type"),
                rs.getString("wait_category"), rs.getString("resource_kind"),
                rs.getLong("wait_ms"), rs.getLong("elapsed_ms"),
                rs.getString("request_status"),
                (Integer) rs.getObject("blocking_session_id")), sampleId, targetId);
        return new Snapshot(header.collectedAt(), header.observedCount(),
            header.limitReached(), waits);
    }

    public List<HistoryEntry> history(String targetId, int limit) {
        return jdbc.query("""
            SELECT s.collected_at, o.session_id, o.wait_type, o.wait_category,
                   o.resource_kind, o.wait_ms, o.elapsed_ms, o.request_status,
                   o.blocking_session_id
            FROM wait_observations o
            JOIN wait_samples s ON s.id = o.sample_id
            WHERE s.target_id = ?
            ORDER BY s.collected_at DESC, o.wait_ms DESC
            LIMIT ?
            """, (rs, i) -> new HistoryEntry(
                rs.getTimestamp("collected_at").toInstant(),
                new WaitObservation(
                    rs.getInt("session_id"), rs.getString("wait_type"),
                    rs.getString("wait_category"), rs.getString("resource_kind"),
                    rs.getLong("wait_ms"), rs.getLong("elapsed_ms"),
                    rs.getString("request_status"),
                    (Integer) rs.getObject("blocking_session_id"))
            ), targetId, limit);
    }

    public int deleteOldSamples() {
        return jdbc.update(
            "DELETE FROM wait_samples WHERE collected_at < ?",
            Timestamp.from(java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS)));
    }

    private record SampleHeader(java.time.Instant collectedAt, int observedCount,
                                boolean limitReached) {}
    public record Snapshot(java.time.Instant collectedAt, int observedCount,
                           boolean limitReached, List<WaitObservation> observations) {}
    public record HistoryEntry(java.time.Instant collectedAt, WaitObservation observation) {}
}
