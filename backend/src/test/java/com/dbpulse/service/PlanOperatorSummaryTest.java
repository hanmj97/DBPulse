package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanOperatorSummaryTest {
    @Test
    void countsOnlyWhitelistedPhysicalOperators() {
        var summary = PlanOperatorSummary.summarize(
            List.of("Index Scan", "Key Lookup", "Key Lookup", "Sort", "Hash Match"), 100);
        assertThat(summary.truncated()).isFalse();
        assertThat(summary.sampledNodes()).isEqualTo(5);
        assertThat(summary.operators())
            .extracting(PlanOperatorSummary.OperatorCount::type)
            .contains("KEY_LOOKUP", "INDEX_SCAN", "SORT", "HASH_MATCH");
        assertThat(summary.operators().stream()
            .filter(o -> o.type().equals("KEY_LOOKUP"))
            .findFirst().orElseThrow().count()).isEqualTo(2);
    }

    @Test
    void doesNotExposeUnknownPlanStringsOrObjectNames() {
        var summary = PlanOperatorSummary.summarize(
            java.util.Arrays.asList("some app's secret table", null, "Index Seek"), 100);
        assertThat(summary.operators())
            .extracting(PlanOperatorSummary.OperatorCount::type)
            .contains("OTHER_OPERATOR", "INDEX_SEEK")
            .doesNotContain("some app's secret table");
    }

    @Test
    void truncationIsExplicitAndDoesNotClaimFullCounts() {
        var summary = PlanOperatorSummary.summarize(
            List.of("Sort", "Sort", "Sort", "Sort"), 2);
        assertThat(summary.truncated()).isTrue();
        assertThat(summary.sampledNodes()).isEqualTo(2);
        assertThat(summary.operators().getFirst().count()).isEqualTo(2);
    }
    @Test
    void recognizesStructuralDmlOperatorsWithoutLeakingTableOrIndexNames() {
        var summary = PlanOperatorSummary.summarize(
            List.of("Clustered Index Update", "Index Update", "Table Update",
                "Clustered Index Insert", "Index Insert", "Table Insert",
                "Clustered Index Delete", "Index Delete", "Table Delete"), 100);

        assertThat(summary.truncated()).isFalse();
        assertThat(summary.sampledNodes()).isEqualTo(9);
        assertThat(summary.operators())
            .extracting(PlanOperatorSummary.OperatorCount::type)
            .containsExactlyInAnyOrder(
                "CLUSTERED_INDEX_UPDATE", "INDEX_UPDATE", "TABLE_UPDATE",
                "CLUSTERED_INDEX_INSERT", "INDEX_INSERT", "TABLE_INSERT",
                "CLUSTERED_INDEX_DELETE", "INDEX_DELETE", "TABLE_DELETE")
            .doesNotContain("OTHER_OPERATOR");
        assertThat(summary.operators()).allSatisfy(item ->
            assertThat(item.count()).isEqualTo(1));
    }

}
