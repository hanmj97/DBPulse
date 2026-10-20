package com.dbpulse.collector;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.service.QueryTextSnippet;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * Explicitly requested Query Store SQL, read-only. Raw SQL is returned only
 * through the gated controller, never logged, scheduled or persisted.
 */
@Component
public class MssqlQueryTextCollector {
    // Fetch at most MAX+1 UTF-16 code units; no full SQL text crosses JDBC
    // when the stored statement exceeds the UI's maximum display length.
    private static final String QUERY_TEXT_SQL = """
        /* DBPULSE_INTERNAL_QUERY_TEXT_V1 */
        SELECT TOP (1)
            SUBSTRING(qt.query_sql_text, 1, ?) AS captured_text,
            DATALENGTH(qt.query_sql_text) AS original_bytes
        FROM sys.query_store_query q
        JOIN sys.query_store_plan p ON p.query_id = q.query_id
        JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
        WHERE q.query_id = ? AND p.plan_id = ?
        """;
    private final MssqlTargetProperties target;

    public MssqlQueryTextCollector(MssqlTargetProperties target) {
        this.target = target;
    }

    public QueryTextSnippet.Result read(long queryId, long planId) throws SQLException {
        if (queryId <= 0 || planId <= 0) throw new IllegalArgumentException("INVALID_QUERY_PLAN_ID");
        if (!target.isEnabled() || target.getUsername() == null || target.getUsername().isBlank()
            || target.getPassword() == null || target.getPassword().isBlank()) {
            throw new IllegalStateException("MSSQL_NOT_CONFIGURED");
        }
        if (target.getPort() < 1 || target.getPort() > 65535)
            throw new IllegalStateException("INVALID_MSSQL_PORT");

        String url = "jdbc:sqlserver://" + target.getHost() + ":" + target.getPort()
            + ";databaseName=" + target.getDatabase()
            + ";encrypt=true;trustServerCertificate=" + target.isTrustServerCertificate()
            + ";loginTimeout=3;applicationIntent=ReadOnly;applicationName=DBPulseQueryText";
        Properties auth = new Properties();
        auth.setProperty("user", target.getUsername());
        auth.setProperty("password", target.getPassword());
        try (Connection conn = DriverManager.getConnection(url, auth)) {
            conn.setReadOnly(true);
            try (PreparedStatement stmt = conn.prepareStatement(QUERY_TEXT_SQL)) {
                stmt.setQueryTimeout(5);
                stmt.setInt(1, QueryTextSnippet.MAX_CHARACTERS + 1);
                stmt.setLong(2, queryId);
                stmt.setLong(3, planId);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) return QueryTextSnippet.unavailable(queryId, planId);
                    return QueryTextSnippet.from(queryId, planId,
                        rs.getString("captured_text"), rs.getLong("original_bytes"));
                }
            }
        }
    }
}
