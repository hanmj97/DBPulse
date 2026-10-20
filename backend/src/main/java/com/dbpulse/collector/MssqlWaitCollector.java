package com.dbpulse.collector;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.WaitObservation;
import com.dbpulse.service.WaitCategory;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * Bounded point-in-time DMV wait observations.
 * Resource type is reduced server-side; raw wait_resource never leaves SQL Server.
 * This is not cumulative sys.dm_os_wait_stats and not a root-cause verdict.
 */
@Component
public class MssqlWaitCollector {
    private static final String SQL = """
        /* DBPULSE_INTERNAL_WAITS_V1 */
        SELECT TOP (100)
            r.session_id, r.wait_type, CONVERT(BIGINT, r.wait_time) AS wait_ms,
            CONVERT(BIGINT, r.total_elapsed_time) AS elapsed_ms,
            r.status AS request_status, r.blocking_session_id,
            CASE
              WHEN r.wait_resource LIKE N'KEY:%' THEN 'KEY'
              WHEN r.wait_resource LIKE N'PAGE:%' THEN 'PAGE'
              WHEN r.wait_resource LIKE N'RID:%' THEN 'RID'
              WHEN r.wait_resource LIKE N'OBJECT:%' THEN 'OBJECT'
              WHEN r.wait_resource LIKE N'HOBT:%' THEN 'HOBT'
              WHEN r.wait_resource LIKE N'DATABASE:%' THEN 'DATABASE'
              WHEN r.wait_resource LIKE N'XACT:%' THEN 'TRANSACTION'
              WHEN r.wait_resource LIKE N'METADATA:%' THEN 'METADATA'
              WHEN r.wait_resource LIKE N'APPLICATION:%' THEN 'APPLICATION'
              WHEN r.wait_resource LIKE N'ALLOCATION_UNIT:%' THEN 'ALLOCATION_UNIT'
              WHEN r.wait_resource LIKE N'FILE:%' THEN 'FILE'
              ELSE 'UNSPECIFIED'
            END AS resource_kind
        FROM sys.dm_exec_requests AS r
        WHERE r.database_id = DB_ID()
          AND r.session_id <> @@SPID
          AND r.wait_type IS NOT NULL
          AND r.wait_time > 0
        ORDER BY r.wait_time DESC, r.session_id ASC
        """;
    private final MssqlTargetProperties target;

    public MssqlWaitCollector(MssqlTargetProperties target) {
        this.target = target;
    }

    public WaitSample collect() throws SQLException {
        if (target.getUsername() == null || target.getUsername().isBlank()
            || target.getPassword() == null || target.getPassword().isBlank()) {
            throw new IllegalStateException("MSSQL_CREDENTIALS_NOT_CONFIGURED");
        }
        if (target.getPort() < 1 || target.getPort() > 65535) {
            throw new IllegalStateException("INVALID_MSSQL_PORT");
        }
        String url = "jdbc:sqlserver://" + target.getHost() + ":" + target.getPort()
            + ";databaseName=" + target.getDatabase()
            + ";encrypt=true;trustServerCertificate=" + target.isTrustServerCertificate()
            + ";loginTimeout=3;applicationIntent=ReadOnly;applicationName=DBPulseWaits";
        Properties auth = new Properties();
        auth.setProperty("user", target.getUsername());
        auth.setProperty("password", target.getPassword());
        List<WaitObservation> observations = new ArrayList<>();
        try (var conn = DriverManager.getConnection(url, auth)) {
            conn.setReadOnly(true);
            try (var statement = conn.prepareStatement(SQL)) {
                statement.setQueryTimeout(4);
                try (var rs = statement.executeQuery()) {
                    while (rs.next()) {
                        int blocker = rs.getInt("blocking_session_id");
                        observations.add(new WaitObservation(
                            rs.getInt("session_id"), rs.getString("wait_type"),
                            WaitCategory.classify(rs.getString("wait_type")),
                            rs.getString("resource_kind"), rs.getLong("wait_ms"),
                            rs.getLong("elapsed_ms"), rs.getString("request_status"),
                            blocker > 0 ? blocker : null));
                    }
                }
            }
        }
        return new WaitSample(Instant.now(), List.copyOf(observations),
            observations.size() == 100);
    }

    public record WaitSample(Instant collectedAt, List<WaitObservation> observations,
                             boolean limitReached) {}
}
