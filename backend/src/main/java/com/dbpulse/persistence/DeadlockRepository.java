package com.dbpulse.persistence;

import com.dbpulse.domain.DeadlockEvent;
import com.dbpulse.domain.DeadlockParticipant;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Exactly-once-per-XEL-location insert with a per-target unique event key. */
@Repository
public class DeadlockRepository {
    private final JdbcTemplate jdbc;

    public DeadlockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int saveNew(String targetId, List<DeadlockEvent> events) {
        int added = 0;
        for (DeadlockEvent event : events) {
            Integer victim = event.participants().stream()
                .filter(DeadlockParticipant::victim).map(DeadlockParticipant::sessionId)
                .findFirst().orElse(null);
            List<Long> ids = jdbc.query("""
                INSERT INTO deadlock_events
                  (target_id, event_key, occurred_at, local_participant_count, victim_session_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (target_id, event_key) DO NOTHING
                RETURNING id
                """, (rs, rowNum) -> rs.getLong(1),
                targetId, event.eventKey(), Timestamp.from(event.occurredAt()),
                event.participants().size(), victim);
            if (ids.isEmpty()) continue;
            long eventId = ids.getFirst();
            for (DeadlockParticipant participant : event.participants()) {
                jdbc.update("""
                    INSERT INTO deadlock_participants (deadlock_event_id, session_id, was_victim)
                    VALUES (?, ?, ?)
                    ON CONFLICT (deadlock_event_id, session_id) DO NOTHING
                    """, eventId, participant.sessionId(), participant.victim());
            }
            added++;
        }
        return added;
    }

    public List<DeadlockEventView> history(String targetId, int limit) {
        List<DeadlockEventView> events = jdbc.query("""
            SELECT id, occurred_at, local_participant_count, victim_session_id
              FROM deadlock_events
             WHERE target_id = ?
             ORDER BY occurred_at DESC, id DESC LIMIT ?
            """, (rs, index) -> new DeadlockEventView(
                rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getInt("local_participant_count"),
                (Integer) rs.getObject("victim_session_id"), List.of()),
            targetId, limit);
        return events.stream().map(event -> new DeadlockEventView(
            event.id(), event.occurredAt(), event.localParticipantCount(),
            event.victimSessionId(), participantHistory(event.id()))).toList();
    }

    private List<DeadlockParticipant> participantHistory(long eventId) {
        return jdbc.query("""
            SELECT session_id, was_victim
              FROM deadlock_participants WHERE deadlock_event_id = ?
             ORDER BY session_id
            """, (rs, index) -> new DeadlockParticipant(
                rs.getInt("session_id"), rs.getBoolean("was_victim")), eventId);
    }

    public int deleteOld() {
        return jdbc.update("DELETE FROM deadlock_events WHERE occurred_at < ?",
            Timestamp.from(Instant.now().minus(14, java.time.temporal.ChronoUnit.DAYS)));
    }

    public record DeadlockEventView(long id, Instant occurredAt, int localParticipantCount,
                                    Integer victimSessionId, List<DeadlockParticipant> participants) {}
}
