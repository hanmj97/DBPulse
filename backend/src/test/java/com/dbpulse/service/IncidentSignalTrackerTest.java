package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.dbpulse.service.IncidentSignalTracker.Action;
import com.dbpulse.service.IncidentSignalTracker.Type;
import org.junit.jupiter.api.Test;

class IncidentSignalTrackerTest {
    @Test
    void opensAfterTwoBadAndResolvesAfterTwoGoodSamples() {
        var detector = new IncidentSignalTracker();
        assertThat(detector.observe(Type.BLOCKING, true)).isEqualTo(Action.NONE);
        assertThat(detector.observe(Type.BLOCKING, true)).isEqualTo(Action.OPEN);
        assertThat(detector.observe(Type.BLOCKING, true)).isEqualTo(Action.OPEN);
        assertThat(detector.observe(Type.BLOCKING, false)).isEqualTo(Action.NONE);
        assertThat(detector.observe(Type.BLOCKING, false)).isEqualTo(Action.RESOLVE);
        assertThat(detector.observe(Type.BLOCKING, true)).isEqualTo(Action.NONE);
        assertThat(detector.observe(Type.BLOCKING, true)).isEqualTo(Action.OPEN);
    }

    @Test
    void separateTypesDoNotAffectOneAnother() {
        var detector = new IncidentSignalTracker();
        assertThat(detector.observe(Type.BLOCKING, true)).isEqualTo(Action.NONE);
        assertThat(detector.observe(Type.COLLECTION_FAILURE, true)).isEqualTo(Action.NONE);
        assertThat(detector.observe(Type.COLLECTION_FAILURE, true)).isEqualTo(Action.OPEN);
        assertThat(detector.observe(Type.BLOCKING, true)).isEqualTo(Action.OPEN);
    }
}
