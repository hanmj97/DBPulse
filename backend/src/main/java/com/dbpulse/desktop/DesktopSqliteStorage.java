package com.dbpulse.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.sqlite.SQLiteConfig;

/**
 * Explicit, fail-closed local SQLite metadata mode for the packaged Windows EXE.
 * The old PostgreSQL configuration is retained until the user activates the
 * switch through the developer migration tool. No auto migration or fallback.
 */
public final class DesktopSqliteStorage {
    private static final String MARKER = "DBPULSE_SQLITE_DESKTOP_V1\n";
    private static final String[] TABLES = {
        "local_target_registry", "metric_snapshots", "query_performance_snapshots",
        "incidents", "blocking_chain_samples", "blocking_chain_edges",
        "deadlock_events", "deadlock_participants", "wait_samples", "wait_observations"
    };

    private DesktopSqliteStorage() {}

    public static Path marker(Path userDbpulseDirectory) {
        return userDbpulseDirectory.resolve("desktop").resolve("sqlite-mode.enabled");
    }

    public static Path database(Path userDbpulseDirectory) {
        return userDbpulseDirectory.resolve("data").resolve("dbpulse.sqlite");
    }

    public static boolean isOptedIn(Path userDbpulseDirectory) {
        Path marker = marker(userDbpulseDirectory);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(marker)
                || Files.size(marker) > 100
                || !MARKER.equals(Files.readString(marker, StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("DESKTOP_SQLITE_SWITCH_INVALID");
            }
            return true;
        } catch (IOException failure) {
            throw new IllegalStateException("DESKTOP_SQLITE_SWITCH_INVALID");
        }
    }

    public static void configure(Path userDbpulseDirectory, Map<String, Object> props) {
        Path dataDir = userDbpulseDirectory.resolve("data");
        Path db = database(userDbpulseDirectory);
        try {
            if (Files.isSymbolicLink(userDbpulseDirectory)
                || Files.isSymbolicLink(dataDir)
                || !Files.isRegularFile(db, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(db)
                || Files.size(db) < 4096) {
                throw new IllegalStateException("DESKTOP_SQLITE_DATABASE_INVALID");
            }
            // Fail closed BEFORE Spring creates a datasource. Read-only verification
            // is intentionally bounded to the user's single verified local file.
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + db.toAbsolutePath(), config.toProperties());
                 Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
                    if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                        throw new IllegalStateException("DESKTOP_SQLITE_DATABASE_INVALID");
                    }
                }
                try (ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (result.next()) {
                        throw new IllegalStateException("DESKTOP_SQLITE_DATABASE_INVALID");
                    }
                }
                for (String table : TABLES) {
                    try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        if (!result.next()) {
                            throw new IllegalStateException("DESKTOP_SQLITE_DATABASE_INVALID");
                        }
                    }
                }
            }
        } catch (Exception failure) {
            // Do not echo JDBC error messages / sensitive local paths.
            throw new IllegalStateException("DESKTOP_SQLITE_DATABASE_INVALID");
        }

        props.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
        props.put("spring.datasource.url", "jdbc:sqlite:" + db.toAbsolutePath());
        props.put("spring.datasource.username", "");
        props.put("spring.datasource.password", "");
        props.put("spring.datasource.hikari.maximum-pool-size", 1);
        props.put("spring.datasource.hikari.minimum-idle", 1);
        props.put("spring.datasource.hikari.connection-init-sql", "PRAGMA foreign_keys=ON");
        props.put("spring.sql.init.mode", "never");
        System.out.println("DBPULSE_DESKTOP_SQLITE_MODE_ENABLED");
    }
}
