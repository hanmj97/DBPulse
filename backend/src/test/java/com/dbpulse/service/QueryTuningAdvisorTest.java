package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryTuningAdvisorTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static QueryPerformanceSnapshot snapshot(long queryId, double duration,
                                                     double cpu, double reads) {
        return new QueryPerformanceSnapshot(
            NOW, queryId, 2, 17, duration, cpu, reads,
            NOW, QueryDiagnosis.classify(duration, cpu, reads));
    }

    @Test
    void highReadsAndSlowDurationSuggestInvestigationNotIndexCreation() {
        var advice = new QueryTuningAdvisor().advise(
            List.of(snapshot(1, 850, 20, 2400)), 10).getFirst();
        assertThat(advice.priority()).isEqualTo("HIGH");
        assertThat(advice.actions()).extracting(QueryTuningAdvisor.Advice::code)
            .containsExactly("REVIEW_LOGICAL_READS", "REVIEW_WAIT_AND_BLOCKING");
        assertThat(advice.actions()).allSatisfy(step ->
            assertThat(step.description()).doesNotContain("인덱스를 생성하세요"));
        assertThat(advice.limitation()).contains("실행계획 XML");
    }

    @Test
    void sortHighBeforeMediumAndObserveAndApplyLimit() {
        var rows = List.of(snapshot(10, 25, 8, 10),
            snapshot(20, 600, 310, 100),
            snapshot(30, 100, 20, 1400));
        var result = new QueryTuningAdvisor().advise(rows, 2);
        assertThat(result).extracting(QueryTuningAdvisor.Candidate::queryId)
            .containsExactly(20L, 30L);
    }

    @Test
    void CPUWithoutSlowDurationRemainsMediumCandidate() {
        var advice = new QueryTuningAdvisor().advise(
            List.of(snapshot(9, 100, 300, 25)), 10).getFirst();
        assertThat(advice.priority()).isEqualTo("MEDIUM");
        assertThat(advice.actions()).extracting(QueryTuningAdvisor.Advice::code)
            .containsExactly("REVIEW_CPU_OPERATORS");
    }

    @Test
    void lowMetricsAreObservationNotProofOfGoodPerformance() {
        var advice = new QueryTuningAdvisor().advise(
            List.of(snapshot(2, 30, 10, 5)), 10).getFirst();
        assertThat(advice.priority()).isEqualTo("OBSERVE");
        assertThat(advice.actions()).extracting(QueryTuningAdvisor.Advice::code)
            .containsExactly("BASELINE_ONLY");
        assertThat(advice.actions().getFirst().description()).contains("보증하지");
    }
    @Test
    void recentSortingPlacesMostRecentlyExecutedQueriesFirstEvenIfPriorityIsLower() {
        var oldSlow = snapshot(10, 750, 320, 2600);
        var justRan = new QueryPerformanceSnapshot(
            NOW, 20, 22, 1, 4, 2, 3,
            NOW.plusSeconds(120), "OBSERVE");
        var rows = List.of(oldSlow, justRan);
        assertThat(new QueryTuningAdvisor().advise(rows, 2, "recent"))
            .extracting(QueryTuningAdvisor.Candidate::queryId)
            .containsExactly(20L, 10L);
        assertThat(new QueryTuningAdvisor().advise(rows, 2, "priority"))
            .extracting(QueryTuningAdvisor.Candidate::queryId)
            .containsExactly(10L, 20L);
    }

    @Test
    void durationSortingWorksIndependentlyOfLastExecutionTime() {
        var rows = List.of(snapshot(1, 50, 10, 5),snapshot(2, 800, 600, 2000));
        assertThat(new QueryTuningAdvisor().advise(rows, 2, "duration"))
            .extracting(QueryTuningAdvisor.Candidate::queryId)
            .containsExactly(2L, 1L);
    }

}
