package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class QueryDiagnosisTest {
    @Test void classifiesHighReadsBeforeGenericSlowness() {
        assertThat(QueryDiagnosis.classify(800, 5, 1500)).isEqualTo("SLOW_HIGH_READS");
    }
    @Test void reportsCpuWithSupportingMeasurement() {
        assertThat(QueryDiagnosis.classify(600, 300, 20)).isEqualTo("SLOW_HIGH_CPU");
    }
    @Test void doesNotInventProblemsForNormalValues() {
        assertThat(QueryDiagnosis.classify(15, 4, 10)).isEqualTo("OBSERVE");
    }
}
