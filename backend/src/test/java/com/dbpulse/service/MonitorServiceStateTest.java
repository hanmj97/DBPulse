package com.dbpulse.service;

import static org.junit.jupiter.api.Assertions.*;
import com.dbpulse.config.MssqlTargetProperties;
import org.junit.jupiter.api.Test;

class MonitorServiceStateTest {
    @Test void uiActivationShowsInitializingUntilFirstRealDmvPoll() {
        var target = new MssqlTargetProperties();
        var monitor = new MonitorService(target, null, null, null);
        assertEquals("NOT_CONFIGURED", monitor.state().status());

        assertTrue(target.activateOnce(new MssqlTargetProperties.RuntimeTarget(
            "uuid-target", "테스트", "host.docker.internal", 1433, "DBPulseLab",
            "readonly", "session-only", true)));
        assertEquals("INITIALIZING", monitor.state().status());
        assertNull(monitor.state().lastCollectedAt());
    }
}
