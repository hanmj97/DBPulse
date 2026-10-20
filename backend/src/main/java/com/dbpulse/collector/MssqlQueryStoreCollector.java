package com.dbpulse.collector;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import com.dbpulse.service.QueryDiagnosis;
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

/** Reads Query Store summaries without fetching SQL text, parameters or plan XML.
 * Designed for SQL Server 2019+; requires read permissions on the Query Store views.
 */
@Component
public class MssqlQueryStoreCollector {
    private final MssqlTargetProperties props;

    private static final String STORE_STATE_SQL = """
        SELECT actual_state_desc
        FROM sys.database_query_store_options
        """;

    // Keep newest 20 and highest weighted duration 20 (deduped, max 40).
    // Query Store stats are window/cumulative counters and may be flushed asynchronously.
    // Do not present execution_count as requests performed in a 30-second polling interval.
    private static final String QUERY_STATS_SQL = """
        WITH aggregates AS (
          SELECT
            q.query_id,
            p.plan_id,
            SUM(rs.count_executions) AS execution_count,
            SUM(rs.avg_duration * rs.count_executions) /
              NULLIF(SUM(rs.count_executions), 0) / 1000.0 AS avg_duration_ms,
            SUM(rs.avg_cpu_time * rs.count_executions) /
              NULLIF(SUM(rs.count_executions), 0) / 1000.0 AS avg_cpu_ms,
            SUM(rs.avg_logical_io_reads * rs.count_executions) /
              NULLIF(SUM(rs.count_executions), 0) AS avg_logical_reads_pages,
            MAX(rs.last_execution_time) AS last_executed_at,
            SUM(rs.avg_duration * rs.count_executions) AS weighted_duration,
            DATEDIFF_BIG(SECOND, '19700101',
              CAST(SWITCHOFFSET(MAX(rs.last_execution_time), '+00:00') AS datetime2))
              AS last_executed_epoch
          FROM sys.query_store_query q
          JOIN sys.query_store_plan p ON p.query_id = q.query_id
          JOIN sys.query_store_runtime_stats rs ON rs.plan_id = p.plan_id
          WHERE rs.last_execution_time >= DATEADD(MINUTE, -15, SYSDATETIMEOFFSET())
            AND rs.execution_type = 0
          GROUP BY q.query_id, p.plan_id
          HAVING SUM(rs.count_executions) > 0
        ),
        ranked AS (
          SELECT *,
            ROW_NUMBER() OVER (
              ORDER BY last_executed_at DESC, query_id DESC, plan_id DESC
            ) AS recent_rank,
            ROW_NUMBER() OVER (
              ORDER BY weighted_duration DESC, query_id DESC, plan_id DESC
            ) AS workload_rank
          FROM aggregates
        )
        SELECT TOP (40)
          query_id, plan_id, execution_count, avg_duration_ms, avg_cpu_ms,
          avg_logical_reads_pages, last_executed_epoch
        FROM ranked
        WHERE recent_rank <= 20 OR workload_rank <= 20
        ORDER BY last_executed_at DESC, query_id DESC, plan_id DESC
        """;

    public MssqlQueryStoreCollector(MssqlTargetProperties props) {
        this.props = props;
    }

    public QueryStoreResult collect() throws SQLException {
        if (props.getUsername() == null || props.getUsername().isBlank()
                || props.getPassword() == null || props.getPassword().isBlank()) {
            throw new IllegalStateException("MSSQL_USERNAME_OR_PASSWORD_MISSING");
        }
        if (props.getPort() < 1 || props.getPort() > 65535) {
            throw new IllegalStateException("INVALID_MSSQL_PORT");
        }
        String url = "jdbc:sqlserver://" + props.getHost() + ":" + props.getPort()
            + ";databaseName=" + props.getDatabase()
            + ";encrypt=true;trustServerCertificate=" + props.isTrustServerCertificate()
            + ";loginTimeout=3;applicationIntent=ReadOnly;applicationName=DBPulseQueryStore";
        Properties auth = new Properties();
        auth.setProperty("user", props.getUsername());
        auth.setProperty("password", props.getPassword());
        try (Connection conn = DriverManager.getConnection(url, auth)) {
            conn.setReadOnly(true);
            String state;
            try (PreparedStatement stmt = conn.prepareStatement(STORE_STATE_SQL)) {
                stmt.setQueryTimeout(5);
                try (ResultSet rs = stmt.executeQuery()) {
                    state = rs.next() ? rs.getString("actual_state_desc") : "OFF";
                }
            }
            if (!"READ_WRITE".equals(state) && !"READ_ONLY".equals(state)) {
                return new QueryStoreResult("QUERY_STORE_DISABLED", List.of());
            }
            Instant collectedAt = Instant.now();
            List<QueryPerformanceSnapshot> rows = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(QUERY_STATS_SQL)) {
                stmt.setQueryTimeout(8);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        double duration = rs.getDouble("avg_duration_ms");
                        double cpu = rs.getDouble("avg_cpu_ms");
                        double reads = rs.getDouble("avg_logical_reads_pages");
                        rows.add(new QueryPerformanceSnapshot(collectedAt,
                            rs.getLong("query_id"), rs.getLong("plan_id"),
                            rs.getLong("execution_count"), duration, cpu, reads,
                            Instant.ofEpochSecond(rs.getLong("last_executed_epoch")),
                            QueryDiagnosis.classify(duration, cpu, reads)));
                    }
                }
            }
            return new QueryStoreResult(state.equals("READ_ONLY") ? "QUERY_STORE_READ_ONLY" : "ONLINE", rows);
        }
    }

    public record QueryStoreResult(String status, List<QueryPerformanceSnapshot> rows) {}
}
