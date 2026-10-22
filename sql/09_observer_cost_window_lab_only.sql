/*
 DBPulseLab ONLY: manual read-only comparison of DBPulse SQL *cumulative*
 Query Store counters across TWO snapshots in the SAME SSMS window.
 All writes are to #temporary tables in tempdb; no DBPulseLab tables,
 indexes, Query Store settings or production queries are modified.

 IMPORTANT: execute with @Phase='START', wait 5-10 min while DBPulse runs,
 then set @Phase='END' and execute again in the SAME SSMS tab/connection.
 Output deliberately omits query_sql_text, parameters and object names.
 Run twice under comparable workloads (normal 60s, throttled 120s)
 and compare per-minute deltas, not raw historical averages.

 LAB_OBSERVER_PROFILER_SELF_EXCLUDE: ensure this ad-hoc profiler query
 is not misclassified as a DBPulse-internal monitored statement.
*/
USE DBPulseLab;
GO
SET NOCOUNT ON;

-- EDIT ONLY START -> END after the initial snapshot.
DECLARE @Phase VARCHAR(8) = 'START';

IF @Phase NOT IN ('START','END')
    THROW 51001, 'Phase must be START or END.', 1;

IF OBJECT_ID('tempdb..#DBPulseObserverWindow') IS NULL
BEGIN
    CREATE TABLE #DBPulseObserverWindow (
        started_at DATETIMEOFFSET(7) NOT NULL,
        cutoff_at DATETIMEOFFSET(7) NOT NULL
    );
END;

IF OBJECT_ID('tempdb..#DBPulseObserverBaseline') IS NULL
BEGIN
    CREATE TABLE #DBPulseObserverBaseline (
        query_id BIGINT NOT NULL,
        plan_id BIGINT NOT NULL,
        source_kind VARCHAR(36) NOT NULL,
        executions FLOAT NOT NULL,
        cpu_us FLOAT NOT NULL,
        logical_read_pages FLOAT NOT NULL,
        PRIMARY KEY (query_id,plan_id)
    );
END;

IF @Phase = 'START'
BEGIN
    DELETE FROM #DBPulseObserverWindow;
    INSERT INTO #DBPulseObserverWindow(started_at,cutoff_at)
    SELECT SYSDATETIMEOFFSET(), DATEADD(HOUR,-4,SYSDATETIMEOFFSET());
    DELETE FROM #DBPulseObserverBaseline;
END
ELSE IF NOT EXISTS (SELECT 1 FROM #DBPulseObserverWindow)
    THROW 51002, 'Run START in this SAME SSMS window first.', 1;

DECLARE @Cutoff DATETIMEOFFSET(7) =
    (SELECT TOP (1) cutoff_at FROM #DBPulseObserverWindow);

IF OBJECT_ID('tempdb..#DBPulseObserverCurrent') IS NOT NULL
    DROP TABLE #DBPulseObserverCurrent;

CREATE TABLE #DBPulseObserverCurrent (
    query_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    source_kind VARCHAR(36) NOT NULL,
    executions FLOAT NOT NULL,
    cpu_us FLOAT NOT NULL,
    logical_read_pages FLOAT NOT NULL,
    PRIMARY KEY (query_id,plan_id)
);

-- Dynamic SQL shape classification: no fixed Query ID or Plan ID.
-- The explicit negative filter prevents this manual profiler from
-- accidentally counting itself because its SQL contains DBPulse patterns.
;WITH InternalQueries AS (
    SELECT
        q.query_id, p.plan_id,
        CASE
          WHEN qt.query_sql_text LIKE N'%DBPULSE_INTERNAL_QSTORE_STATE_V1%'
            OR (qt.query_sql_text LIKE N'%sys.database_query_store_options%'
                AND qt.query_sql_text LIKE N'%actual_state_desc%')
            THEN 'QUERY_STORE_STATE'
          WHEN qt.query_sql_text LIKE N'%DBPULSE_INTERNAL_QSTORE_STATS_V1%'
            OR (qt.query_sql_text LIKE N'%sys.query_store_runtime_stats%'
                AND qt.query_sql_text LIKE N'%last_executed_epoch%')
            THEN 'QUERY_STORE_COLLECTOR'
          WHEN qt.query_sql_text LIKE N'%DBPULSE_INTERNAL_QUERY_TEXT_V1%'
            OR (qt.query_sql_text LIKE N'%sys.query_store_query_text%'
                AND qt.query_sql_text LIKE N'%captured_text%'
                AND qt.query_sql_text LIKE N'%original_bytes%')
            THEN 'QUERY_TEXT_VIEWER'
          WHEN qt.query_sql_text LIKE N'%DBPULSE_INTERNAL_%'
            OR (qt.query_sql_text LIKE N'%sys.dm_exec_requests%'
                AND qt.query_sql_text LIKE N'%active_requests%'
                AND qt.query_sql_text LIKE N'%blocked_requests%')
            OR (qt.query_sql_text LIKE N'%sys.dm_exec_requests%'
                AND qt.query_sql_text LIKE N'%blocking_session_id%'
                AND qt.query_sql_text LIKE N'%blocked_session_id%')
            OR (qt.query_sql_text LIKE N'%sys.fn_xe_file_target_read_file%'
                AND qt.query_sql_text LIKE N'%xml_deadlock_report%')
            THEN 'OTHER_INTERNAL'
          ELSE NULL
        END AS source_kind
    FROM sys.query_store_query q
    JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
    JOIN sys.query_store_plan p ON p.query_id = q.query_id
    WHERE qt.query_sql_text NOT LIKE N'%LAB_OBSERVER_PROFILER_SELF_EXCLUDE%'
), Totals AS (
    SELECT x.query_id,x.plan_id,x.source_kind,
        SUM(CONVERT(FLOAT,rs.count_executions)) AS executions,
        SUM(CONVERT(FLOAT,rs.avg_cpu_time)
            * CONVERT(FLOAT,rs.count_executions)) AS cpu_us,
        SUM(CONVERT(FLOAT,rs.avg_logical_io_reads)
            * CONVERT(FLOAT,rs.count_executions)) AS logical_read_pages
    FROM InternalQueries x
    JOIN sys.query_store_runtime_stats rs ON rs.plan_id = x.plan_id
    WHERE x.source_kind IS NOT NULL
      AND rs.execution_type = 0
      AND rs.last_execution_time >= @Cutoff
    GROUP BY x.query_id,x.plan_id,x.source_kind
)
INSERT INTO #DBPulseObserverCurrent(query_id,plan_id,source_kind,
                                    executions,cpu_us,logical_read_pages)
SELECT query_id,plan_id,source_kind,executions,cpu_us,logical_read_pages
FROM Totals;

IF @Phase = 'START'
BEGIN
    INSERT INTO #DBPulseObserverBaseline
    SELECT query_id,plan_id,source_kind,executions,cpu_us,logical_read_pages
    FROM #DBPulseObserverCurrent;

    SELECT 'BASELINE_RECORDED' AS state,
      (SELECT started_at FROM #DBPulseObserverWindow) AS started_at,
      (SELECT COUNT(*) FROM #DBPulseObserverBaseline) AS plans,
      'Wait 5-10 minutes, change @Phase to END and run again in this SSMS session.' AS next_step;
END
ELSE
BEGIN
    DECLARE @ElapsedSeconds BIGINT = DATEDIFF_BIG(
      SECOND,
      (SELECT TOP (1) started_at FROM #DBPulseObserverWindow),
      SYSDATETIMEOFFSET());

    IF @ElapsedSeconds < 60
        THROW 51003, 'Measurement interval must be at least 60 seconds.', 1;

    ;WITH Diff AS (
      SELECT COALESCE(c.source_kind,b.source_kind) AS source_kind,
        CASE WHEN b.query_id IS NULL THEN 1 ELSE 0 END AS newly_observed,
        CASE WHEN c.query_id IS NULL
             OR (b.query_id IS NOT NULL AND (
                c.executions < b.executions
                OR c.cpu_us < b.cpu_us
                OR c.logical_read_pages < b.logical_read_pages
             )) THEN 1 ELSE 0 END AS invalid_reset,
        -- New captured plans may have executed BEFORE START but surfaced late.
        -- Exclude their lifetime totals from window deltas; report separately.
        CASE WHEN b.query_id IS NULL THEN NULL
             ELSE c.executions-b.executions END AS delta_exec,
        CASE WHEN b.query_id IS NULL THEN NULL
             ELSE c.cpu_us-b.cpu_us END AS delta_cpu_us,
        CASE WHEN b.query_id IS NULL THEN NULL
             ELSE c.logical_read_pages-b.logical_read_pages END AS delta_reads
      FROM #DBPulseObserverBaseline b
      FULL OUTER JOIN #DBPulseObserverCurrent c
        ON c.query_id=b.query_id AND c.plan_id=b.plan_id
    )
    SELECT source_kind,
       @ElapsedSeconds AS elapsed_seconds,
       COUNT(*) AS observed_plans,
       SUM(invalid_reset) AS invalid_or_disappeared_plans,
       SUM(newly_observed) AS newly_observed_plans_excluded,
       CONVERT(BIGINT,ROUND(SUM(CASE WHEN invalid_reset=0 AND newly_observed=0
                           THEN delta_exec ELSE 0 END),0)) AS observed_executions,
       ROUND(SUM(CASE WHEN invalid_reset=0 AND newly_observed=0
                      THEN delta_cpu_us ELSE 0 END)/1000.0,2) AS total_cpu_ms,
       ROUND(SUM(CASE WHEN invalid_reset=0 AND newly_observed=0
                      THEN delta_reads ELSE 0 END),2) AS total_logical_read_pages,
       ROUND(SUM(CASE WHEN invalid_reset=0 AND newly_observed=0 THEN delta_exec ELSE 0 END)
             * 60.0/NULLIF(@ElapsedSeconds,0),2) AS executions_per_minute,
       ROUND(SUM(CASE WHEN invalid_reset=0 AND newly_observed=0 THEN delta_cpu_us ELSE 0 END)
             * 60.0/1000.0/NULLIF(@ElapsedSeconds,0),2) AS cpu_ms_per_minute,
       ROUND(SUM(CASE WHEN invalid_reset=0 AND newly_observed=0 THEN delta_reads ELSE 0 END)
             * 60.0/NULLIF(@ElapsedSeconds,0),2) AS logical_pages_per_minute
    FROM Diff
    GROUP BY source_kind
    ORDER BY cpu_ms_per_minute DESC;

    SELECT @ElapsedSeconds AS elapsed_seconds,
       (SELECT COUNT(*) FROM #DBPulseObserverBaseline) AS baseline_plans,
       (SELECT COUNT(*) FROM #DBPulseObserverCurrent) AS current_plans,
       'Query Store is incomplete: new plans are excluded from deltas; any new/vanished/reset plans or missing collector invalidate a strict A/B claim. Verify using sql/10 and lab-only XE sql/11.' AS limitations;
END;
GO
