package com.dbpulse.desktop;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Zero-write inventory for planning a future PostgreSQL -> SQLite migration.
 * Counts contain no SQL text, customer data, login details or passwords.
 * No database switching or automatic backup/migration is performed.
 */
public final class MetadataInventory {
    public static final String[] TABLES = {
        "local_target_registry",
        "metric_snapshots",
        "query_performance_snapshots",
        "incidents",
        "blocking_chain_samples",
        "blocking_chain_edges",
        "deadlock_events",
        "deadlock_participants",
        "wait_samples",
        "wait_observations"
    };

    private MetadataInventory() {}

    /** Exact allowlist avoids accepting SQL identifiers or arbitrary SQL. */
    public static Map<String, Long> counts(Connection db) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            try (Statement statement = db.createStatement();
                 var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                if (!rs.next()) throw new SQLException("MISSING_TABLE_COUNT");
                counts.put(table, rs.getLong(1));
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Local signed-in-Windows-user metadata read-only report. */
    public static void printExistingPostgresInventory() {
        // Do not inventory while a local DBPulse backend may still be writing.
        // PostgreSQL snapshot transaction still protects internal consistency.
        if (DesktopInstanceHandoff.inspect()
            != DesktopInstanceHandoff.Result.FREE) {
            throw new IllegalStateException("STOP_DBPULSE_BEFORE_STORAGE_INVENTORY");
        }
        String password = WindowsDesktopBoot.loadMetadataPasswordForInventory();
        Properties credentials = new Properties();
        credentials.setProperty("user", "dbpulse");
        credentials.setProperty("password", password);
        try (Connection db = DriverManager.getConnection(
            "jdbc:postgresql://127.0.0.1:5544/dbpulse", credentials)) {
            db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            db.setReadOnly(true);
            db.setAutoCommit(false);
            Map<String, Long> result = counts(db);
            db.rollback();
            System.out.println("DBPulse metadata PostgreSQL read-only inventory");
            long total = 0L;
            for (var entry : result.entrySet()) {
                System.out.println(entry.getKey() + ": " + entry.getValue());
                total += entry.getValue();
            }
            System.out.println("TOTAL_METADATA_ROWS: " + total);
            System.out.println("NO_DATA_COPIED_OR_MODIFIED");
            System.out.println("SQLITE_MIGRATION_NOT_STARTED");
        } catch (SQLException failure) {
            // Database/credential SQL exceptions can contain private details.
            // Do NOT propagate or log their raw text to the user.
            throw new IllegalStateException("POSTGRES_INVENTORY_UNAVAILABLE");
        }
    }
}
