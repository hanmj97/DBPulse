package com.dbpulse.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LocalTargetPolicyTest {
    private final LocalTargetPolicy local = new LocalTargetPolicy(
        "host.docker.internal,localhost,127.0.0.1");

    @Test void onlyOperatorAllowListedHostsCanBeConnected() {
        assertTrue(local.permits("host.docker.internal"));
        assertTrue(local.permits("LOCALHOST"));
        assertTrue(local.permits("127.0.0.1"));
        assertFalse(local.permits("10.0.0.3"));
        assertFalse(local.permits("metadata.google.internal"));
        assertFalse(local.permits("localhost.attacker.com"));
        assertFalse(local.permits("host.docker.internal;encrypt=false"));
        assertFalse(local.permits("127.0.0.1/../"));
        assertFalse(local.permits(null));
    }

    @Test void databaseAndNamesAreValidatedBeforeJdbcStringBuild() {
        assertTrue(local.validDatabaseName("DBPulseLab"));
        assertTrue(local.validDatabaseName("한글 DB"));
        assertFalse(local.validDatabaseName("DBPulseLab;trustServerCertificate=true"));
        assertFalse(local.validDatabaseName("x\nlogin"));
        assertFalse(local.validLogin("bad\nname"));
        assertTrue(local.validLogin("read_only_login"));
        assertTrue(local.validDisplayName("사내 운영DB"));
        assertFalse(local.validDisplayName("x\rbroken"));
    }

    @Test void blankAllowListDoesNotPermitAnyTarget() {
        LocalTargetPolicy empty = new LocalTargetPolicy("");
        assertFalse(empty.permits("localhost"));
    }
}
