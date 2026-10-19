package com.dbpulse.domain;

/**
 * One observed request waiting on a positive SQL Server blocking_session_id.
 * Root is only a candidate within this bounded DMV sample, not proven root cause.
 */
public record BlockingEdge(
    int blockedSessionId, int blockingSessionId,
    String waitType, long waitMs, long elapsedMs,
    String blockedStatus, String blockerStatus, int blockerOpenTransactions,
    Integer rootCandidateSessionId, int chainDepth, boolean cycleDetected
) {}
