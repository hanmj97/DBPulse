package com.dbpulse.domain;

import java.time.Instant;

/** A Query Store measurement for one (query_id, plan_id) in the last 15 minutes.
 * No SQL text, bind values or plan XML are collected or persisted here.
 * Cumulative interval values, NOT executions performed since the previous poll.
 */
public record QueryPerformanceSnapshot(
    Instant collectedAt,
    long queryId,
    long planId,
    long executionCount,
    double avgDurationMs,
    double avgCpuMs,
    double avgLogicalReadsPages,
    Instant lastExecutedAt,
    String diagnosisCode
) {}
