package com.dbpulse.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Historical wait observations scoped to a single configured target and
 * the incident's evidence window. Rows are samples, not unique wait events.
 */
@Repository
public class IncidentWaitEvidenceRepository {
    private final JdbcTemplate jdbc;

    public IncidentWaitEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WaitEvidence read(String targetId, Instant from, Instant to) {
        Timestamp begin = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);

        Long sampleCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM wait_samples
            WHERE target_id = ? AND collected_at >= ? AND collected_at <= ?
            """, Long.class, targetId, begin, end);

        Long truncatedSamples = jdbc.queryForObject("""
            SELECT COUNT(*) FROM wait_samples
            WHERE target_id = ? AND collected_at >= ? AND collected_at <= ?
              AND limit_reached = TRUE
            """, Long.class, targetId, begin, end);

        List<WaitCategorySummary> categories = jdbc.query("""
            SELECT o.wait_category, COUNT(*) AS sampled_count,
                   MAX(o.wait_ms) AS peak_wait_ms
            FROM wait_observations o
            JOIN wait_samples s ON s.id = o.sample_id
            WHERE s.target_id = ? AND s.collected_at >= ? AND s.collected_at <= ?
            GROUP BY o.wait_category
            ORDER BY sampled_count DESC, o.wait_category
            LIMIT 10
            """, (rs, index) -> new WaitCategorySummary(
                rs.getString("wait_category"), rs.getLong("sampled_count"),
                rs.getLong("peak_wait_ms")), targetId, begin, end);

        List<HistoricalWait> latest = jdbc.query("""
            SELECT s.collected_at, o.session_id, o.wait_type, o.wait_category,
                   o.resource_kind, o.wait_ms, o.blocking_session_id
            FROM wait_observations o
            JOIN wait_samples s ON s.id = o.sample_id
            WHERE s.target_id = ? AND s.collected_at >= ? AND s.collected_at <= ?
            ORDER BY s.collected_at DESC, o.wait_ms DESC, o.session_id
            LIMIT 12
            """, (rs, index) -> new HistoricalWait(
                rs.getTimestamp("collected_at").toInstant(), rs.getInt("session_id"),
                rs.getString("wait_type"), rs.getString("wait_category"),
                rs.getString("resource_kind"), rs.getLong("wait_ms"),
                (Integer) rs.getObject("blocking_session_id")
            ), targetId, begin, end);

        return new WaitEvidence(
            sampleCount == null ? 0 : sampleCount,
            truncatedSamples == null ? 0 : truncatedSamples,
            categories.stream().mapToLong(WaitCategorySummary::sampledCount).sum(),
            List.copyOf(categories), List.copyOf(latest));
    }

    public record WaitCategorySummary(String category, long sampledCount, long peakWaitMs) {}
    public record HistoricalWait(Instant collectedAt, int sessionId,
                                 String waitType, String waitCategory,
                                 String resourceKind, long waitMs, Integer blockingSessionId) {}
    public record WaitEvidence(long sampleCount, long truncatedSampleCount,
                               long sampledWaitCount,
                               List<WaitCategorySummary> categories,
                               List<HistoricalWait> recent) {}
}
