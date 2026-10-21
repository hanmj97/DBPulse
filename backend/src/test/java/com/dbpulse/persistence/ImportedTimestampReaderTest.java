package com.dbpulse.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Mirrors the actual imported staging string without editing a user database. */
class ImportedTimestampReaderTest {
    @Test void importedOffsetMicrosecondsAndNativeJdbcTimestamps() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = db.createStatement()) {
            statement.executeUpdate("CREATE TABLE sample (t TIMESTAMP)");
            statement.executeUpdate("INSERT INTO sample(t) VALUES "
                + "('2026-10-21T04:04:56.524781+00:00'), "
                + "('2026-10-21T13:04:56.524781+09:00'), "
                + "('2026-10-21 04:04:56.524'), (NULL)");
            try (var rs = statement.executeQuery("SELECT t FROM sample ORDER BY rowid")) {
                assertTrue(rs.next());
                assertEquals(Instant.parse("2026-10-21T04:04:56.524781Z"),
                    ImportedTimestampReader.instant(rs, "t"));
                assertTrue(rs.next());
                assertEquals(Instant.parse("2026-10-21T04:04:56.524781Z"),
                    ImportedTimestampReader.instant(rs, "t"));
                assertTrue(rs.next());
                assertEquals(Instant.parse("2026-10-21T04:04:56.524Z"),
                    ImportedTimestampReader.instant(rs, "t"));
                assertTrue(rs.next());
                assertNull(ImportedTimestampReader.instantOrNull(rs, "t"));
                assertThrows(java.sql.SQLException.class,
                    () -> ImportedTimestampReader.instant(rs, "t"));
            }
        }
    }
}
