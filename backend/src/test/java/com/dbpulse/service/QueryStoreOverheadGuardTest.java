package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryStoreOverheadGuardTest {
    private static final Instant NOW = Instant.parse("2026-10-20T06:00:00Z");

    private QueryPerformanceSnapshot internal(double cpuMs, double reads) {
        return new QueryPerformanceSnapshot(NOW, 95, 29, 100,
            45.76, cpuMs, reads, NOW, "COLLECTOR_OVERHEAD", "DBPULSE_PATTERN");
    }

    private QueryPerformanceSnapshot user(double cpuMs, double reads) {
        return new QueryPerformanceSnapshot(NOW, 247, 191, 100,
            45.76, cpuMs, reads, NOW, "HIGH_READS", "OTHER_OR_UNKNOWN");
    }

    @Test
    void healthyPollsStayAtSixtySeconds() {
        var g = new QueryStoreOverheadGuard(60_000);
        assertThat(g.shouldSkipScheduledTick()).isFalse();
        g.recordSuccess(100, List.of(internal(2, 100)), NOW);
        g.recordSuccess(125, List.of(internal(5, 90)), NOW.plusSeconds(60));
        assertThat(g.snapshot().mode()).isEqualTo("NORMAL");
        assertThat(g.snapshot().effectiveIntervalMs()).isEqualTo(60_000);
        assertThat(g.shouldSkipScheduledTick()).isFalse();
    }

    @Test
    void twoConsecutiveHighReadSamplesThrottleOnlyQueryStoreEveryOtherTick() {
        var g = new QueryStoreOverheadGuard(60_000);
        g.recordSuccess(150, List.of(internal(2, 1071)), NOW);
        assertThat(g.snapshot().mode()).isEqualTo("NORMAL");
        assertThat(g.snapshot().reason()).isEqualTo("INTERNAL_AVG_LOGICAL_READS_HIGH");
        g.recordSuccess(150, List.of(internal(2, 1070)), NOW.plusSeconds(60));
        assertThat(g.snapshot().mode()).isEqualTo("THROTTLED");
        assertThat(g.snapshot().effectiveIntervalMs()).isEqualTo(120_000);
        assertThat(g.shouldSkipScheduledTick()).isTrue();
        assertThat(g.shouldSkipScheduledTick()).isFalse();
        assertThat(g.snapshot().skippedCycles()).isEqualTo(1);
    }

    @Test
    void threeHealthyCollectedSamplesRecoverFromThrottling() {
        var g = new QueryStoreOverheadGuard(60_000);
        g.recordSuccess(100, List.of(internal(100, 10)), NOW);
        g.recordSuccess(100, List.of(internal(105, 10)), NOW.plusSeconds(60));
        assertThat(g.snapshot().mode()).isEqualTo("THROTTLED");
        for (int i=1;i<=3;i++) {
            assertThat(g.shouldSkipScheduledTick()).isTrue();
            assertThat(g.shouldSkipScheduledTick()).isFalse();
            g.recordSuccess(100, List.of(internal(1, 10)), NOW.plusSeconds(60+120L*i));
        }
        assertThat(g.snapshot().mode()).isEqualTo("NORMAL");
        assertThat(g.shouldSkipScheduledTick()).isFalse();
    }

    @Test
    void highUserQueryCostsDoNotTriggerObserverThrottling() {
        var g = new QueryStoreOverheadGuard(60_000);
        g.recordSuccess(100, List.of(user(10000, 500000)), NOW);
        g.recordSuccess(100, List.of(user(10000, 500000)), NOW.plusSeconds(60));
        assertThat(g.snapshot().mode()).isEqualTo("NORMAL");
        assertThat(g.snapshot().sampledInternalPlans()).isZero();
        assertThat(g.snapshot().reason()).isEqualTo("NO_INTERNAL_QUERY_STORE_SAMPLES");
    }

    @Test
    void slowJDBCCollectionAndRepeatedFailuresAlsoThrottleSafely() {
        var g = new QueryStoreOverheadGuard(60_000);
        g.recordSuccess(2000, List.of(), NOW);
        g.recordSuccess(3000, List.of(), NOW.plusSeconds(60));
        assertThat(g.snapshot().mode()).isEqualTo("THROTTLED");
        assertThat(g.snapshot().reason()).isEqualTo("COLLECTOR_WALL_TIME_HIGH");
        g.recordFailure(5000, NOW.plusSeconds(120));
        g.recordFailure(5000, NOW.plusSeconds(180));
        assertThat(g.snapshot().reason()).isEqualTo("QUERY_STORE_COLLECTION_FAILED");
        assertThat(g.snapshot().failureStreak()).isEqualTo(2);
        assertThat(g.shouldSkipScheduledTick()).isTrue();
    }

    @Test
    void staleAgeIsLongEnoughForSkippedTickButBounded() {
        var g = new QueryStoreOverheadGuard(60_000);
        assertThat(g.allowedAgeMs()).isEqualTo(180_000);
        assertThat(new QueryStoreOverheadGuard(300_000).allowedAgeMs()).isEqualTo(600_000);
    }

    @Test
    void unconfiguredResetRemovesBackoff() {
        var g = new QueryStoreOverheadGuard(60_000);
        g.recordFailure(2000,NOW);
        g.recordFailure(2000,NOW.plusSeconds(60));
        g.reset();
        assertThat(g.snapshot().mode()).isEqualTo("NORMAL");
        assertThat(g.snapshot().reason()).isEqualTo("NOT_CONFIGURED");
        assertThat(g.shouldSkipScheduledTick()).isFalse();
    }
    @Test
    void actualAttemptsAndSkippedTicksAreDistinctNonSqlServerCounters() {
        var g = new QueryStoreOverheadGuard(60_000);
        g.recordSuccess(100, List.of(internal(1, 1000)), NOW);
        g.recordSuccess(180, List.of(internal(1, 1200)), NOW.plusSeconds(60));
        assertThat(g.shouldSkipScheduledTick()).isTrue();
        assertThat(g.shouldSkipScheduledTick()).isFalse();
        g.recordFailure(250, NOW.plusSeconds(180));
        var report = g.snapshot();
        assertThat(report.totalCollectorAttempts()).isEqualTo(3);
        assertThat(report.successfulCollections()).isEqualTo(2);
        assertThat(report.failedCollections()).isEqualTo(1);
        assertThat(report.skippedCycles()).isEqualTo(1);
        assertThat(report.cumulativeJdbcWallMs()).isEqualTo(530);
        assertThat(report.totalCollectorAttempts())
            .isEqualTo(report.successfulCollections() + report.failedCollections());
    }

    @Test
    void fixedBaselineNeverSkipsEvenWithExpensiveCallsOrFailures() {
        var g = new QueryStoreOverheadGuard(60_000, false);
        g.recordSuccess(2_500, List.of(internal(200, 5000)), NOW);
        g.recordSuccess(2_700, List.of(internal(200, 5000)), NOW.plusSeconds(60));
        g.recordFailure(2_000, NOW.plusSeconds(120));
        g.recordFailure(2_000, NOW.plusSeconds(180));
        assertThat(g.snapshot().mode()).isEqualTo("NORMAL");
        assertThat(g.snapshot().policy()).isEqualTo("FIXED_BASELINE_LAB_ONLY");
        assertThat(g.snapshot().effectiveIntervalMs()).isEqualTo(60_000);
        assertThat(g.shouldSkipScheduledTick()).isFalse();
        assertThat(g.snapshot().skippedCycles()).isZero();
        assertThat(g.snapshot().totalCollectorAttempts()).isEqualTo(4);
        assertThat(g.snapshot().cumulativeJdbcWallMs()).isEqualTo(9_200);
    }

    @Test
    void adaptiveModeIsDefaultAndExplicit() {
        assertThat(new QueryStoreOverheadGuard(60_000).snapshot().policy()).isEqualTo("ADAPTIVE");
    }

    @Test
    void totalsSurviveCounterStateResetDuringDisabledTargetPeriod() {
        var g = new QueryStoreOverheadGuard(60_000);
        g.recordSuccess(100, List.of(), NOW);
        g.reset();
        assertThat(g.snapshot().successfulCollections()).isEqualTo(1);
        assertThat(g.snapshot().cumulativeJdbcWallMs()).isEqualTo(100);
        assertThat(g.snapshot().reason()).isEqualTo("NOT_CONFIGURED");
    }

}
