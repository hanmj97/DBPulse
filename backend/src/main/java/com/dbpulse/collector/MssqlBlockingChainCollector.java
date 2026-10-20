package com.dbpulse.collector;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.BlockingEdge;
import com.dbpulse.service.BlockingChainResolver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * Read-only per-request blocking relationships. Sleeping blockers are surfaced
 * by joining sys.dm_exec_sessions instead of requiring an active request.
 * Does not fetch SQL text, login names, hostnames or plan XML.
 */
@Component
public class MssqlBlockingChainCollector {
    private final MssqlTargetProperties target;

    // The monitored database is determined by this connection's databaseName.
    // Negative blocking_session_id special cases are not represented as session edges.
    private static final String SQL = """
        /* DBPULSE_INTERNAL_BLOCKING_V1 */
        SELECT TOP (100)
            r.session_id AS blocked_session_id,
            r.blocking_session_id,
            COALESCE(r.wait_type, 'UNKNOWN') AS wait_type,
            CONVERT(BIGINT, r.wait_time) AS wait_ms,
            CONVERT(BIGINT, r.total_elapsed_time) AS elapsed_ms,
            r.status AS blocked_status,
            COALESCE(s.status, 'UNKNOWN') AS blocker_status,
            COALESCE(s.open_transaction_count, 0) AS blocker_open_transactions
        FROM sys.dm_exec_requests AS r
        LEFT JOIN sys.dm_exec_sessions AS s
          ON s.session_id = r.blocking_session_id
        WHERE r.database_id = DB_ID()
          AND r.blocking_session_id > 0
          AND r.session_id <> @@SPID
        ORDER BY r.wait_time DESC, r.session_id
        """;

    public MssqlBlockingChainCollector(MssqlTargetProperties target) {
        this.target = target;
    }

    public BlockingSample collect() throws SQLException {
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
            + ";loginTimeout=3;applicationIntent=ReadOnly;applicationName=DBPulseBlockingChain";
        Properties auth = new Properties();
        auth.setProperty("user", target.getUsername());
        auth.setProperty("password", target.getPassword());
        List<BlockingEdge> edges = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, auth)) {
            conn.setReadOnly(true);
            try (PreparedStatement statement = conn.prepareStatement(SQL)) {
                statement.setQueryTimeout(4);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        edges.add(new BlockingEdge(
                            rs.getInt("blocked_session_id"), rs.getInt("blocking_session_id"),
                            rs.getString("wait_type"), rs.getLong("wait_ms"),
                            rs.getLong("elapsed_ms"), rs.getString("blocked_status"),
                            rs.getString("blocker_status"), rs.getInt("blocker_open_transactions"),
                            null, 0, false));
                    }
                }
            }
        }
        return new BlockingSample(Instant.now(), BlockingChainResolver.resolve(edges));
    }

    public record BlockingSample(Instant collectedAt, List<BlockingEdge> edges) {}
}
