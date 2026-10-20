/*
 DBPulseLab ONLY — read-only investigation of a missing Query Store collector row.
 LAB_OBSERVER_DIAGNOSTICS_SELF_EXCLUDE
 Does NOT modify Query Store capture settings, monitor permissions, or customer data.
 All query text inspected within SQL Server. Output contains only counters/metadata.
*/
USE [DBPulseLab];
GO
SET NOCOUNT ON;

-- 1. Diagnose whether Query Store is allowed to record new query shapes.
SELECT DB_NAME() AS database_name,
       actual_state_desc, desired_state_desc, query_capture_mode_desc,
       readonly_reason, current_storage_size_mb, max_storage_size_mb,
       flush_interval_seconds
FROM sys.database_query_store_options;

-- 2. Look for the actual collector SQL by its unique comment marker
-- and its stable source-view shape. These are independent detection routes.
;WITH Captured AS (
    SELECT q.query_id, p.plan_id,
           CASE
             WHEN qt.query_sql_text LIKE N'%DBPULSE_INTERNAL_QSTORE_STATS_V1%'
                  THEN 'MARKER_MATCH'
             WHEN qt.query_sql_text LIKE N'%sys.query_store_runtime_stats%'
                  AND qt.query_sql_text LIKE N'%last_executed_epoch%'
                  AND qt.query_sql_text LIKE N'%avg_logical_io_reads%'
                  THEN 'SHAPE_MATCH'
             ELSE NULL
           END AS match_method
    FROM sys.query_store_query_text AS qt
    INNER JOIN sys.query_store_query AS q ON q.query_text_id = qt.query_text_id
    INNER JOIN sys.query_store_plan AS p ON p.query_id = q.query_id
    WHERE qt.query_sql_text NOT LIKE N'%LAB_OBSERVER_DIAGNOSTICS_SELF_EXCLUDE%'
      AND qt.query_sql_text NOT LIKE N'%LAB_OBSERVER_PROFILER_SELF_EXCLUDE%'
),
Matching AS (
    SELECT c.query_id, c.plan_id, c.match_method,
           SUM(CONVERT(BIGINT,rs.count_executions)) AS executions,
           MAX(rs.last_execution_time) AS last_execution_time
    FROM Captured c
    LEFT JOIN sys.query_store_runtime_stats rs ON rs.plan_id = c.plan_id
    WHERE c.match_method IS NOT NULL
    GROUP BY c.query_id, c.plan_id, c.match_method
)
SELECT match_method, COUNT(*) AS captured_plans,
       COALESCE(SUM(executions),0) AS query_store_recorded_executions,
       MAX(last_execution_time) AS latest_execution_time
FROM Matching
GROUP BY match_method
ORDER BY match_method;

-- 3. Query Store may omit the SQL even if the JVM did execute it.
-- Plan-cache DMVs are INDEPENDENT of Query Store, but they are not complete:
-- recompilation, eviction, cache clearing and permissions affect visibility.
BEGIN TRY
    IF COALESCE(HAS_PERMS_BY_NAME(NULL,'SERVER','VIEW SERVER STATE'),0)=1
       OR COALESCE(HAS_PERMS_BY_NAME(NULL,'SERVER','VIEW SERVER PERFORMANCE STATE'),0)=1
    BEGIN
        SELECT TOP (20)
               qs.creation_time, qs.last_execution_time,
               qs.execution_count AS cached_plan_executions,
               CAST(qs.total_worker_time/1000.0 AS DECIMAL(20,2)) AS cached_cpu_ms,
               qs.total_logical_reads AS cached_logical_read_pages,
               CASE WHEN st.text LIKE N'%DBPULSE_INTERNAL_QSTORE_STATS_V1%'
                    THEN 'MARKER_MATCH' ELSE 'SHAPE_MATCH' END AS match_method
        FROM sys.dm_exec_query_stats AS qs
        CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) AS st
        WHERE (st.text LIKE N'%DBPULSE_INTERNAL_QSTORE_STATS_V1%'
            OR (st.text LIKE N'%sys.query_store_runtime_stats%'
                AND st.text LIKE N'%last_executed_epoch%'
                AND st.text LIKE N'%avg_logical_io_reads%'))
          AND st.text NOT LIKE N'%LAB_OBSERVER_DIAGNOSTICS_SELF_EXCLUDE%'
          AND st.text NOT LIKE N'%LAB_OBSERVER_PROFILER_SELF_EXCLUDE%'
        ORDER BY qs.last_execution_time DESC;
    END
    ELSE
        SELECT 'PLAN_CACHE_SKIPPED_INSUFFICIENT_SERVER_PERMISSION' AS diagnostic_status;
END TRY
BEGIN CATCH
    SELECT 'PLAN_CACHE_UNAVAILABLE' AS diagnostic_status,
           ERROR_NUMBER() AS error_number; -- do not display SQL text or login details
END CATCH;

-- 4. Check JVM /overhead successfulCollections delta separately.
-- Presence in Query Store, presence in cache and JDBC completion are distinct.
-- Never interpret zero Query Store matches as zero executions.
GO