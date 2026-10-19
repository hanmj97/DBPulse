package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.dbpulse.domain.IncidentRecord;
import com.dbpulse.persistence.IncidentEvidenceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentEvidenceClassifierTest {
    private final Instant at = Instant.parse("2026-10-19T01:00:00Z");

    private IncidentRecord incident(String type) {
        return new IncidentRecord(4, "local-mssql", type, "WARNING", "RESOLVED",
            at, at.plusSeconds(10), at.plusSeconds(20), 1, "test");
    }

    @Test
    void collectorFailureNeverClaimsSqlServerIsDown() {
        var evidence = new IncidentEvidenceRepository.Evidence(
            new IncidentEvidenceRepository.MetricAggregate(0, 0, 0, 0), List.of(), List.of());
        var findings = IncidentEvidenceClassifier.assess(incident("COLLECTION_FAILURE"), evidence);
        assertThat(findings).extracting(IncidentEvidenceClassifier.Finding::code)
            .contains("COLLECTION_VISIBILITY_LOST", "NO_METRICS_IN_WINDOW");
        assertThat(findings).allSatisfy(x -> assertThat(x.kind()).isEqualTo("LIMITATION"));
    }

    @Test
    void observedBlockingChainIsEvidenceButNotProvenRootCause() {
        var edge = new IncidentEvidenceRepository.BlockingObservation(
            at, 80, 71, "LCK_M_X", 11671, 71, 1);
        var evidence = new IncidentEvidenceRepository.Evidence(
            new IncidentEvidenceRepository.MetricAggregate(4, 1, 1, 15000),
            List.of(edge), List.of());
        var findings = IncidentEvidenceClassifier.assess(incident("BLOCKING"), evidence);
        assertThat(findings).extracting(IncidentEvidenceClassifier.Finding::code)
            .contains("BLOCKING_CHAIN_OBSERVED", "BLOCKED_REQUESTS_OBSERVED");
        assertThat(findings.stream().filter(x -> x.code().equals("BLOCKING_CHAIN_OBSERVED"))
            .findFirst().orElseThrow().detail()).contains("별도 확인");
    }
}
