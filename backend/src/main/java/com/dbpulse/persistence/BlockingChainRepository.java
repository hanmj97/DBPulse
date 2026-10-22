package com.dbpulse.persistence;

import com.dbpulse.collector.MssqlBlockingChainCollector.BlockingSample;
import com.dbpulse.domain.BlockingEdge;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BlockingChainRepository {
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


    public BlockingChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Persist a sample even with zero edges to distinguish healthy from stale/unknown. */
    @Transactional
    public long save(String targetId, BlockingSample sample) {
        Long id = jdbc.queryForObject("""
            INSERT INTO blocking_chain_samples (target_id, collected_at, edge_count)
            VALUES (?, ?, ?) RETURNING id
            """, Long.class, targetId, MetadataTimestampValue.value(sqlite(), sample.collectedAt()), sample.edges().size());
        if (id == null) throw new IllegalStateException("SAMPLE_ID_NOT_RETURNED");
        if (!sample.edges().isEmpty()) {
            jdbc.batchUpdate("""
                INSERT INTO blocking_chain_edges
                  (sample_id, blocked_session_id, blocking_session_id,
                   wait_type, wait_ms, elapsed_ms, blocked_status, blocker_status,
                   blocker_open_transactions, root_candidate_session_id,
                   chain_depth, cycle_detected)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sample.edges(), sample.edges().size(), (ps, edge) -> {
                    ps.setLong(1, id);
                    ps.setInt(2, edge.blockedSessionId());
                    ps.setInt(3, edge.blockingSessionId());
                    ps.setString(4, edge.waitType());
                    ps.setLong(5, edge.waitMs());
                    ps.setLong(6, edge.elapsedMs());
                    ps.setString(7, edge.blockedStatus());
                    ps.setString(8, edge.blockerStatus());
                    ps.setInt(9, edge.blockerOpenTransactions());
                    if (edge.rootCandidateSessionId() == null) {
                        ps.setNull(10, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(10, edge.rootCandidateSessionId());
                    }
                    ps.setInt(11, edge.chainDepth());
                    ps.setBoolean(12, edge.cycleDetected());
                });
        }
        return id;
    }

    public List<BlockingEdge> edges(long sampleId, String targetId) {
        return jdbc.query("""
            SELECT e.blocked_session_id, e.blocking_session_id, e.wait_type,
                   e.wait_ms, e.elapsed_ms, e.blocked_status, e.blocker_status,
                   e.blocker_open_transactions, e.root_candidate_session_id,
                   e.chain_depth, e.cycle_detected
              FROM blocking_chain_edges e
              JOIN blocking_chain_samples s ON s.id = e.sample_id
             WHERE s.id = ? AND s.target_id = ?
             ORDER BY e.wait_ms DESC, e.blocked_session_id
            """, (rs, i) -> new BlockingEdge(
                rs.getInt("blocked_session_id"), rs.getInt("blocking_session_id"),
                rs.getString("wait_type"), rs.getLong("wait_ms"),
                rs.getLong("elapsed_ms"), rs.getString("blocked_status"),
                rs.getString("blocker_status"), rs.getInt("blocker_open_transactions"),
                (Integer) rs.getObject("root_candidate_session_id"),
                rs.getInt("chain_depth"), rs.getBoolean("cycle_detected")
            ), sampleId, targetId);
    }

    /** Prior observed waiting edges, even if current status is healthy. */
    public List<BlockingHistoryEntry> history(String targetId, int limit) {
        return jdbc.query("""
            SELECT s.collected_at,
                   e.blocked_session_id, e.blocking_session_id,
                   e.wait_type, e.wait_ms, e.elapsed_ms, e.blocked_status,
                   e.blocker_status, e.blocker_open_transactions,
                   e.root_candidate_session_id, e.chain_depth, e.cycle_detected
              FROM blocking_chain_edges e
              JOIN blocking_chain_samples s ON s.id = e.sample_id
             WHERE s.target_id = ?
             ORDER BY s.collected_at DESC, e.wait_ms DESC
             LIMIT ?
            """, (rs, i) -> new BlockingHistoryEntry(
                ImportedTimestampReader.instant(rs, "collected_at"),
                new BlockingEdge(
                    rs.getInt("blocked_session_id"), rs.getInt("blocking_session_id"),
                    rs.getString("wait_type"), rs.getLong("wait_ms"),
                    rs.getLong("elapsed_ms"), rs.getString("blocked_status"),
                    rs.getString("blocker_status"), rs.getInt("blocker_open_transactions"),
                    (Integer) rs.getObject("root_candidate_session_id"),
                    rs.getInt("chain_depth"), rs.getBoolean("cycle_detected"))
                ), targetId, limit);
    }

    public record BlockingHistoryEntry(java.time.Instant collectedAt, BlockingEdge edge) {}

    public int deleteOldSamples() {
        // Child rows cascade-delete automatically.
        return jdbc.update("""
            DELETE FROM blocking_chain_samples
             WHERE collected_at < ?
            """, MetadataTimestampValue.value(sqlite(), java.time.Instant.now().minus(7,
                java.time.temporal.ChronoUnit.DAYS)));
    }
}
