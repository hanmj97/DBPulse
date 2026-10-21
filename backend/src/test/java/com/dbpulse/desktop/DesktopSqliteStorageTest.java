package com.dbpulse.desktop;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSqliteStorageTest {
    @TempDir Path temp;

    @Test void requiresExplicitMarkerAndRejectsInvalidFile() throws Exception {
        assertFalse(DesktopSqliteStorage.isOptedIn(temp));
        Path marker=DesktopSqliteStorage.marker(temp);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker,"bad switch\n",StandardCharsets.US_ASCII);
        assertThrows(IllegalStateException.class,()->DesktopSqliteStorage.isOptedIn(temp));
        Files.writeString(marker,"DBPULSE_SQLITE_DESKTOP_V1\n",StandardCharsets.US_ASCII);
        assertTrue(DesktopSqliteStorage.isOptedIn(temp));
        assertThrows(IllegalStateException.class,()->DesktopSqliteStorage.configure(temp,new HashMap<>()));
    }

    @Test void existingDatabaseIsValidatedReadOnlyAndConfiguredWithoutPostgres() throws Exception {
        Path file=DesktopSqliteStorage.database(temp);
        Files.createDirectories(file.getParent());
        String[] tables={"local_target_registry","metric_snapshots","query_performance_snapshots",
            "incidents","blocking_chain_samples","blocking_chain_edges","deadlock_events",
            "deadlock_participants","wait_samples","wait_observations"};
        try (var db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
            for(String table:tables) {
                db.createStatement().execute("CREATE TABLE "+table+"(id INTEGER PRIMARY KEY)");
            }
        }
        long size=Files.size(file);
        Map<String,Object> props=new HashMap<>();
        DesktopSqliteStorage.configure(temp, props);
        assertEquals("org.sqlite.JDBC",props.get("spring.datasource.driver-class-name"));
        assertEquals("never",props.get("spring.sql.init.mode"));
        assertEquals("PRAGMA foreign_keys=ON",props.get("spring.datasource.hikari.connection-init-sql"));
        assertFalse(props.toString().contains("postgresql"));
        assertEquals(size,Files.size(file));
    }
}
