/*
 DBPulse v0.13 LAB ONLY: recover A XE aggregates after SSMS result loss.
 Read-only: reads existing XEL into #tempdb only. No session creation,
 database change, XEL deletion, user SQL output, credentials, or parameters.

 USE ONLY when the NEW A .xel is present directly in:
 D:\Projects\DBPulseLabXE\ObserverCost*.xel
 Archived B XEL under .\archive\B_20261020 stays untouched.

 The safety window below brackets the user's NEW A workload
 2026-10-20 06:49:52 -> 06:59:52 UTC. This is a recovery filter,
 NOT the exact XE START/END; an XE screenshot or start/end log is
 needed for exact per-minute monitoring-cost normalization.
 If the file is absent, DO NOT invent events: the A XE cost is lost.

 Local SQL Server XE metadata was confirmed: cpu_time and duration
 are MICROSECONDS for both rpc_completed and sql_batch_completed.
*/
USE master;
GO
SET NOCOUNT ON;

DECLARE @RangeStartUtc DATETIME2(7) = '2026-10-20T06:45:00';
DECLARE @RangeEndUtc   DATETIME2(7) = '2026-10-20T07:05:00';
DECLARE @TimedStartUtc DATETIME2(7) = '2026-10-20T06:49:52.6114141';
DECLARE @TimedEndUtc   DATETIME2(7) = '2026-10-20T06:59:52.6225616';

IF @RangeEndUtc <= @RangeStartUtc
    THROW 51041, 'Invalid A recovery window.', 1;

IF OBJECT_ID('tempdb..#DBPulseRecoveredAXe') IS NOT NULL
    DROP TABLE #DBPulseRecoveredAXe;

CREATE TABLE #DBPulseRecoveredAXe (
    event_name NVARCHAR(128) NOT NULL,
    app_name NVARCHAR(128) NOT NULL,
    event_at_utc DATETIME2(7) NOT NULL,
    sql_kind VARCHAR(40) NOT NULL,
    cpu_us BIGINT NOT NULL,
    duration_us BIGINT NOT NULL,
    logical_reads BIGINT NOT NULL,
    physical_reads BIGINT NOT NULL
);

;WITH SourceEvents AS (
    SELECT object_name AS event_name,
           timestamp_utc,
           TRY_CONVERT(XML,event_data) AS x
    FROM sys.fn_xe_file_target_read_file(
        N'D:\Projects\DBPulseLabXE\ObserverCost*.xel',NULL,NULL,NULL
    )
    WHERE object_name IN (N'rpc_completed',N'sql_batch_completed')
      AND timestamp_utc >= @RangeStartUtc
      AND timestamp_utc <= @RangeEndUtc
), Extracted AS (
    SELECT event_name, timestamp_utc,
      x.value('(/event/action[@name="client_app_name"]/value)[1]',
              'nvarchar(128)') AS app_name,
      CASE WHEN event_name=N'rpc_completed'
           THEN x.value('(/event/data[@name="statement"]/value)[1]',
                        'nvarchar(max)')
           ELSE x.value('(/event/data[@name="batch_text"]/value)[1]',
                        'nvarchar(max)')
      END AS sql_text,
      x.value('(/event/data[@name="cpu_time"]/value)[1]',
              'bigint') AS cpu_us,
      x.value('(/event/data[@name="duration"]/value)[1]',
              'bigint') AS duration_us,
      x.value('(/event/data[@name="logical_reads"]/value)[1]',
              'bigint') AS logical_reads,
      x.value('(/event/data[@name="physical_reads"]/value)[1]',
              'bigint') AS physical_reads
    FROM SourceEvents WHERE x IS NOT NULL
)
INSERT INTO #DBPulseRecoveredAXe
  (event_name,app_name,event_at_utc,sql_kind,
   cpu_us,duration_us,logical_reads,physical_reads)
SELECT event_name,app_name,timestamp_utc,
       CASE
         WHEN app_name=N'DBPulseLabWorkload' AND
              event_name=N'rpc_completed' THEN 'LAB_WORKLOAD'
         WHEN app_name=N'DBPulseLabWorkload' AND
              event_name=N'sql_batch_completed' THEN 'LAB_SETUP_BATCH'
         WHEN app_name=N'DBPulseQueryStore'
              AND (sql_text LIKE N'%DBPULSE_INTERNAL_QSTORE_STATS_V1%'
                OR (sql_text LIKE N'%sys.query_store_runtime_stats%'
                  AND sql_text LIKE N'%last_executed_epoch%'))
              THEN 'QUERY_STORE_COLLECTOR'
         WHEN app_name=N'DBPulseQueryStore'
              AND (sql_text LIKE N'%DBPULSE_INTERNAL_QSTORE_STATE_V1%'
                OR sql_text LIKE N'%sys.database_query_store_options%')
              THEN 'QUERY_STORE_STATE'
         WHEN app_name=N'DBPulseQueryStore' THEN 'QUERY_STORE_UNATTRIBUTED'
         ELSE 'OTHER_INTERNAL'
       END AS sql_kind,
       cpu_us,duration_us,logical_reads,physical_reads
FROM Extracted
WHERE app_name LIKE N'DBPulse%';

-- 1. Integrity: exactly 1,200 timed RPCs is expected for A.
-- First/last captured events ARE NOT the session's exact start/end times.
SELECT COUNT_BIG(*) AS events_in_recovery_window,
       SUM(CASE WHEN sql_kind='LAB_WORKLOAD' THEN 1 ELSE 0 END)
         AS timed_workload_rpcs,
       SUM(CASE WHEN sql_kind='LAB_SETUP_BATCH' THEN 1 ELSE 0 END)
         AS setup_batches,
       MIN(event_at_utc) AS first_captured_event_utc,
       MAX(event_at_utc) AS last_captured_event_utc,
       CASE WHEN SUM(CASE WHEN sql_kind='LAB_WORKLOAD'
                          THEN 1 ELSE 0 END)=1200
            THEN 'TIMED_WORKLOAD_COUNT_MATCHED'
            ELSE 'INCOMPLETE_OR_WRONG_A_XEL' END
         AS workload_integrity,
       'No exact XE elapsed seconds can be inferred from first/last event timestamps.'
         AS normalization_limit
FROM #DBPulseRecoveredAXe;

-- 2. Recover RPC ONLY client-workload CPU; setup batch separately.
;WITH WorkloadData AS (
    SELECT *,
      PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY duration_us)
          OVER (PARTITION BY sql_kind) AS p50_us,
      PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_us)
          OVER (PARTITION BY sql_kind) AS p95_us,
      PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY duration_us)
          OVER (PARTITION BY sql_kind) AS p99_us
    FROM #DBPulseRecoveredAXe
    WHERE sql_kind IN ('LAB_WORKLOAD','LAB_SETUP_BATCH')
)
SELECT sql_kind,
       COUNT_BIG(*) AS completed_requests,
       ROUND(SUM(CONVERT(FLOAT,cpu_us))/1000.0,2) AS cpu_ms,
       SUM(logical_reads) AS logical_read_pages,
       SUM(physical_reads) AS physical_read_pages,
       ROUND(AVG(CONVERT(FLOAT,duration_us))/1000.0,3) AS mean_ms,
       ROUND(MAX(p50_us)/1000.0,3) AS p50_ms,
       ROUND(MAX(p95_us)/1000.0,3) AS p95_ms,
       ROUND(MAX(p99_us)/1000.0,3) AS p99_ms,
       ROUND(MAX(CONVERT(FLOAT,duration_us))/1000.0,3) AS max_ms,
       SUM(CASE WHEN duration_us>=100000 THEN 1 ELSE 0 END)
         AS over_100ms,
       SUM(CASE WHEN duration_us>=1000000 THEN 1 ELSE 0 END)
         AS over_1s
FROM WorkloadData
GROUP BY sql_kind
ORDER BY sql_kind;

-- 3. Internal monitoring cost during captured events.
-- Do not compare these totals per minute with B until XE elapsed
-- seconds for this recovered A window have been confirmed.
SELECT sql_kind,
       COUNT_BIG(*) AS completed_requests,
       ROUND(SUM(CONVERT(FLOAT,cpu_us))/1000.0,2) AS cpu_ms,
       SUM(logical_reads) AS logical_read_pages,
       SUM(physical_reads) AS physical_read_pages
FROM #DBPulseRecoveredAXe
WHERE sql_kind NOT IN ('LAB_WORKLOAD','LAB_SETUP_BATCH')
GROUP BY sql_kind
ORDER BY sql_kind;

-- 4. Outlier timestamps only, NEVER SQL statements or parameters.
SELECT TOP (10)
       event_at_utc,
       ROUND(CONVERT(FLOAT,duration_us)/1000.0,3) AS duration_ms,
       ROUND(CONVERT(FLOAT,cpu_us)/1000.0,3) AS cpu_ms,
       logical_reads AS logical_read_pages
FROM #DBPulseRecoveredAXe
WHERE sql_kind='LAB_WORKLOAD'
ORDER BY duration_us DESC, cpu_us DESC;

-- 5. Show monitoring application request counts for 5s poller check.
SELECT app_name,event_name,COUNT_BIG(*) AS recorded_requests
FROM #DBPulseRecoveredAXe
GROUP BY app_name,event_name
ORDER BY app_name,event_name;

-- 6. The timed workload UTC range is known independently from
-- PowerShell; this does not replace XE session START/END evidence.
SELECT @TimedStartUtc AS client_workload_start_utc,
       @TimedEndUtc AS client_workload_end_utc,
       DATEDIFF_BIG(MILLISECOND,@TimedStartUtc,@TimedEndUtc)
           AS client_workload_elapsed_ms;
GO
