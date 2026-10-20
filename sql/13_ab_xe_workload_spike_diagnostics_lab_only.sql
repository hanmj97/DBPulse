/*
 DBPulse v0.13 / DBPulseLab ONLY
 Read-only diagnosis of the existing B-mode Extended Events XEL.
 Does NOT start/stop or change XE, Query Store, any database, or user SQL.
 Never output statement text, parameters, or connection secrets.

 IMPORTANT:
 - Run with SSMS Windows sysadmin connected to LOCAL SQL Server.
 - Retain B's D:\Projects\DBPulseLabXE\ObserverCost*.xel until diagnosis.
 - If different captures reused the same XEL directory, the precise UTC
   time window below isolates only the actual B sample provided by the user.
 - Timed RPC cpu_time is converted from microseconds to milliseconds.
 - sql_batch_completed CPU units need server-metadata verification;
   its raw value is reported separately and never added to RPC CPU.
 - B XEL files must remain intact; do not modify or clear the database.
*/
USE master;
GO
SET NOCOUNT ON;

DECLARE @StartedUtc DATETIME2(7) = '2026-10-20T06:03:27.8657724';
DECLARE @EndedUtc DATETIME2(7)   = '2026-10-20T06:15:17.3419270';

IF @EndedUtc <= @StartedUtc
    THROW 51031, 'B event capture time range is invalid.', 1;

IF OBJECT_ID('tempdb..#DBPulseABXEWorkload') IS NOT NULL
    DROP TABLE #DBPulseABXEWorkload;

CREATE TABLE #DBPulseABXEWorkload
(
    event_name SYSNAME NOT NULL,
    event_at_utc DATETIME2(7) NOT NULL,
    raw_cpu BIGINT NOT NULL,
    cpu_us BIGINT NOT NULL,
    duration_us BIGINT NOT NULL,
    logical_reads BIGINT NOT NULL,
    physical_reads BIGINT NOT NULL
);

;WITH Raw AS
(
    SELECT object_name AS event_name, timestamp_utc,
           TRY_CONVERT(XML,event_data) AS event_xml
    FROM sys.fn_xe_file_target_read_file(
        N'D:\Projects\DBPulseLabXE\ObserverCost*.xel', NULL, NULL, NULL
    )
    WHERE object_name IN (N'rpc_completed', N'sql_batch_completed')
      AND timestamp_utc >= @StartedUtc
      AND timestamp_utc <= @EndedUtc
),
Extracted AS
(
    SELECT
        event_name, timestamp_utc,
        event_xml.value('(/event/action[@name="client_app_name"]/value)[1]', 'nvarchar(128)') AS app_name,
        event_xml.value('(/event/data[@name="cpu_time"]/value)[1]', 'bigint') AS raw_cpu,
        event_xml.value('(/event/data[@name="duration"]/value)[1]', 'bigint') AS duration_us,
        event_xml.value('(/event/data[@name="logical_reads"]/value)[1]', 'bigint') AS logical_reads,
        event_xml.value('(/event/data[@name="physical_reads"]/value)[1]', 'bigint') AS physical_reads
    FROM Raw
    WHERE event_xml IS NOT NULL
)
INSERT INTO #DBPulseABXEWorkload
    (event_name, event_at_utc, raw_cpu, cpu_us, duration_us, logical_reads, physical_reads)
SELECT event_name, timestamp_utc,
       raw_cpu, CASE WHEN event_name=N'rpc_completed' THEN raw_cpu ELSE 0 END,
       duration_us, logical_reads, physical_reads
FROM Extracted
WHERE app_name = N'DBPulseLabWorkload';

-- 1. Separate 1,200 timed RPCs from the TWO un-timed preparation batches.
-- A batch-only CPU spike must not be attributed to the 1,200 timed calls.
;WITH Distribution AS
(
    SELECT *,
        PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY duration_us)
            OVER (PARTITION BY event_name) AS p50_us,
        PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_us)
            OVER (PARTITION BY event_name) AS p95_us,
        PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY duration_us)
            OVER (PARTITION BY event_name) AS p99_us
    FROM #DBPulseABXEWorkload
)
SELECT event_name,
       COUNT_BIG(*) AS completed_requests,
       CASE WHEN event_name=N'rpc_completed'
            THEN ROUND(SUM(CONVERT(FLOAT,cpu_us))/1000.0,2)
            ELSE NULL END AS timed_rpc_cpu_ms,
       SUM(raw_cpu) AS raw_cpu_value_unconverted,
       SUM(logical_reads) AS total_logical_read_pages,
       SUM(physical_reads) AS total_physical_read_pages,
       ROUND(AVG(CONVERT(FLOAT,duration_us))/1000.0,3) AS mean_duration_ms,
       ROUND(MAX(p50_us)/1000.0,3) AS median_duration_ms,
       ROUND(MAX(p95_us)/1000.0,3) AS p95_duration_ms,
       ROUND(MAX(p99_us)/1000.0,3) AS p99_duration_ms,
       ROUND(MAX(CONVERT(FLOAT,duration_us))/1000.0,3) AS max_duration_ms,
       SUM(CASE WHEN duration_us>=20000 THEN 1 ELSE 0 END) AS calls_over_20ms,
       SUM(CASE WHEN duration_us>=100000 THEN 1 ELSE 0 END) AS calls_over_100ms,
       SUM(CASE WHEN duration_us>=1000000 THEN 1 ELSE 0 END) AS calls_over_1s
FROM Distribution
GROUP BY event_name
ORDER BY event_name;

-- 2. Outlier timestamps + numeric costs ONLY; no SQL text or parameter output.
SELECT TOP (15)
       event_name, event_at_utc,
       ROUND(CONVERT(FLOAT,duration_us)/1000.0,3) AS duration_ms,
       CASE WHEN event_name=N'rpc_completed'
            THEN ROUND(CONVERT(FLOAT,cpu_us)/1000.0,3)
            ELSE NULL END AS timed_rpc_cpu_ms,
       raw_cpu AS raw_cpu_value_unconverted,
       logical_reads AS logical_read_pages,
       physical_reads AS physical_read_pages
FROM #DBPulseABXEWorkload
ORDER BY duration_us DESC, cpu_us DESC;

-- 3. Inspect the event metadata from THIS actual SQL Server.
-- This may describe different cpu_time units per XE event.
-- Batch CPU must not be included in timed 1,200-request comparisons.
SELECT object_name AS xe_event, name AS xe_field,
       type_name, description AS server_documented_unit
FROM sys.dm_xe_object_columns
WHERE object_name IN (N'rpc_completed',N'sql_batch_completed')
  AND name IN (N'cpu_time',N'duration')
  AND column_type=N'data'
ORDER BY object_name,name;

-- 4. Verify capture scope before interpreting a diagnostic.
SELECT COUNT_BIG(*) AS workload_completed_total,
       SUM(CASE WHEN event_name=N'rpc_completed' THEN 1 ELSE 0 END) AS workload_rpc_completed,
       SUM(CASE WHEN event_name=N'sql_batch_completed' THEN 1 ELSE 0 END) AS workload_batch_completed,
       @StartedUtc AS selected_window_start_utc,
       @EndedUtc AS selected_window_end_utc
FROM #DBPulseABXEWorkload;
GO
