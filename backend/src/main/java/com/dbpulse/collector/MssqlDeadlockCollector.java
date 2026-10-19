package com.dbpulse.collector;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.DeadlockEvent;
import com.dbpulse.domain.DeadlockParticipant;
import com.dbpulse.service.DeadlockEventKey;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * Reads only sanitized columns derived from system_health XEL inside SQL Server.
 * Event XML never reaches the Java application and is never stored in PostgreSQL.
 *
 * This is instance-wide XEL source, but only participating processes whose
 * currentdb matches this connection's DB_ID() are returned.
 */
@Component
public class MssqlDeadlockCollector {
    private static final String SQL = """
        WITH recent AS (
            SELECT TOP (100)
                e.file_name, e.file_offset, e.timestamp_utc,
                TRY_CAST(e.event_data AS xml) AS evt
            FROM sys.fn_xe_file_target_read_file('system_health*.xel', NULL, NULL, NULL) AS e
            WHERE e.object_name = N'xml_deadlock_report'
              AND e.timestamp_utc >= DATEADD(HOUR, -1, SYSUTCDATETIME())
            ORDER BY e.timestamp_utc DESC
        )
        SELECT
            d.file_name, d.file_offset, d.timestamp_utc,
            p.process_node.value('(@spid)[1]', 'int') AS session_id,
            CASE WHEN p.process_node.value('(@id)[1]', 'nvarchar(128)') =
                      d.evt.value('(/event/data/value/deadlock/victim-list/victimProcess/@id)[1]', 'nvarchar(128)')
                 THEN CAST(1 AS bit) ELSE CAST(0 AS bit) END AS is_victim
        FROM recent AS d
        CROSS APPLY d.evt.nodes('/event/data/value/deadlock/process-list/process') AS p(process_node)
        WHERE p.process_node.value('(@currentdb)[1]', 'int') = DB_ID()
        ORDER BY d.timestamp_utc DESC, d.file_offset, session_id
        """;

    private final MssqlTargetProperties target;

    public MssqlDeadlockCollector(MssqlTargetProperties target) {
        this.target = target;
    }

    public List<DeadlockEvent> collect() throws SQLException {
        if (target.getUsername() == null || target.getUsername().isBlank()
            || target.getPassword() == null || target.getPassword().isBlank()) {
            throw new IllegalStateException("MSSQL_CREDENTIALS_NOT_CONFIGURED");
        }
        String url = "jdbc:sqlserver://" + target.getHost() + ":" + target.getPort()
            + ";databaseName=" + target.getDatabase()
            + ";encrypt=true;trustServerCertificate=" + target.isTrustServerCertificate()
            + ";loginTimeout=3;applicationIntent=ReadOnly;applicationName=DBPulseDeadlocks";
        Properties auth = new Properties();
        auth.setProperty("user", target.getUsername());
        auth.setProperty("password", target.getPassword());
        Map<String, EventBuilder> events = new LinkedHashMap<>();
        try (var conn = DriverManager.getConnection(url, auth)) {
            conn.setReadOnly(true);
            try (var statement = conn.prepareStatement(SQL)) {
                statement.setQueryTimeout(8);
                try (var rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String key = DeadlockEventKey.fromLocation(
                            rs.getString("file_name"), rs.getLong("file_offset"));
                        var event = events.get(key);
                        if (event == null) {
                            LocalDateTime utc = rs.getObject("timestamp_utc", LocalDateTime.class);
                            event = new EventBuilder(key, utc.toInstant(ZoneOffset.UTC));
                            events.put(key, event);
                        }
                        event.participants.add(new DeadlockParticipant(
                            rs.getInt("session_id"), rs.getBoolean("is_victim")));
                    }
                }
            }
        }
        return events.values().stream().map(EventBuilder::build).toList();
    }

    private static final class EventBuilder {
        final String key;
        final java.time.Instant occurred;
        final List<DeadlockParticipant> participants = new ArrayList<>();
        EventBuilder(String key, java.time.Instant occurred) {
            this.key = key;
            this.occurred = occurred;
        }
        DeadlockEvent build() { return new DeadlockEvent(key, occurred, participants); }
    }
}
