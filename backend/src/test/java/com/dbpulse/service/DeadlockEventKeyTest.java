package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class DeadlockEventKeyTest {
    @Test
    void stableForSameXelEventButDifferentForDifferentOffset() {
        var first = DeadlockEventKey.fromLocation("C:\\sql\\system_health_01.xel", 450);
        assertThat(first).hasSize(64);
        assertThat(first).isEqualTo(
            DeadlockEventKey.fromLocation("C:\\sql\\system_health_01.xel", 450));
        assertThat(first).isNotEqualTo(
            DeadlockEventKey.fromLocation("C:\\sql\\system_health_01.xel", 451));
    }
}
