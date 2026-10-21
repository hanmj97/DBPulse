package com.dbpulse.desktop;

import static org.junit.jupiter.api.Assertions.*;
import java.net.BindException;
import java.net.ConnectException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class DesktopStartupDiagnosticsTest {
    @Test void recognizesUnprovisionedWindowsUserProfile() {
        assertEquals("DESKTOP_SETUP_REQUIRED", DesktopStartupDiagnostics.categorize(
            new IllegalStateException("DESKTOP_SETUP_REQUIRED")));
        assertEquals("DESKTOP_CONFIG_CANNOT_DECRYPT", DesktopStartupDiagnostics.categorize(
            new IllegalStateException("DESKTOP_CONFIG_CANNOT_DECRYPT")));
    }

    @Test void differentiatesPostgresAuthFromNetworkAndPortConflict() {
        assertEquals("METADATA_DB_PASSWORD_REJECTED", DesktopStartupDiagnostics.categorize(
            new IllegalStateException("wrapped", new SQLException(
                "a private JDBC exception may contain credentials", "28P01"))));
        assertEquals("METADATA_DB_UNREACHABLE", DesktopStartupDiagnostics.categorize(
            new IllegalStateException("jdbc metadata", new ConnectException("connection refused"))));
        assertEquals("PORT_18081_ALREADY_IN_USE", DesktopStartupDiagnostics.categorize(
            new IllegalStateException("server startup", new BindException("Address already in use"))));
        assertEquals("METADATA_DATABASE_NOT_FOUND", DesktopStartupDiagnostics.categorize(
            new SQLException("raw private connection string", "3D000")));
    }

    @Test void neverReturnsRawExceptionMessagesToUser() {
        var secret="not-a-real-password-private-connection";
        var error=new SQLException("jdbc:postgresql://db?password="+secret,"08006");
        var category=DesktopStartupDiagnostics.categorize(error);
        var explanation=DesktopStartupDiagnostics.nextStep(category);
        assertEquals("METADATA_DB_UNREACHABLE",category);
        assertFalse(category.contains(secret));
        assertFalse(explanation.contains(secret));
        assertEquals("BACKEND_STARTUP_FAILED",DesktopStartupDiagnostics.categorize(
            new IllegalStateException("private JDBC details")));
    }
}
