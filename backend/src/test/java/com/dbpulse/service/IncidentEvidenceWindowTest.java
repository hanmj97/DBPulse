package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.dbpulse.domain.IncidentRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IncidentEvidenceWindowTest {
    private static IncidentRecord incident(String state, String type,
                                           Instant start, Instant last, Instant resolved) {
        return new IncidentRecord(8, "local-mssql", type, "WARNING", state,
            start, last, resolved, 1, "test");
    }

    @Test
    void resolvedIncidentUsesHistoricalTimeNotCurrentTime() {
        Instant start = Instant.parse("2026-10-19T01:00:00Z");
        Instant resolved = start.plusSeconds(65);
        var window = IncidentEvidenceWindow.of(
            incident("RESOLVED", "BLOCKING", start, start.plusSeconds(60), resolved),
            start.plusSeconds(4000));
        assertThat(window.from()).isEqualTo(start.minusSeconds(15));
        assertThat(window.to()).isEqualTo(resolved.plusSeconds(15));
        assertThat(window.truncated()).isFalse();
    }

    @Test
    void longOpenIncidentLimitsHistoricLookupToFifteenMinutes() {
        Instant start = Instant.parse("2026-10-19T01:00:00Z");
        Instant now = start.plusSeconds(3600);
        var window = IncidentEvidenceWindow.of(
            incident("OPEN", "BLOCKING", start, now.minusSeconds(5), null), now);
        assertThat(window.from()).isEqualTo(now.minusSeconds(900));
        assertThat(window.to()).isEqualTo(now);
        assertThat(window.truncated()).isTrue();
    }
}
