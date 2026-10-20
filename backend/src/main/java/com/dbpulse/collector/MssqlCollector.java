package com.dbpulse.collector;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.MetricSnapshot;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Properties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class MssqlCollector implements DatabaseCollector {
    private final MssqlTargetProperties props;
    private final int slowThresholdMs;

    // No SQL text or parameter values leave the monitored DB during phase 1.
    // sys.dm_exec_requests contains IN-FLIGHT requests, not all completed queries.
    private static final String METRIC_SQL = """
        /* DBPULSE_INTERNAL_METRICS_V1 */
        SELECT
            COUNT_BIG(*) AS active_requests,
            COALESCE(SUM(CASE WHEN r.blocking_session_id > 0 THEN CONVERT(BIGINT, 1) ELSE 0 END), 0) AS blocked_requests,
            COALESCE(SUM(CASE WHEN r.total_elapsed_time >= ? THEN CONVERT(BIGINT, 1) ELSE 0 END), 0) AS long_running_requests,
            COALESCE(MAX(CONVERT(BIGINT, r.total_elapsed_time)), 0) AS max_elapsed_ms,
            COALESCE(SUM(CONVERT(BIGINT, r.cpu_time)), 0) AS total_cpu_ms
        FROM sys.dm_exec_requests r
        WHERE r.database_id = DB_ID()
          AND r.session_id <> @@SPID
        """;

    public MssqlCollector(MssqlTargetProperties props,
                          @Value("${dbpulse.collector.slow-threshold-ms:1000}") int slowThresholdMs) {
        this.props = props;
        this.slowThresholdMs = slowThresholdMs;
    }

    @Override
    public MetricSnapshot collect() throws Exception {
        if (props.getUsername().isBlank() || props.getPassword().isBlank()) {
            throw new IllegalStateException("MSSQL_USERNAME_OR_PASSWORD_MISSING");
        }
        if (props.getPort() < 1 || props.getPort() > 65535) {
            throw new IllegalArgumentException("INVALID_MSSQL_PORT");
        }
        String jdbcUrl = "jdbc:sqlserver://" + props.getHost() + ":" + props.getPort()
            + ";databaseName=" + props.getDatabase()
            + ";encrypt=true;trustServerCertificate=" + props.isTrustServerCertificate()
            + ";loginTimeout=3;applicationIntent=ReadOnly;applicationName=DBPulseCollector";
        Properties auth = new Properties();
        auth.setProperty("user", props.getUsername());
        auth.setProperty("password", props.getPassword());
        try (Connection conn = DriverManager.getConnection(jdbcUrl, auth);
             PreparedStatement stmt = conn.prepareStatement(METRIC_SQL)) {
            conn.setReadOnly(true);
            stmt.setQueryTimeout(3);
            stmt.setInt(1, slowThresholdMs);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return new MetricSnapshot(Instant.now(), rs.getLong("active_requests"),
                    rs.getLong("blocked_requests"), rs.getLong("long_running_requests"),
                    rs.getLong("max_elapsed_ms"), rs.getLong("total_cpu_ms"));
            }
        }
    }
}
