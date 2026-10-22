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
        /* DBPULSE_INTERNAL_QSTORE_STATE_V1 */
        SELECT actual_state_desc
        FROM sys.database_query_store_options
        """;

    // Filter and aggregate the recent Query Store runtime interval rows FIRST.
    // Only then match SQL fingerprints; no raw SQL text leaves SQL Server.
    // This reduces unnecessary wide query_text lookups across historical queries.
    // No hardcoded Query ID/Plan ID; customer databases assign their own IDs.
    private static final String QUERY_STATS_SQL = """
        /* DBPULSE_INTERNAL_QSTORE_STATS_V1 */
        WITH aggregates AS (
          SELECT
            p.query_id,
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
          FROM sys.query_store_plan p
          JOIN sys.query_store_runtime_stats rs ON rs.plan_id = p.plan_id
          WHERE rs.last_execution_time >= DATEADD(MINUTE, -15, SYSDATETIMEOFFSET())
            AND rs.execution_type = 0
          GROUP BY p.query_id, p.plan_id
          HAVING SUM(rs.count_executions) > 0
        ),
        classified AS (
          SELECT a.*,
            CASE
              WHEN CHARINDEX(N'DBPULSE_INTERNAL_', qt.query_sql_text) > 0
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.query_store_query_text%'
                AND qt.query_sql_text LIKE N'%SUBSTRING%'
                AND qt.query_sql_text LIKE N'%captured_text%'
                AND qt.query_sql_text LIKE N'%DATALENGTH%'
                AND qt.query_sql_text LIKE N'%original_bytes%'
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.dm_os_ring_buffers%'
                AND qt.query_sql_text LIKE N'%RING_BUFFER_SCHEDULER_MONITOR%'
                AND qt.query_sql_text LIKE N'%ProcessUtilization%'
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.database_query_store_options%'
                AND qt.query_sql_text LIKE N'%actual_state_desc%'
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.query_store_runtime_stats%'
                AND qt.query_sql_text LIKE N'%avg_logical_io_reads%'
                AND qt.query_sql_text LIKE N'%last_executed_epoch%'
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.dm_exec_requests%'
                AND qt.query_sql_text LIKE N'%active_requests%'
                AND qt.query_sql_text LIKE N'%blocked_requests%'
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.dm_exec_requests%'
                AND qt.query_sql_text LIKE N'%resource_kind%'
                AND qt.query_sql_text LIKE N'%wait_resource%'
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.dm_exec_requests%'
                AND qt.query_sql_text LIKE N'%blocker_open_transactions%'
                AND qt.query_sql_text LIKE N'%blocked_session_id%'
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.fn_xe_file_target_read_file%'
                AND qt.query_sql_text LIKE N'%xml_deadlock_report%'
                AND qt.query_sql_text LIKE N'%process_node%'
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.query_store_plan%'
                AND qt.query_sql_text LIKE N'%is_parallel_plan%'
                AND qt.query_sql_text LIKE N'%plan_bytes%'
              THEN 'DBPULSE_PATTERN'
              WHEN qt.query_sql_text LIKE N'%sys.query_store_plan%'
                AND qt.query_sql_text LIKE N'%@PhysicalOp%'
                AND qt.query_sql_text LIKE N'%RelOp%'
              THEN 'DBPULSE_PATTERN'
              ELSE 'OTHER_OR_UNKNOWN'
            END AS source_category
          FROM aggregates a
          JOIN sys.query_store_query q ON q.query_id = a.query_id
          JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
        ),
        ranked AS (
          SELECT *,
            ROW_NUMBER() OVER (
              PARTITION BY source_category
              ORDER BY last_executed_at DESC, query_id DESC, plan_id DESC
            ) AS recent_rank,
            ROW_NUMBER() OVER (
              PARTITION BY source_category
              ORDER BY weighted_duration DESC, query_id DESC, plan_id DESC
            ) AS workload_rank
          FROM classified
        )
        SELECT TOP (50)
          query_id, plan_id, source_category, execution_count, avg_duration_ms, avg_cpu_ms,
          avg_logical_reads_pages, last_executed_epoch
        FROM ranked
        WHERE (source_category = 'OTHER_OR_UNKNOWN'
                 AND (recent_rank <= 20 OR workload_rank <= 20))
           OR (source_category = 'DBPULSE_PATTERN' AND recent_rank <= 10)
        ORDER BY last_executed_at DESC, query_id DESC, plan_id DESC
        """;

    // Query Store CPU for the SELECTED database only. SQL Server Query Store
    // aggregates completed executions in runtime intervals. This is CPU
    // time, NOT an instantaneous per-database CPU utilization percentage.
    // Runtime intervals can extend outside the 15-minute last-execution filter.
    // No SQL text, plans or customer data are fetched.
    private static final String DATABASE_CPU_SQL = """
        /* DBPULSE_INTERNAL_QSTORE_DB_CPU_V1 */
        SELECT
          COALESCE(SUM(CAST(rs.avg_cpu_time AS float) *
                       CAST(rs.count_executions AS float)) / 1000.0, 0) AS recorded_cpu_ms,
          COALESCE(SUM(CAST(rs.count_executions AS bigint)), 0) AS recorded_executions
        FROM sys.query_store_runtime_stats rs
        WHERE rs.execution_type = 0
          AND rs.last_execution_time >= DATEADD(MINUTE, -15, SYSDATETIMEOFFSET())
        """;

    private static final java.time.Duration STATE_CACHE_TTL = java.time.Duration.ofMinutes(5);
    private volatile StateSnapshot cachedState;

    private record StateSnapshot(String state, Instant fetchedAt) {}

    static boolean shouldRefreshState(Instant lastChecked, Instant now) {
        return lastChecked == null || !now.isBefore(lastChecked.plus(STATE_CACHE_TTL));
    }

    private String readStateIfNeeded(Connection conn) throws SQLException {
        Instant now = Instant.now();
        StateSnapshot cached = cachedState;
        if (!shouldRefreshState(cached == null ? null : cached.fetchedAt(), now)) return cached.state();
        String state;
        try (PreparedStatement stmt = conn.prepareStatement(STORE_STATE_SQL)) {
            stmt.setQueryTimeout(5);
            try (ResultSet rs = stmt.executeQuery()) {
                state = rs.next() ? rs.getString("actual_state_desc") : "OFF";
            }
        }
        cachedState = new StateSnapshot(state, Instant.now());
        return state;
    }

    public MssqlQueryStoreCollector(MssqlTargetProperties props) {
        this.props = props;
        // A cached Query Store OFF/READ_WRITE state belongs to ONE DB only.
        props.onTargetChanged(() -> cachedState = null);
    }

    /**
     * A stable, safe diagnostic stage plus a SQL Server numeric error code
     * tells operators which read-only step failed without exposing query text,
     * connection strings, driver messages, passwords or DB contents.
     */
    public static final class StageFailure extends SQLException {
        private final String stage;
        public StageFailure(String stage, SQLException cause) {
            super("QUERY_STORE_" + stage + "_FAILED", cause.getSQLState(),
                cause.getErrorCode(), cause);
            this.stage = stage;
        }
        public String stage() { return stage; }
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
        String stage = "CONNECT";
        try (Connection conn = DriverManager.getConnection(url, auth)) {
            conn.setReadOnly(true);
            stage = "STATE";
            String state = readStateIfNeeded(conn);
            if (!"READ_WRITE".equals(state) && !"READ_ONLY".equals(state)) {
                return new QueryStoreResult("QUERY_STORE_DISABLED", List.of());
            }
            Instant collectedAt = Instant.now();
            List<QueryPerformanceSnapshot> rows = new ArrayList<>();
            stage = "STATS";
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
                            "DBPULSE_PATTERN".equals(rs.getString("source_category"))
                                ? "COLLECTOR_OVERHEAD" : QueryDiagnosis.classify(duration, cpu, reads),
                            rs.getString("source_category")));
                    }
                }
            }
            Double databaseCpuMs = null;
            Long databaseExecutions = null;
            // Optional lightweight aggregate. A failure must not erase the
            // captured query list. Never log SQL text or JDBC messages.
            try (PreparedStatement cpu = conn.prepareStatement(DATABASE_CPU_SQL)) {
                cpu.setQueryTimeout(5);
                try (ResultSet summary = cpu.executeQuery()) {
                    if (summary.next()) {
                        databaseCpuMs = summary.getDouble("recorded_cpu_ms");
                        databaseExecutions = summary.getLong("recorded_executions");
                    }
                }
            } catch (SQLException ignored) {
                // CPU estimate is unavailable, but original query history remains.
            }
            return new QueryStoreResult(
                state.equals("READ_ONLY") ? "QUERY_STORE_READ_ONLY" : "ONLINE",
                rows, databaseCpuMs, databaseExecutions);
        } catch (SQLException failure) {
            // Refresh Query Store state on the next poll if a stats read fails.
            cachedState = null;
            throw new StageFailure(stage, failure);
        }
    }

    public record QueryStoreResult(String status, List<QueryPerformanceSnapshot> rows,
                                   Double databaseCpuMs, Long databaseExecutions) {
        public QueryStoreResult(String status, List<QueryPerformanceSnapshot> rows) {
            this(status, rows, null, null);
        }
    }
}
