package com.dbpulse.domain;

import java.time.Instant;

public record MetricSnapshot(
    Instant collectedAt,
    long activeRequests,
    long blockedRequests,
    long longRunningRequests,
    long maxElapsedMs,
    long totalCpuMs
) {}
