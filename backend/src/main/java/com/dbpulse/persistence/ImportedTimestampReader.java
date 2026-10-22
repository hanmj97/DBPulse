package com.dbpulse.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Read historic PostgreSQL-to-SQLite staging timestamps without losing their
 * UTC offsets or microseconds. Postgres and native sqlite-jdbc timestamps
 * continue to use the original JDBC conversion path.
 *
 * This is a read compatibility layer ONLY. Mixed timestamp write formats,
 * retention cutoff comparisons and desktop SQLite cutover require separate
 * validation before any production switch.
 */
public final class ImportedTimestampReader {
    private ImportedTimestampReader() {}

    public static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant();
        } catch (DateTimeParseException ignored) {
            // Postgres and sqlite-jdbc's native timestamp strings use their
            // existing JDBC representation; preserve established behavior.
        }
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Instant value = instantOrNull(rs, column);
        if (value == null) throw new SQLException("DBPULSE_REQUIRED_TIMESTAMP_IS_NULL", "22007");
        return value;
    }
}
