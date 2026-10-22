/*
 DBPulse v0.13 — lab-only A/B XE temporal co-occurrence diagnostics
 READ-ONLY: reads archived XEL files and creates only #temporary tables.
 No live XE session, DB settings, Query Store settings or customer records
 are changed. No SQL text, parameters, credentials or file data are emitted.
 Run from Windows-authenticated SSMS on the local SQL Server ONLY.

 Archive folders expected:
   D:\Projects\DBPulseLabXE\archive\A_20261020_retest\ObserverCost*.xel
   D:\Projects\DBPulseLabXE\archive\B_20261020\ObserverCost*.xel
 Use PowerShell Test-Path / Get-ChildItem to verify first.
 NOTE: event timestamp is at completion; estimated start = end-duration_us.
 This does NOT recover SQL wait types or prove DBPulse caused latency.
*/
USE master;
GO
SET NOCOUNT ON;

DECLARE @AStartUtc DATETIME2(7) = '2026-10-20T06:49:45';
DECLARE @AEndUtc   DATETIME2(7) = '2026-10-20T07:01:00';
DECLARE @BStartUtc DATETIME2(7) = '2026-10-20T06:03:27.8657724';
DECLARE @BEndUtc   DATETIME2(7) = '2026-10-20T06:15:17.3419270';

IF OBJECT_ID('tempdb..#DBPulseEventOverlap15') IS NOT NULL
    DROP TABLE #DBPulseEventOverlap15;

CREATE TABLE #DBPulseEventOverlap15 (
    event_id BIGINT IDENTITY(1,1) PRIMARY KEY,
    sample CHAR(1) NOT NULL,
    app_name NVARCHAR(128) NOT NULL,
    event_name NVARCHAR(128) NOT NULL,
    event_end_utc DATETIME2(7) NOT NULL,
    event_start_utc DATETIME2(7) NULL,
    duration_us BIGINT NOT NULL,
    cpu_us BIGINT NOT NULL,
    logical_reads BIGINT NOT NULL
);

-- Both XE event types use microseconds for duration and cpu_time
-- on the user's local SQL Server, confirmed using xe metadata.
;WITH Files AS (
    SELECT 'A' AS sample,object_name,timestamp_utc,
           TRY_CONVERT(XML,event_data) AS xe_xml
    FROM sys.fn_xe_file_target_read_file(
        N'D:\Projects\DBPulseLabXE\archive\A_20261020_retest\ObserverCost*.xel',
        NULL,NULL,NULL
    )
    WHERE object_name IN (N'rpc_completed',N'sql_batch_completed')
      AND timestamp_utc >= @AStartUtc AND timestamp_utc <= @AEndUtc

    UNION ALL

    SELECT 'B' AS sample,object_name,timestamp_utc,
           TRY_CONVERT(XML,event_data) AS xe_xml
    FROM sys.fn_xe_file_target_read_file(
        N'D:\Projects\DBPulseLabXE\archive\B_20261020\ObserverCost*.xel',
        NULL,NULL,NULL
    )
    WHERE object_name IN (N'rpc_completed',N'sql_batch_completed')
      AND timestamp_utc >= @BStartUtc AND timestamp_utc <= @BEndUtc
),
Parsed AS (
    SELECT sample,object_name AS event_name,timestamp_utc,
        xe_xml.value('(/event/action[@name="client_app_name"]/value)[1]',
                     'nvarchar(128)') AS app_name,
        xe_xml.value('(/event/data[@name="duration"]/value)[1]',
                     'bigint') AS duration_us,
        xe_xml.value('(/event/data[@name="cpu_time"]/value)[1]',
                     'bigint') AS cpu_us,
        xe_xml.value('(/event/data[@name="logical_reads"]/value)[1]',
                     'bigint') AS logical_reads
    FROM Files WHERE xe_xml IS NOT NULL
)
INSERT INTO #DBPulseEventOverlap15
    (sample,app_name,event_name,event_end_utc,event_start_utc,
     duration_us,cpu_us,logical_reads)
SELECT sample,app_name,event_name,timestamp_utc,
       CASE WHEN duration_us BETWEEN 0 AND 2147483647
            THEN DATEADD(MICROSECOND,-CONVERT(INT,duration_us),timestamp_utc)
            ELSE NULL END,
       duration_us,cpu_us,logical_reads
FROM Parsed
WHERE app_name LIKE N'DBPulse%';

CREATE INDEX IX_DBPO15_Cost
ON #DBPulseEventOverlap15(sample,app_name,event_end_utc);

-- 1. Verify capture completeness BEFORE interpreting time proximity.
SELECT sample, COUNT_BIG(*) AS total_events,
       SUM(CASE WHEN app_name=N'DBPulseLabWorkload'
                     AND event_name=N'rpc_completed' THEN 1 ELSE 0 END)
           AS timed_workload_rpcs,
       SUM(CASE WHEN app_name=N'DBPulseLabWorkload'
                     AND event_name=N'sql_batch_completed' THEN 1 ELSE 0 END)
           AS setup_batches,
       SUM(CASE WHEN app_name=N'DBPulseQueryStore' THEN 1 ELSE 0 END)
           AS query_store_related_requests,
       SUM(CASE WHEN event_start_utc IS NULL THEN 1 ELSE 0 END)
           AS unknown_event_starts,
       MIN(event_end_utc) AS first_captured_completion_utc,
       MAX(event_end_utc) AS last_captured_completion_utc
FROM #DBPulseEventOverlap15
GROUP BY sample
ORDER BY sample;

IF EXISTS (
    SELECT sample FROM #DBPulseEventOverlap15
    GROUP BY sample
    HAVING SUM(CASE WHEN app_name=N'DBPulseLabWorkload'
                       AND event_name=N'rpc_completed'
                    THEN 1 ELSE 0 END) <> 1200
) OR (SELECT COUNT(DISTINCT sample) FROM #DBPulseEventOverlap15)<>2
BEGIN
    THROW 51051, 'A/B XE archives incomplete: each must have 1200 timed RPCs. Do not interpret temporal analysis.', 1;
END;

-- 2. Outlier intervals vs monitor request intervals.
-- "overlapping" means estimated request time intervals intersect;
-- it does NOT imply blocking or causal interference.
;WITH Slow AS (
    SELECT event_id,sample,event_start_utc,event_end_utc,duration_us,cpu_us
    FROM #DBPulseEventOverlap15
    WHERE app_name=N'DBPulseLabWorkload'
      AND event_name=N'rpc_completed'
      AND duration_us>=100000
      AND event_start_utc IS NOT NULL
)
SELECT s.sample,s.event_id AS slow_event_id,
       s.event_end_utc AS slow_rpc_completed_utc,
       ROUND(s.duration_us/1000.0,3) AS slow_rpc_duration_ms,
       ROUND(s.cpu_us/1000.0,3) AS slow_rpc_cpu_ms,
       COUNT_BIG(m.app_name) AS simultaneous_monitor_requests,
       SUM(CASE WHEN m.app_name=N'DBPulseQueryStore' THEN 1 ELSE 0 END)
           AS overlapping_query_store_requests,
       SUM(CASE WHEN m.app_name IN
                  (N'DBPulseCollector',N'DBPulseWaits',N'DBPulseBlockingChain')
                THEN 1 ELSE 0 END) AS overlapping_5s_poller_requests
FROM Slow s
LEFT JOIN #DBPulseEventOverlap15 m
  ON m.sample=s.sample
 AND m.app_name NOT IN (N'DBPulseLabWorkload',N'DBPulseXEProbe')
 AND m.event_start_utc IS NOT NULL
 AND m.event_start_utc <= s.event_end_utc
 AND m.event_end_utc >= s.event_start_utc
GROUP BY s.sample,s.event_id,s.event_end_utc,s.duration_us,s.cpu_us
ORDER BY s.sample,s.duration_us DESC,s.event_end_utc;

-- 3. Which monitor components appear during actual slow RPCs?
;WITH Slow AS (
    SELECT sample,event_start_utc,event_end_utc
    FROM #DBPulseEventOverlap15
    WHERE app_name=N'DBPulseLabWorkload'
      AND event_name=N'rpc_completed'
      AND duration_us>=100000 AND event_start_utc IS NOT NULL
)
SELECT s.sample,m.app_name AS overlapping_monitor_app,
       COUNT_BIG(*) AS overlapping_event_pairs
FROM Slow s
JOIN #DBPulseEventOverlap15 m
  ON m.sample=s.sample
 AND m.app_name NOT IN (N'DBPulseLabWorkload',N'DBPulseXEProbe')
 AND m.event_start_utc IS NOT NULL
 AND m.event_start_utc <= s.event_end_utc
 AND m.event_end_utc >= s.event_start_utc
GROUP BY s.sample,m.app_name
ORDER BY s.sample,overlapping_event_pairs DESC,m.app_name;

-- 4. Duration-neutral check: a fixed +/- 100 ms window around
-- completion for EVERY timed RPC. Compares slow and normal queries
-- with the SAME reference-window width.
-- Background 5s jobs can coincide by chance; never infer causality.
;WITH Timed AS (
    SELECT sample,event_end_utc,duration_us
    FROM #DBPulseEventOverlap15
    WHERE app_name=N'DBPulseLabWorkload'
      AND event_name=N'rpc_completed'
)
SELECT t.sample,
       CASE WHEN t.duration_us>=100000 THEN 'SLOW_GE_100MS'
            WHEN t.duration_us<10000 THEN 'FAST_LT_10MS'
            ELSE 'MID_10_TO_100MS' END AS latency_group,
       COUNT_BIG(*) AS timed_rpcs,
       SUM(CASE WHEN hits.qstore_requests>0 THEN 1 ELSE 0 END)
           AS rpcs_near_query_store,
       SUM(CASE WHEN hits.monitor_requests>0 THEN 1 ELSE 0 END)
           AS rpcs_near_any_monitor,
       ROUND(100.0*SUM(CASE WHEN hits.qstore_requests>0 THEN 1 ELSE 0 END)
             /NULLIF(COUNT_BIG(*),0),2) AS pct_near_query_store,
       ROUND(100.0*SUM(CASE WHEN hits.monitor_requests>0 THEN 1 ELSE 0 END)
             /NULLIF(COUNT_BIG(*),0),2) AS pct_near_any_monitor
FROM Timed t
OUTER APPLY (
    SELECT COUNT_BIG(*) AS monitor_requests,
           COALESCE(SUM(CASE WHEN m.app_name=N'DBPulseQueryStore'
                             THEN 1 ELSE 0 END),0) AS qstore_requests
    FROM #DBPulseEventOverlap15 m
    WHERE m.sample=t.sample
      AND m.app_name NOT IN (N'DBPulseLabWorkload',N'DBPulseXEProbe')
      AND m.event_start_utc IS NOT NULL
      AND m.event_start_utc <= DATEADD(MILLISECOND,100,t.event_end_utc)
      AND m.event_end_utc >= DATEADD(MILLISECOND,-100,t.event_end_utc)
) hits
GROUP BY t.sample,
         CASE WHEN t.duration_us>=100000 THEN 'SLOW_GE_100MS'
              WHEN t.duration_us<10000 THEN 'FAST_LT_10MS'
              ELSE 'MID_10_TO_100MS' END
ORDER BY t.sample,latency_group;
GO
