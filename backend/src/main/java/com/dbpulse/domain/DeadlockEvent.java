package com.dbpulse.domain;

import java.time.Instant;
import java.util.List;

/**
 * Deadlock identified by a SHA-256 digest of source XEL file and file offset.
 * The source path and event XML must never be persisted or returned by the API.
 */
public record DeadlockEvent(String eventKey, Instant occurredAt,
                            List<DeadlockParticipant> participants) {
    public DeadlockEvent {
        participants = List.copyOf(participants);
    }
}
