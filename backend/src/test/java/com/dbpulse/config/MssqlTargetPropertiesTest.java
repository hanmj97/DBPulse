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

    @Test void disconnectResetsRuntimeWithoutErasingLegacyConfig() {
        var settings = new MssqlTargetProperties();
        var transitions = new java.util.concurrent.atomic.AtomicInteger();
        settings.onTargetChanged(transitions::incrementAndGet);
        var first = new MssqlTargetProperties.RuntimeTarget(
            "db-1", "DB One", "host.docker.internal", 1433,
            "FirstDb", "reader", "secret-a", false);
        var second = new MssqlTargetProperties.RuntimeTarget(
            "db-2", "DB Two", "host.docker.internal", 1433,
            "SecondDb", "reader", "secret-b", false);
        assertTrue(settings.activateOnce(first));
        assertFalse(settings.activateOnce(second));
        assertTrue(settings.disconnectUiTarget());
        assertFalse(settings.isEnabled());
        assertEquals("", settings.getPassword());
        assertEquals("local-mssql", settings.getId());
        assertFalse(settings.disconnectUiTarget());
        assertTrue(settings.activateOnce(second));
        assertEquals("db-2", settings.getId());
        assertEquals("SecondDb", settings.getDatabase());
        assertEquals(3, transitions.get());
    }

    @Test void disconnectWaitsForInFlightReadLeaseBeforeChangingTarget() throws Exception {
        var settings = new MssqlTargetProperties();
        assertTrue(settings.activateOnce(new MssqlTargetProperties.RuntimeTarget(
            "db-1","One","localhost",1433,"One","reader","secret",false)));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var reading = pool.submit(() -> settings.withReadLease(() -> {
                entered.countDown();
                try { if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("reader timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                assertEquals("db-1", settings.getId());
            }));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var disconnect = pool.submit(settings::disconnectUiTarget);
            // An in-flight collector retains its lease until complete.
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> disconnect.get(70, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            reading.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(disconnect.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(settings.isEnabled());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
