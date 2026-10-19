-- Local lab only. The DBPulse Collector itself always remains READ ONLY.
-- Run in SSMS with a lab-owner login. Never run this script on a real customer's DB.
USE [DBPulseLab];
GO
IF OBJECT_ID(N'dbo.QueryPerfDemo', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.QueryPerfDemo (
        Id INT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        GroupId INT NOT NULL,
        Payload NVARCHAR(200) NOT NULL
    );
END;
GO
IF NOT EXISTS (SELECT 1 FROM dbo.QueryPerfDemo)
BEGIN
    ;WITH n AS (
        SELECT TOP (5000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS rn
        FROM sys.all_objects a CROSS JOIN sys.all_objects b
    )
    INSERT INTO dbo.QueryPerfDemo (GroupId, Payload)
    SELECT CONVERT(INT, rn % 20), REPLICATE(N'X', 150) FROM n;
END;
GO
-- A deliberately unindexed GroupId filter: produces repeatable Query Store samples.
-- Only a small amount of work, so this is NOT the high-volume load test.
DECLARE @i INT = 0;
WHILE @i < 30
BEGIN
    SELECT COUNT_BIG(*) AS MatchingRows
    FROM dbo.QueryPerfDemo
    WHERE GroupId = (@i % 20);
    SET @i += 1;
END;
GO
SELECT TOP (10)
    q.query_id, p.plan_id, SUM(rs.count_executions) AS executions,
    CAST(SUM(rs.avg_duration * rs.count_executions) /
         NULLIF(SUM(rs.count_executions), 0) / 1000.0 AS DECIMAL(18,2)) AS avg_duration_ms
FROM sys.query_store_query q
JOIN sys.query_store_plan p ON q.query_id = p.query_id
JOIN sys.query_store_runtime_stats rs ON p.plan_id = rs.plan_id
GROUP BY q.query_id, p.plan_id
ORDER BY executions DESC;
GO
