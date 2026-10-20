package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dbpulse.collector.MssqlPlanOperatorCollector.PlanReport;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanMetricCorrelationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private final PlanMetricCorrelationService service = new PlanMetricCorrelationService();

    private static QueryPerformanceSnapshot sample(
        long qid, long pid, double duration, double cpu, double reads) {
        return new QueryPerformanceSnapshot(NOW, qid, pid, 20, duration,
            cpu, reads, NOW, "OBSERVE", "OTHER_OR_UNKNOWN");
    }

    private static PlanReport plan(long qid, long pid, String status, String... types) {
        var ops = java.util.Arrays.stream(types)
            .map(type -> new PlanOperatorSummary.OperatorCount(type, 1)).toList();
        return new PlanReport(qid, pid, status, NOW, false, false, 5000L,
            ops.size(), ops, "Compiled plan operators only");
    }

    @Test
    void matchesActualLookupAndHighReadsWithoutClaimingRootCause() {
        var result = service.review(sample(239, 178, 700, 50, 2400),
            plan(239, 178, "AVAILABLE", "KEY_LOOKUP", "INDEX_SEEK"));
        assertThat(result.status()).isEqualTo("EVIDENCE_AVAILABLE");
        assertThat(result.sampleCollectedAt()).isEqualTo(NOW);
        assertThat(result.findings()).extracting(PlanMetricCorrelationService.Finding::code)
            .contains("READS_WITH_LOOKUP", "DURATION_REVIEW")
            .doesNotContain("READS_WITH_SCAN");
        assertThat(result.findings().getFirst().nextCheck())
            .contains("SSMS").doesNotContain("CREATE INDEX");
        assertThat(result.limitation()).contains("직접 원인이라는 뜻이 아니");
    }

    @Test
    void scanPlusReadsIsContextForReviewNotMandatoryIndexChange() {
        var result = service.review(sample(2, 3, 150, 50, 1600),
            plan(2, 3, "AVAILABLE", "CLUSTERED_INDEX_SCAN"));
        assertThat(result.findings()).extracting(PlanMetricCorrelationService.Finding::code)
            .containsExactly("READS_WITH_SCAN");
        assertThat(result.findings().getFirst().nextCheck()).contains("Scan 자체가 비효율이라는 뜻은 아닙니다");
    }

    @Test
    void sortOrHashCorrelatesWithHighCpuOnlyWhenObserved() {
        var result = service.review(sample(4, 5, 250, 380, 20),
            plan(4, 5, "AVAILABLE", "SORT", "HASH_MATCH"));
        assertThat(result.findings()).extracting(PlanMetricCorrelationService.Finding::code)
            .containsExactly("CPU_WITH_SORT_OR_HASH");
    }

    @Test
    void dmlOnlyAddsInformationalObservation() {
        var result = service.review(sample(247, 191, 1.0, 0.5, 2.0),
            plan(247, 191, "AVAILABLE", "CLUSTERED_INDEX_UPDATE"));
        assertThat(result.findings()).extracting(PlanMetricCorrelationService.Finding::code)
            .containsExactly("DML_OPERATOR_OBSERVED");
        assertThat(result.findings().getFirst().kind()).isEqualTo("INFO");
    }

    @Test
    void incompletePlanIsMarkedPartialAndNeverUsedToAssertMissingOperators() {
        var result = service.review(sample(100, 200, 5, 4, 1500),
            plan(100, 200, "OPERATOR_LIMIT_REACHED", "INDEX_SCAN"));
        assertThat(result.status()).isEqualTo("PARTIAL_EVIDENCE");
        assertThat(result.findings()).extracting(PlanMetricCorrelationService.Finding::code)
            .contains("READS_WITH_SCAN");
        assertThat(result.limitation()).contains("100개");
    }

    @Test
    void unavailablePlanProducesNoFalsePlanFindings() {
        var result = service.review(sample(10, 11, 5000, 1000, 25000),
            plan(10, 11, "PLAN_TOO_LARGE"));
        assertThat(result.status()).isEqualTo("PLAN_UNAVAILABLE");
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void mismatchIsRejectedBeforeJoiningUnrelatedMetrics() {
        assertThatThrownBy(() -> service.review(sample(10, 11, 500, 200, 3000),
            plan(10, 12, "AVAILABLE", "KEY_LOOKUP")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("QUERY_PLAN_MISMATCH");
    }

    @Test
    void noThresholdIsNotProofOfHealthyPlan() {
        var result = service.review(sample(10, 11, 1.5, 1.0, 4),
            plan(10, 11, "AVAILABLE", "INDEX_SEEK"));
        assertThat(result.findings()).extracting(PlanMetricCorrelationService.Finding::code)
            .containsExactly("NO_THRESHOLD_CORRELATION");
        assertThat(result.findings().getFirst().nextCheck()).contains("보증하지");
    }
    @Test
    void internalCollectorGetsOnlyOverheadAdviceRegardlessOfPlanOperators() {
        var sample = new QueryPerformanceSnapshot(NOW, 95, 29, 108,
            45.76, 1.96, 1071.43, NOW, "HIGH_READS", "DBPULSE_PATTERN");
        var result = service.review(sample,
            plan(95, 29, "AVAILABLE", "CLUSTERED_INDEX_SCAN", "HASH_MATCH"));
        assertThat(result.status()).isEqualTo("COLLECTOR_OVERHEAD_ONLY");
        assertThat(result.findings())
            .extracting(PlanMetricCorrelationService.Finding::code)
            .containsExactly("MONITORING_OVERHEAD");
        assertThat(result.findings().getFirst().nextCheck()).contains("수집 주기");
    }

}
