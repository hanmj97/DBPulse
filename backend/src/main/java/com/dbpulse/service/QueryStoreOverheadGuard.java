package com.dbpulse.service;

import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * Local prototype: conservative Query Store poll scheduler guard.
 * Observes this collector's JDBC wall time and Query Store per-execution
 * AVERAGES for internal SQL. Neither metric is a measurement of whole-server
 * CPU utilisation or total observer cost per minute.
 *
 * Only the Query Store collector is throttled. The five-second DMV incident
 * detector and the on-demand plan/text tools are deliberately unaffected.
 */
public final class QueryStoreOverheadGuard {
    public static final long MAX_COLLECTOR_WALL_MS = 2_000L;
    public static final double MAX_INTERNAL_AVG_CPU_MS = 100.0;
    public static final double MAX_INTERNAL_AVG_LOGICAL_READS = 1_000.0;
    private static final String NOTE =
        "수집기 JDBC 소요시간 및 Query Store에 기록된 DBPulse 내부 SQL의 실행당 평균 CPU·논리 읽기 징후입니다. " +
        "고객 DB 전체 CPU·I/O 또는 실제 사용자 영향도를 측정한 값이 아닙니다. " +
        "내부 SQL 식별은 문장 패턴 기반 추정입니다. Query Store 수집만 조절하며 5초 장애 감지는 유지합니다.";

    private final long baseIntervalMs;
    private boolean throttled;
    private boolean skipNextTick;
    private int expensiveStreak;
    private int recoveryStreak;
    private int failureStreak;
    private long skippedCycles;
    private long totalCollectorAttempts;
    private long successfulCollections;
    private long failedCollections;
    private long cumulativeJdbcWallMs;
    private long lastCollectorWallMs;
    private int internalSampleCount;
    private double peakInternalAvgCpuMs;
    private double peakInternalAvgReadsPages;
    private Instant lastObservedAt;
    private String reason = "NOT_MEASURED";

    public QueryStoreOverheadGuard(long baseIntervalMs) {
        if (baseIntervalMs < 5_000L || baseIntervalMs > 300_000L)
            throw new IllegalArgumentException("Query Store interval must be 5000..300000ms");
        this.baseIntervalMs = baseIntervalMs;
    }

    /**
     * Called once at each scheduled Query Store polling tick, before JDBC.
     * A single scheduled tick is skipped when throttled, yielding effective
     * 2x base cadence; no additional background worker is created.
     */
    public synchronized boolean shouldSkipScheduledTick() {
        if (!throttled) return false;
        if (skipNextTick) {
            skipNextTick = false;
            skippedCycles++;
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess(long jdbcWallMs,
                                           List<QueryPerformanceSnapshot> captured,
                                           Instant now) {
        totalCollectorAttempts++;
        successfulCollections++;
        cumulativeJdbcWallMs += Math.max(0, jdbcWallMs);
        lastObservedAt = now;
        lastCollectorWallMs = Math.max(0, jdbcWallMs);
        internalSampleCount = 0;
        peakInternalAvgCpuMs = 0;
        peakInternalAvgReadsPages = 0;
        for (var row : captured) {
            if (!"DBPULSE_PATTERN".equals(row.sourceCategory())) continue;
            internalSampleCount++;
            peakInternalAvgCpuMs = Math.max(peakInternalAvgCpuMs, row.avgCpuMs());
            peakInternalAvgReadsPages = Math.max(peakInternalAvgReadsPages, row.avgLogicalReadsPages());
        }
        failureStreak = 0;
        boolean slowWall = lastCollectorWallMs >= MAX_COLLECTOR_WALL_MS;
        boolean highCpu = internalSampleCount > 0 && peakInternalAvgCpuMs >= MAX_INTERNAL_AVG_CPU_MS;
        boolean highReads = internalSampleCount > 0 &&
            peakInternalAvgReadsPages >= MAX_INTERNAL_AVG_LOGICAL_READS;
        boolean expensive = slowWall || highCpu || highReads;
        if (expensive) {
            expensiveStreak++;
            recoveryStreak = 0;
            reason = slowWall ? "COLLECTOR_WALL_TIME_HIGH" :
                highCpu ? "INTERNAL_AVG_CPU_HIGH" : "INTERNAL_AVG_LOGICAL_READS_HIGH";
            if (expensiveStreak >= 2) throttled = true;
        } else {
            expensiveStreak = 0;
            recoveryStreak++;
            reason = internalSampleCount == 0 ?
                "NO_INTERNAL_QUERY_STORE_SAMPLES" : "WITHIN_HEURISTIC_LIMITS";
            if (throttled && recoveryStreak >= 3) {
                throttled = false;
                skipNextTick = false;
            }
        }
        if (throttled) skipNextTick = true;
    }

    public synchronized void recordFailure(long jdbcWallMs, Instant now) {
        totalCollectorAttempts++;
        failedCollections++;
        cumulativeJdbcWallMs += Math.max(0, jdbcWallMs);
        lastObservedAt = now;
        lastCollectorWallMs = Math.max(0, jdbcWallMs);
        failureStreak++;
        recoveryStreak = 0;
        expensiveStreak = 0;
        reason = "QUERY_STORE_COLLECTION_FAILED";
        // Avoid repeatedly hammering an unavailable Query Store. Independent
        // 5s DMV collection continues even while Query Store is in backoff.
        if (failureStreak >= 2) {
            throttled = true;
            skipNextTick = true;
        }
    }

    public synchronized void reset() {
        throttled = false;
        skipNextTick = false;
        expensiveStreak = 0;
        recoveryStreak = 0;
        failureStreak = 0;
        reason = "NOT_CONFIGURED";
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            throttled ? "THROTTLED" : "NORMAL",
            reason,
            baseIntervalMs,
            throttled ? baseIntervalMs * 2 : baseIntervalMs,
            lastCollectorWallMs,
            lastObservedAt,
            internalSampleCount,
            peakInternalAvgCpuMs,
            peakInternalAvgReadsPages,
            expensiveStreak,
            recoveryStreak,
            failureStreak,
            skippedCycles,
            totalCollectorAttempts,
            successfulCollections,
            failedCollections,
            cumulativeJdbcWallMs,
            NOTE
        );
    }

    public long allowedAgeMs() {
        // API results remain available across one skipped 60-second tick.
        // Even if a custom base cadence is selected, never trust arbitrary
        // historical snapshots for ad-hoc SQL-text/plan inspection.
        return Math.min(600_000L, Math.max(180_000L, baseIntervalMs * 3L));
    }

    public record Snapshot(
        String mode, String reason, long baseIntervalMs, long effectiveIntervalMs,
        long lastCollectorWallMs, Instant lastObservedAt,
        int sampledInternalPlans, double peakInternalAvgCpuMs,
        double peakInternalAvgLogicalReadsPages, int expensiveStreak,
        int recoveryStreak, int failureStreak, long skippedCycles,
        long totalCollectorAttempts, long successfulCollections,
        long failedCollections, long cumulativeJdbcWallMs, String limitation
    ) {}
}
