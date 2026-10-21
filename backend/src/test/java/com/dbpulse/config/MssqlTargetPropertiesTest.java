package com.dbpulse.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MssqlTargetPropertiesTest {

    @Test void disabledLegacyMssqlMayActivateExactlyOneNewUiTarget() {
        var settings = new MssqlTargetProperties();
        assertFalse(settings.isEnabled());
        assertEquals("local-mssql", settings.getId());

        var selected = new MssqlTargetProperties.RuntimeTarget(
            "local-generated-uuid", "실험 데이터베이스", "host.docker.internal",
            1433, "TestProductionLikeDB", "dbpulse_reader",
            "memory-only-credential", true);
        assertTrue(settings.activateOnce(selected));
        assertTrue(settings.isUiActivated());
        assertTrue(settings.isEnabled());
        assertEquals("local-generated-uuid", settings.getId());
        assertEquals("실험 데이터베이스", settings.getName());
        assertEquals("TestProductionLikeDB", settings.getDatabase());
        assertEquals("dbpulse_reader", settings.getUsername());
        assertTrue(settings.isTrustServerCertificate());

        // Switching mid-stream would mingle live Query Store/incident state.
        var second = new MssqlTargetProperties.RuntimeTarget(
            "different-target", "other", "localhost", 1433,
            "OtherDb", "readonly", "another-secret", false);
        assertFalse(settings.activateOnce(second));
        assertEquals("local-generated-uuid", settings.getId());
    }

    @Test void legacyEnvConfiguredTargetNeverGetsOverwritten() {
        var settings = new MssqlTargetProperties();
        settings.setEnabled(true);
        settings.setDatabase("DBPulseLab");
        var selected = new MssqlTargetProperties.RuntimeTarget(
            "ui-target", "name", "host.docker.internal", 1433,
            "AnotherDB", "readonly", "secret", false);
        assertFalse(settings.activateOnce(selected));
        assertFalse(settings.isUiActivated());
        assertEquals("DBPulseLab", settings.getDatabase());
        assertEquals("local-mssql", settings.getId());
    }

    @Test void emptyCredentialsCannotActivateRuntimeCollector() {
        var settings = new MssqlTargetProperties();
        assertFalse(settings.activateOnce(new MssqlTargetProperties.RuntimeTarget(
            "some-id", "local", "localhost", 1433,
            "DBPulseLab", "reader", "", false)));
        assertFalse(settings.isEnabled());
    }
}
