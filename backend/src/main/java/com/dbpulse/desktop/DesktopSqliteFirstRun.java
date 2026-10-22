package com.dbpulse.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Bootstrap ONLY for an empty Windows user profile. Historical DPAPI,
 * PostgreSQL/SQLite metadata and files are never overwritten or migrated.
 * DesktopStartupDiagnostics may already have written its known log marker.
 */
public final class DesktopSqliteFirstRun {
    private static final String SWITCH = "DBPULSE_SQLITE_DESKTOP_V1\n";
    private DesktopSqliteFirstRun() {}

    static boolean isPristine(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(root)) return false;
        try (var listing = Files.list(root)) {
            List<Path> entries = listing.toList();
            if (entries.isEmpty()) return true;
            if (entries.size() != 1 || !entries.getFirst().getFileName().toString().equals("logs"))
                return false;
            Path logs = entries.getFirst();
            if (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(logs)) return false;
            try (var logListing = Files.list(logs)) {
                List<Path> logFiles = logListing.toList();
                return logFiles.stream().allMatch(p ->
                    "desktop-startup-status.txt".equals(p.getFileName().toString())
                    && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(p));
            }
        }
    }

    /**
     * The caller uses this before trying the legacy PG configuration.
     * Existing directories with any unrelated file are deliberately ignored.
     * A half-written/foreign SQLite file is never repaired or replaced.
     */
    public static boolean initializeIfPristine(Path root) {
        Path temp = null;
        try {
            if (!isPristine(root)) return false;
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root);
            Path data = root.resolve("data");
            Path desktop = root.resolve("desktop");
            if (Files.exists(data, LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(desktop, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Files.createDirectory(data);
            Files.createDirectory(desktop);
            temp = Files.createTempFile(data, "dbpulse-new-", ".partial.sqlite");
            // The .sqlite resource contains fixed trusted CREATE TABLE/INDEX
            // statements only; no user input or SQL Server data is copied.
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + temp.toAbsolutePath())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys=ON");
                }
                ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("schema-sqlite.sql"));
                try (Statement statement = connection.createStatement();
                     ResultSet check = statement.executeQuery("PRAGMA quick_check")) {
                    if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) {
                        throw new IllegalStateException("DESKTOP_SQLITE_FIRST_RUN_FAILED");
                    }
                }
            }
            Path target = data.resolve("dbpulse.sqlite");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("DESKTOP_SQLITE_FIRST_RUN_FAILED");
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IllegalStateException("DESKTOP_SQLITE_FIRST_RUN_ATOMIC_MOVE_REQUIRED");
            }
            temp = null;
            // Publish marker LAST. A crash before this point fails closed:
            // subsequent launches will not auto-initialize non-pristine data.
            Files.writeString(desktop.resolve("sqlite-mode.enabled"), SWITCH,
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            System.out.println("DBPULSE_FRESH_USER_SQLITE_INITIALIZED");
            return true;
        } catch (IOException | SQLException | RuntimeException failure) {
            // No raw filesystem/SQL exception details are exposed.
            throw new IllegalStateException("DESKTOP_SQLITE_FIRST_RUN_FAILED");
        } finally {
            // Own temporary bootstrap file only; never delete an existing DB.
            if (temp != null) try { Files.deleteIfExists(temp); }
                catch (IOException ignored) {}
        }
    }
}
