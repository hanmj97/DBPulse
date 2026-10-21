package com.dbpulse.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL retains its normal JDBC TIMESTAMPTZ binding. Only SQLite stores
 * all NEW metadata timestamps in the same UTC ISO-8601 text family as the
 * exact-offset rows imported from PostgreSQL (Phase 15 staged migration).
 * Comparisons and indexes over ISO UTC TEXT then work on historic/new rows.
 * The original imported file is never mutated by this codec.
 */
public final class MetadataTimestampValue {
    private static final DateTimeFormatter SQLITE_UTC = new DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)
        .appendLiteral("+00:00")
        .toFormatter(Locale.ROOT).withZone(ZoneOffset.UTC);

    private MetadataTimestampValue() {}

    public static boolean isSqlite(JdbcTemplate jdbc) {
        Boolean result = jdbc.execute((ConnectionCallback<Boolean>) connection ->
            "SQLite".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()));
        if (result == null) throw new IllegalStateException("DBPULSE_METADATA_DATABASE_UNKNOWN");
        return result;
    }

    public static Object value(boolean sqlite, Instant instant) {
        if (instant == null) throw new IllegalArgumentException("METADATA_TIMESTAMP_REQUIRED");
        return sqlite ? SQLITE_UTC.format(instant) : Timestamp.from(instant);
    }
}
