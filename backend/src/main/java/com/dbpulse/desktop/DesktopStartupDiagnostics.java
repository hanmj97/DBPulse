package com.dbpulse.desktop;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import javax.swing.JOptionPane;

/**
 * Safe diagnostic status for native Windows app-image failures.
 * Never serializes Throwable.toString(), SQL text, usernames, environment
 * variables, paths from exception messages, or password-bearing JDBC URLs.
 */
public final class DesktopStartupDiagnostics {
    private static final String LOG_NAME = "desktop-startup-status.txt";

    private DesktopStartupDiagnostics() {}

    public static Path statusFile() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("DESKTOP_LOCALAPPDATA_MISSING");
        }
        return Path.of(base, "DBPulse", "logs", LOG_NAME);
    }

    public static void record(String code) {
        if (!code.matches("[A-Z0-9_]{2,80}")) {
            throw new IllegalArgumentException("INVALID_STATUS_CODE");
        }
        try {
            Path path = statusFile();
            Files.createDirectories(path.getParent());
            if (Files.isSymbolicLink(path.getParent()) || Files.isSymbolicLink(path)) {
                return;
            }
            Files.writeString(path, Instant.now() + " " + code + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (RuntimeException | IOException ignored) {
            // Diagnostic failure must not prevent normal app execution or mask
            // the original startup error.
        }
    }

    /** Stable bounded category; no raw third-party exception message. */
    public static String categorize(Throwable error) {
        if (error == null) return "UNKNOWN";
        for (Throwable cause = error, current = null;
             cause != null && cause != current;
             current = cause, cause = cause.getCause()) {
            if (cause instanceof BindException) return "PORT_18081_ALREADY_IN_USE";
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("28P01".equals(state)) return "METADATA_DB_PASSWORD_REJECTED";
                if ("3D000".equals(state)) return "METADATA_DATABASE_NOT_FOUND";
                if (state != null && state.startsWith("08")) return "METADATA_DB_UNREACHABLE";
            }
            if (cause instanceof ConnectException) return "METADATA_DB_UNREACHABLE";
            String message = cause.getMessage();
            if (cause instanceof IllegalStateException && message != null) {
                if ("PORT_18081_ALREADY_IN_USE".equals(message)) return message;
                if (message.matches("DESKTOP_[A-Z_]{3,70}")) return message;
            }
        }
        return "BACKEND_STARTUP_FAILED";
    }

    public static String nextStep(String code) {
        return switch(code) {
            case "DESKTOP_SETUP_REQUIRED", "DESKTOP_CONFIG_CORRUPT",
                 "DESKTOP_CONFIG_CANNOT_DECRYPT", "DESKTOP_CONFIG_VERSION_INVALID" ->
                "Run tools/provision_dbpulse_windows_app.ps1 to enroll the metadata password for the same Windows user. Do not delete your SQL DB or PostgreSQL data.";
            case "METADATA_DB_UNREACHABLE" ->
                "Start the existing dbpulse-postgres-1 Docker container and verify 127.0.0.1:5544. Do not create a new empty database.";
            case "METADATA_DB_PASSWORD_REJECTED" ->
                "Verify the actual password of the EXISTING DBPulse PostgreSQL database. Do not reset the data volume.";
            case "METADATA_DATABASE_NOT_FOUND" ->
                "Check that the existing dbpulse PostgreSQL database is running on 127.0.0.1:5544.";
            case "PORT_18081_ALREADY_IN_USE" ->
                "Another program is using 18081. If it is DBPulse, close its original console or try reopening once it is ready. Do not terminate unrelated Java processes.";
            default ->
                "Open DBPulse in a PowerShell window to inspect the original startup exception. Hide passwords and SQL text before sharing.";
        };
    }

    public static void displayFailure(String code) {
        String advice = nextStep(code);
        String statusPath;
        try { statusPath = statusFile().toString(); }
        catch (RuntimeException ignored) { statusPath = "%LOCALAPPDATA%\\DBPulse\\logs"; }
        System.err.println("DBPulse failed to start: " + code);
        System.err.println(advice);
        System.err.println("Safe startup status: " + statusPath);
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null,
                    "DBPulse could not start.\n\nStatus: " + code
                    + "\n\n" + advice
                    + "\n\nSafe status file:\n" + statusPath,
                    "DBPulse Startup Error", JOptionPane.ERROR_MESSAGE);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A console log and status file are sufficient fallback.
        }
    }
}
