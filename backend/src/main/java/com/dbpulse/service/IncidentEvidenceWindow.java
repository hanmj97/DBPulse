package com.dbpulse.service;

import com.dbpulse.domain.IncidentRecord;
import java.time.Duration;
import java.time.Instant;

/** Time-bounded historical investigation, never substitute live DMV state for past evidence. */
public record IncidentEvidenceWindow(Instant from, Instant to, boolean truncated) {
    private static final Duration MAX_WINDOW = Duration.ofMinutes(15);

    public static IncidentEvidenceWindow of(IncidentRecord incident, Instant now) {
        boolean open = "OPEN".equals(incident.status());
        Instant stop = open ? now
            : incident.resolvedAt() != null ? incident.resolvedAt() : incident.lastObservedAt();
        Instant requestedStart = incident.startedAt().minusSeconds(15);
        Instant floor = stop.minus(MAX_WINDOW);
        Instant from = requestedStart.isBefore(floor) ? floor : requestedStart;
        // Closed events need a short grace period to correlate nearby XE events.
        Instant to = open ? stop : stop.plusSeconds(15);
        return new IncidentEvidenceWindow(from, to, from.isAfter(requestedStart));
    }
}
