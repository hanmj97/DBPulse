-- Read-only Query Store diagnostics for the SQL Server database connected to DBPulse.
-- Run in SSMS using the SAME monitoring login (not a sysadmin account).
-- The statements do not print SQL text, login details or passwords.
-- DBPulseLab is the current local test target; change ONLY if your registered DB differs.
USE [DBPulseLab];
GO

SELECT DB_NAME() AS SelectedDatabase,
       TRY_CONVERT(int, SERVERPROPERTY('ProductMajorVersion')) AS SqlServerMajorVersion,
       HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DATABASE PERFORMANCE STATE')
          AS HasDbPerformanceState,
       HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DATABASE STATE')
          AS HasDbState;
GO

-- If this SELECT fails, preserve only the displayed SQL error NUMBER.
SELECT actual_state_desc, desired_state_desc, readonly_reason,
       query_capture_mode_desc, current_storage_size_mb, max_storage_size_mb
FROM sys.database_query_store_options;
GO

-- This is the basic query store runtime access that DBPulse depends on.
SELECT COUNT_BIG(*) AS RecentQueryStoreRuntimeRows
FROM sys.query_store_runtime_stats
WHERE execution_type = 0
  AND last_execution_time >= DATEADD(MINUTE, -15, SYSDATETIMEOFFSET());
GO

-- Inspect whether plan and query metadata can be joined; no SQL text returned.
SELECT TOP (5)
    p.query_id, p.plan_id, SUM(rs.count_executions) AS execution_count,
    MAX(rs.last_execution_time) AS last_executed_at
FROM sys.query_store_plan p
JOIN sys.query_store_runtime_stats rs ON rs.plan_id = p.plan_id
JOIN sys.query_store_query q ON q.query_id = p.query_id
JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
WHERE rs.last_execution_time >= DATEADD(MINUTE,-15,SYSDATETIMEOFFSET())
  AND rs.execution_type = 0
GROUP BY p.query_id, p.plan_id
ORDER BY last_executed_at DESC;
GO
