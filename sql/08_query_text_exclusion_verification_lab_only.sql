-- DBPulseLab read-only integration check for known DBPulse Query Store SQL shapes.
-- NEVER use a fixed Query ID or Plan ID across different SQL Server instances.
-- Does not return any raw SQL statement, parameters, object names or passwords.
USE DBPulseLab;
GO

SELECT TOP (30)
    q.query_id,
    p.plan_id,
    p.last_execution_time AS last_executed_at,
    CASE
      WHEN CHARINDEX(N'DBPULSE_INTERNAL_', qt.query_sql_text) > 0
        THEN 'INTERNAL_MARKER'
      WHEN qt.query_sql_text LIKE N'%sys.query_store_query_text%'
        AND qt.query_sql_text LIKE N'%SUBSTRING%'
        AND qt.query_sql_text LIKE N'%captured_text%'
        AND qt.query_sql_text LIKE N'%DATALENGTH%'
        AND qt.query_sql_text LIKE N'%original_bytes%'
        THEN 'QUERY_TEXT_LOOKUP_FALLBACK'
      ELSE 'NOT_MATCHED'
    END AS exclusion_signal
FROM sys.query_store_query q
JOIN sys.query_store_plan p ON p.query_id = q.query_id
JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
WHERE p.last_execution_time >= DATEADD(MINUTE, -15, SYSDATETIMEOFFSET())
  AND (
    CHARINDEX(N'DBPULSE_INTERNAL_', qt.query_sql_text) > 0
    OR (
      qt.query_sql_text LIKE N'%sys.query_store_query_text%'
      AND qt.query_sql_text LIKE N'%SUBSTRING%'
      AND qt.query_sql_text LIKE N'%captured_text%'
      AND qt.query_sql_text LIKE N'%DATALENGTH%'
      AND qt.query_sql_text LIKE N'%original_bytes%'
    )
  )
ORDER BY p.last_execution_time DESC, q.query_id DESC, p.plan_id DESC;
GO
