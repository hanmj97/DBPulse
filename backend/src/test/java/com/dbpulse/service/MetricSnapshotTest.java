package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.dbpulse.domain.MetricSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MetricSnapshotTest {
    @Test
    void recordsExactDatabaseMeasurementsWithoutFabricatingValues() {
        MetricSnapshot item = new MetricSnapshot(Instant.parse("2026-10-19T00:00:00Z"), 12, 3, 2, 3400, 850);
        assertThat(item.activeRequests()).isEqualTo(12);
        assertThat(item.blockedRequests()).isEqualTo(3);
        assertThat(item.longRunningRequests()).isEqualTo(2);
        assertThat(item.maxElapsedMs()).isEqualTo(3400);
    }
}
