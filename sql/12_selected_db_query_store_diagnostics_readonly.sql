-- DBPulse Phase 19: selected DB Query Store read-only diagnosis.
-- Run in SSMS against YOUR OWN SQL Server DBPulseLab.
-- Prefer executing as the same restricted login that DBPulse uses.
-- No ALTER/GRANT, no raw SQL text, no plans, no passwords.
USE [DBPulseLab];
GO
SELECT DB_NAME() AS selected_database,
       HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DATABASE PERFORMANCE STATE')
         AS has_database_performance_state,
       HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DATABASE STATE')
         AS has_database_state;
GO
SELECT actual_state_desc, desired_state_desc, readonly_reason,
       query_capture_mode_desc, current_storage_size_mb, max_storage_size_mb
FROM sys.database_query_store_options;
GO
-- Stats count only. Does not return SQL texts/plan XML.
SELECT COUNT_BIG(*) AS finished_stats_rows_recent_last_execution
FROM sys.query_store_runtime_stats
WHERE execution_type = 0
  AND last_execution_time >= DATEADD(MINUTE,-15,SYSDATETIMEOFFSET());
GO
