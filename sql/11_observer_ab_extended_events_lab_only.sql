/*
 DBPulseLab ONLY — OPTIONAL server-side Extended Events A/B measurement.
 Captures ONLY applications with names beginning DBPulse (including
 DBPulseQueryStore, DBPulseCollector, DBPulseWaits) and a synthetic
 DBPulseLabWorkload client. Requires lab sysadmin/ALTER ANY EVENT SESSION.
 Uses statement_completed only: NO duplicate RPC/batch + statement sums.
 Records statement text inside a LOCAL XEL file (sensitive!); report
 returns aggregates only. Remove XEL after the experiment.
 Never execute on a customer/production SQL Server.

 BEFORE START:
  1. Make D:\Projects\DBPulseLabXE on the SQL Server machine. Grant SQL Server
     SERVICE account write access. Remove old ObserverCost*.xel files.
  2. Start this script in ONE SSMS tab; change @Phase START -> END after
     10 min on the SAME SSMS session. Repeat A and B separately.
  3. Put repeatable synthetic workload in its own Application Name=
     DBPulseLabWorkload connection. Do not simulate customer personal data.
*/
USE master;
GO
SET NOCOUNT ON;
DECLARE @Phase VARCHAR(8) = 'START'; -- START, END, CLEANUP

IF @Phase NOT IN ('START','END','CLEANUP')
    THROW 51010, 'Phase must be START, END or CLEANUP.', 1;

IF OBJECT_ID('tempdb..#DBPulseXEWindow') IS NULL
    CREATE TABLE #DBPulseXEWindow(started_at_utc DATETIME2(7) NOT NULL);

IF @Phase='START'
BEGIN
    IF EXISTS (SELECT 1 FROM sys.server_event_sessions WHERE name=N'DBPulseLab_Observer_AB')
        THROW 51011, 'Existing XE session. Run CLEANUP and delete old XEL before START.', 1;

    -- CREATE EVENT SESSION must execute in its own batch.
    EXEC(N'
        CREATE EVENT SESSION [DBPulseLab_Observer_AB] ON SERVER
        ADD EVENT sqlserver.sql_statement_completed
        (
          ACTION(sqlserver.client_app_name)
          WHERE ([sqlserver].[like_i_sql_unicode_string]
                    ([sqlserver].[client_app_name],N''DBPulse%''))
        )
        ADD TARGET package0.event_file
        (
          SET filename=N''D:\Projects\DBPulseLabXE\ObserverCost.xel'',
              max_file_size=(10),
              max_rollover_files=(4)
        )
        WITH (MAX_MEMORY=4096 KB,
              EVENT_RETENTION_MODE=ALLOW_SINGLE_EVENT_LOSS,
              MAX_DISPATCH_LATENCY=5 SECONDS,
              TRACK_CAUSALITY=OFF,
              STARTUP_STATE=OFF);');

    DELETE FROM #DBPulseXEWindow;
    INSERT INTO #DBPulseXEWindow VALUES (SYSUTCDATETIME());
    ALTER EVENT SESSION [DBPulseLab_Observer_AB] ON SERVER STATE=START;

    SELECT 'XE_CAPTURE_STARTED' AS state,
           started_at_utc, 'Change to END after approximately 10 min, same SSMS tab.' AS next_step
    FROM #DBPulseXEWindow;
END
ELSE IF @Phase='END'
BEGIN
    IF NOT EXISTS (SELECT 1 FROM #DBPulseXEWindow)
        THROW 51012, 'Run START in this exact SSMS tab first.', 1;
    IF NOT EXISTS (SELECT 1 FROM sys.server_event_sessions WHERE name=N'DBPulseLab_Observer_AB')
        THROW 51013, 'XE session missing.', 1;

    -- A dropped-event count >0 invalidates exact statement-level totals.
    DECLARE @DroppedEvents BIGINT = (
        SELECT dropped_event_count
        FROM sys.dm_xe_sessions WHERE name=N'DBPulseLab_Observer_AB'
    );
    IF @DroppedEvents IS NULL
        THROW 51015, 'XE session was not running at END.', 1;

    ALTER EVENT SESSION [DBPulseLab_Observer_AB] ON SERVER STATE=STOP;
    DECLARE @StartedUtc DATETIME2(7)=(SELECT TOP (1) started_at_utc FROM #DBPulseXEWindow);
    DECLARE @EndedUtc DATETIME2(7)=SYSUTCDATETIME();
    DECLARE @Seconds BIGINT=DATEDIFF_BIG(SECOND,@StartedUtc,@EndedUtc);
    IF @Seconds < 60
        THROW 51014, 'Capture must run for at least 60 seconds.', 1;

    -- XEL event timestamp is UTC. No SQL text is selected for output.
    IF OBJECT_ID('tempdb..#DBPulseXECosts') IS NOT NULL DROP TABLE #DBPulseXECosts;
    CREATE TABLE #DBPulseXECosts (
        app_name NVARCHAR(128) NOT NULL,
        sql_kind VARCHAR(40) NOT NULL,
        cpu_us BIGINT NULL,
        duration_us BIGINT NULL,
        logical_reads BIGINT NULL,
        physical_reads BIGINT NULL
    );

    ;WITH RawEvents AS (
        SELECT TRY_CONVERT(XML,event_data) AS x
        FROM sys.fn_xe_file_target_read_file(
            N'D:\Projects\DBPulseLabXE\ObserverCost*.xel',NULL,NULL,NULL)
        WHERE object_name=N'sql_statement_completed'
          AND timestamp_utc>=@StartedUtc AND timestamp_utc<=@EndedUtc
    ),
    Extracted AS (
        SELECT
          x.value('(/event/action[@name="client_app_name"]/value)[1]','nvarchar(128)') AS app_name,
          x.value('(/event/data[@name="statement"]/value)[1]','nvarchar(max)') AS statement_text,
          x.value('(/event/data[@name="cpu_time"]/value)[1]','bigint') AS cpu_us,
          x.value('(/event/data[@name="duration"]/value)[1]','bigint') AS duration_us,
          x.value('(/event/data[@name="logical_reads"]/value)[1]','bigint') AS logical_reads,
          x.value('(/event/data[@name="physical_reads"]/value)[1]','bigint') AS physical_reads
        FROM RawEvents WHERE x IS NOT NULL
    )
    INSERT INTO #DBPulseXECosts
    SELECT app_name,
           CASE WHEN app_name=N'DBPulseLabWorkload' THEN 'LAB_WORKLOAD'
                WHEN app_name=N'DBPulseQueryStore'
                  AND (statement_text LIKE N'%DBPULSE_INTERNAL_QSTORE_STATS_V1%'
                    OR (statement_text LIKE N'%sys.query_store_runtime_stats%'
                        AND statement_text LIKE N'%last_executed_epoch%'))
                  THEN 'QUERY_STORE_COLLECTOR'
                WHEN app_name=N'DBPulseQueryStore'
                  AND (statement_text LIKE N'%DBPULSE_INTERNAL_QSTORE_STATE_V1%'
                    OR statement_text LIKE N'%sys.database_query_store_options%')
                  THEN 'QUERY_STORE_STATE'
                WHEN app_name=N'DBPulseQueryStore' THEN 'QUERY_STORE_OTHER'
                ELSE 'OTHER_INTERNAL' END,
           cpu_us,duration_us,logical_reads,physical_reads
    FROM Extracted
    WHERE app_name LIKE N'DBPulse%';

    SELECT sql_kind, @Seconds AS elapsed_seconds,
           COUNT_BIG(*) AS completed_statements,
           ROUND(SUM(CONVERT(FLOAT,cpu_us))/1000.0,2) AS total_cpu_ms,
           SUM(logical_reads) AS total_logical_read_pages,
           SUM(physical_reads) AS total_physical_read_pages,
           ROUND(AVG(CONVERT(FLOAT,duration_us))/1000.0,2) AS avg_duration_ms,
           ROUND(COUNT_BIG(*)*60.0/NULLIF(@Seconds,0),2) AS calls_per_minute,
           ROUND(SUM(CONVERT(FLOAT,cpu_us))*60.0/1000.0/NULLIF(@Seconds,0),2) AS cpu_ms_per_minute
    FROM #DBPulseXECosts
    GROUP BY sql_kind
    ORDER BY sql_kind;

    -- Latency percentile is measured ONLY for synthetic workload statements.
    ;WITH Workload AS (
        SELECT duration_us,
          PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_us)
            OVER () AS p95_us
        FROM #DBPulseXECosts WHERE sql_kind='LAB_WORKLOAD'
    )
    SELECT COUNT_BIG(*) AS lab_statement_count,
      ROUND(AVG(CONVERT(FLOAT,duration_us))/1000.0,2) AS avg_ms,
      ROUND(MAX(p95_us)/1000.0,2) AS p95_ms
    FROM Workload;

    SELECT @StartedUtc AS started_at_utc,@EndedUtc AS ended_at_utc,
           @Seconds AS elapsed_seconds, @DroppedEvents AS dropped_xe_events,
           CASE WHEN @DroppedEvents=0 THEN 'NO_REPORTED_XE_DROPS'
                ELSE 'INVALID_XE_DROPPED_EVENTS' END AS capture_integrity,
           'Do NOT add these counts to Query Store totals; different sources overlap. Zero collector events must be checked against JVM attempts.' AS limitations;
    -- KEEP session stopped until review; run CLEANUP later.
END
ELSE IF @Phase='CLEANUP'
BEGIN
    IF EXISTS (SELECT 1 FROM sys.server_event_sessions WHERE name=N'DBPulseLab_Observer_AB')
    BEGIN
        IF EXISTS (SELECT 1 FROM sys.dm_xe_sessions WHERE name=N'DBPulseLab_Observer_AB')
            ALTER EVENT SESSION [DBPulseLab_Observer_AB] ON SERVER STATE=STOP;
        DROP EVENT SESSION [DBPulseLab_Observer_AB] ON SERVER;
    END;
    SELECT 'XE_SESSION_DROPPED' AS state,
           'Delete D:\Projects\DBPulseLabXE\ObserverCost*.xel after preserving aggregates.' AS next_step;
END;
GO