package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.dbpulse.persistence.IncidentWaitEvidenceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentWaitFindingsTest {
    @Test
    void missingSamplesMustNotBeCalledHealthy() {
        var evidence = new IncidentWaitEvidenceRepository.WaitEvidence(
            0, 0, List.of(), List.of());
        var findings = IncidentWaitFindings.from(evidence);
        assertThat(findings).extracting(IncidentEvidenceClassifier.Finding::code)
            .containsExactly("WAIT_SAMPLES_UNAVAILABLE");
        assertThat(findings.getFirst().kind()).isEqualTo("LIMITATION");
    }

    @Test
    void zeroObservedWaitsAreOnlyASamplingLimit() {
        var evidence = new IncidentWaitEvidenceRepository.WaitEvidence(
            4, 0, List.of(), List.of());
        var findings = IncidentWaitFindings.from(evidence);
        assertThat(findings.getFirst().code()).isEqualTo("WAITS_NOT_OBSERVED");
        assertThat(findings.getFirst().kind()).isEqualTo("LIMITATION");
    }

    @Test
    void samplesWithLockWaitsAreMarkedObservedNotProvenCause() {
        var item = new IncidentWaitEvidenceRepository.HistoricalWait(
            Instant.parse("2026-10-20T00:00:00Z"), 80, "LCK_M_X",
            "LOCK", "KEY", 12000, 71);
        var evidence = new IncidentWaitEvidenceRepository.WaitEvidence(
            6, 2, List.of(new IncidentWaitEvidenceRepository.WaitCategorySummary(
                "LOCK", 4, 12000)), List.of(item));
        var findings = IncidentWaitFindings.from(evidence);
        assertThat(findings).extracting(IncidentEvidenceClassifier.Finding::code)
            .containsExactly("WAIT_COLLECTION_LIMIT_REACHED", "WAIT_CATEGORY_OBSERVED");
        assertThat(findings.get(1).kind()).isEqualTo("OBSERVED");
        assertThat(findings.get(1).detail()).contains("확정하지 않습니다");
    }
}
