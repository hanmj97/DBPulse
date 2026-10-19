package com.dbpulse.domain;

import java.time.Instant;

public record IncidentRecord(
    long id, String targetId, String type, String severity, String status,
    Instant startedAt, Instant lastObservedAt, Instant resolvedAt,
    long observedValue, String summary
) {}
