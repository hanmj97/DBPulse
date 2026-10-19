package com.dbpulse.domain;

/** Sanitized sampled request wait; never includes wait_resource or SQL text. */
public record WaitObservation(
    int sessionId, String waitType, String waitCategory,
    String resourceKind, long waitMs, long elapsedMs,
    String requestStatus, Integer blockingSessionId
) {}
