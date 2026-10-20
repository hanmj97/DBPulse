package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryPlanComparisonServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private final QueryPlanComparisonService service = new QueryPlanComparisonService();

    private static QueryPerformanceSnapshot sample(long queryId, long planId,
            long executions, double duration, double cpu, double reads) {
        return new QueryPerformanceSnapshot(NOW, queryId, planId, executions,
            duration, cpu, reads, NOW, "OBSERVE", "OTHER_OR_UNKNOWN");
    }

    @Test
    void comparesOnlyOtherPlansOfTheExactSameQuery() {
        var selected = sample(251, 195, 12, 20, 10, 200);
        var slower = sample(251, 196, 15, 40, 18, 500);
        var unrelated = sample(252, 197, 100, 900, 600, 2000);
        var report = service.compare(selected, List.of(selected, unrelated, slower));
        assertThat(report.status()).isEqualTo("COMPARISON_AVAILABLE");
        assertThat(report.comparisons()).hasSize(1);
        var pair = report.comparisons().getFirst();
        assertThat(pair.planId()).isEqualTo(196);
        assertThat(pair.durationRatioToSelected()).isEqualTo(2d);
        assertThat(pair.assessment()).isEqualTo("MEAN_DURATION_DIFFERENCE");
        assertThat(pair.nextCheck()).contains("회귀가 확정된 것은 아닙니다");
    }

    @Test
    void aSinglePlanDoesNotProduceFakeRegression() {
        var selected = sample(247, 191, 10, 5, 2, 4);
        var report = service.compare(selected, List.of(selected));
        assertThat(report.status()).isEqualTo("ONLY_ONE_PLAN_SAMPLED");
        assertThat(report.comparisons()).isEmpty();
    }

    @Test
    void fewerThanFiveSampleExecutionsDoesNotFlagARegression() {
        var selected = sample(251, 195, 1, 100, 40, 200);
        var peer = sample(251, 196, 10, 1000, 700, 12000);
        var report = service.compare(selected, List.of(selected, peer));
        assertThat(report.comparisons().getFirst().assessment())
            .isEqualTo("INSUFFICIENT_SAMPLES");
        assertThat(report.comparisons().getFirst().durationRatioToSelected()).isEqualTo(10d);
    }

    @Test
    void tinyExecutionDurationsDoNotProduceUnstableRatios() {
        var selected = sample(251, 195, 10, 0.5, 0.3, 4);
        var peer = sample(251, 196, 20, 4, 2, 12);
        var report = service.compare(selected, List.of(selected, peer));
        assertThat(report.comparisons().getFirst().durationRatioToSelected()).isNull();
        assertThat(report.comparisons().getFirst().assessment())
            .isEqualTo("SMALL_DURATION_BASELINE");
    }

    @Test
    void differenceInOppositeDirectionCanBeObservedWithoutClaimingTimeOrder() {
        var selected = sample(251, 195, 15, 50, 25, 500);
        var peer = sample(251, 196, 16, 20, 8, 80);
        var report = service.compare(selected, List.of(selected, peer));
        assertThat(report.comparisons().getFirst().assessment())
            .isEqualTo("MEAN_DURATION_DIFFERENCE");
        assertThat(report.comparisons().getFirst().durationRatioToSelected()).isEqualTo(0.4);
        assertThat(report.limitation()).contains("시간순 회귀");
    }

    @Test
    void similarDurationsDoNotTriggerDifference() {
        var selected = sample(251, 195, 10, 50, 25, 500);
        var peer = sample(251, 196, 12, 60, 30, 800);
        assertThat(service.compare(selected, List.of(selected, peer))
            .comparisons().getFirst().assessment()).isEqualTo("NO_LARGE_MEAN_DIFFERENCE");
    }

    @Test
    void comparisonResultsAreDeterministicallySortedByPlanId() {
        var selected = sample(251, 195, 10, 50, 25, 500);
        var b = sample(251, 198, 10, 60, 25, 500);
        var a = sample(251, 196, 10, 30, 20, 200);
        assertThat(service.compare(selected, List.of(b, selected, a))
            .comparisons()).extracting(QueryPlanComparisonService.Comparison::planId)
            .containsExactly(196L, 198L);
    }
}
