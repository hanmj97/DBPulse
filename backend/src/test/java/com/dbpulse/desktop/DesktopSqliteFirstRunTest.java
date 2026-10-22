package com.dbpulse.desktop;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** All tests use temporary standalone files. No real user's DBPulse data. */
class DesktopSqliteFirstRunTest {
    @TempDir Path tmp;

    @Test void initializesOnlyAbsentProfileWithTenEmptyTablesAndValidMarker() throws Exception {
        Path userDir = tmp.resolve("new-profile");
        assertTrue(DesktopSqliteFirstRun.initializeIfPristine(userDir));
        assertTrue(DesktopSqliteStorage.isOptedIn(userDir));
        var props = new HashMap<String,Object>();
        DesktopSqliteStorage.configure(userDir,props);
        assertEquals("never", props.get("spring.sql.init.mode"));
        assertTrue(String.valueOf(props.get("spring.datasource.url")).contains("dbpulse.sqlite"));
        try (var connection=DriverManager.getConnection(
                "jdbc:sqlite:" + DesktopSqliteStorage.database(userDir))) {
            try (var check=connection.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM local_target_registry")) {
                assertEquals(0,check.getInt(1));
            }
        }
        // Second run must not overwrite the initialized file.
        long before=Files.size(DesktopSqliteStorage.database(userDir));
        assertFalse(DesktopSqliteFirstRun.initializeIfPristine(userDir));
        assertEquals(before,Files.size(DesktopSqliteStorage.database(userDir)));
    }

    @Test void startupDiagnosticsOnlyIsAllowed() throws Exception {
        Path userDir=tmp.resolve("startup-logged");
        Path logs=userDir.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("desktop-startup-status.txt"),"STARTING\n");
        assertTrue(DesktopSqliteFirstRun.initializeIfPristine(userDir));
        assertTrue(Files.exists(logs.resolve("desktop-startup-status.txt")));
    }

    @Test void existingLegacyDpapiIsNeverChangedOrConverted() throws Exception {
        Path userDir=tmp.resolve("legacy");
        Files.createDirectories(userDir.resolve("desktop"));
        Path secret=userDir.resolve("desktop/metadata.dpapi");
        Files.writeString(secret,"test-secret-must-remain",StandardCharsets.UTF_8);
        assertFalse(DesktopSqliteFirstRun.initializeIfPristine(userDir));
        assertEquals("test-secret-must-remain",Files.readString(secret));
        assertFalse(Files.exists(DesktopSqliteStorage.database(userDir)));
    }

    @Test void existingOrDamagedSqliteIsNeverReset() throws Exception {
        Path userDir=tmp.resolve("damaged");
        Files.createDirectories(userDir.resolve("data"));
        Path db=DesktopSqliteStorage.database(userDir);
        Files.writeString(db,"DO_NOT_OVERWRITE");
        assertFalse(DesktopSqliteFirstRun.initializeIfPristine(userDir));
        assertEquals("DO_NOT_OVERWRITE",Files.readString(db));
    }

    @Test void unexpectedExistingLogFileBlocksFirstRun() throws Exception {
        Path userDir=tmp.resolve("unexpected-log");
        Files.createDirectories(userDir.resolve("logs"));
        Files.writeString(userDir.resolve("logs/private.log"),"PRESERVE");
        assertFalse(DesktopSqliteFirstRun.initializeIfPristine(userDir));
        assertFalse(Files.exists(DesktopSqliteStorage.database(userDir)));
        assertEquals("PRESERVE",Files.readString(userDir.resolve("logs/private.log")));
    }

    @Test void emptyUserRootIsAllowed() {
        Path userDir=tmp.resolve("blank");
        assertDoesNotThrow(()->Files.createDirectory(userDir));
        assertTrue(DesktopSqliteFirstRun.initializeIfPristine(userDir));
    }
}
