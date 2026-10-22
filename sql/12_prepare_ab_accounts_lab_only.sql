/*
 DBPulse v0.13 A/B LAB ONLY — isolated synthetic workload fixture.
 Run with Windows authentication in SSMS against the LOCAL DBPulseLab.
 Creates/seeds ONLY dbo.Accounts. No ALTER DATABASE, Query Store config,
 SQL Server permissions, DBPulse app data or external target changes.
 Rerunnable without replacing existing rows. Must run BEFORE A/B START.
 Never execute on an actual customer database.
*/
USE [DBPulseLab];
GO
SET NOCOUNT ON;

IF DB_NAME() <> N'DBPulseLab'
    THROW 51020, 'Fixture script must run ONLY in local DBPulseLab.', 1;

IF OBJECT_ID(N'dbo.Accounts', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Accounts (
        account_id INT IDENTITY(1,1) NOT NULL
            CONSTRAINT PK_DBPulse_Accounts PRIMARY KEY CLUSTERED,
        balance BIGINT NOT NULL
            CONSTRAINT DF_DBPulse_Accounts_Balance DEFAULT (100000),
        updated_at DATETIME2(3) NOT NULL
            CONSTRAINT DF_DBPulse_Accounts_Updated DEFAULT (SYSUTCDATETIME())
    );
    PRINT 'Created DBPulseLab.dbo.Accounts for synthetic A/B workload.';
END;
GO

-- Check known table shape before touching any preexisting rows.
IF COL_LENGTH(N'dbo.Accounts',N'account_id') IS NULL
   OR COL_LENGTH(N'dbo.Accounts',N'balance') IS NULL
   OR COL_LENGTH(N'dbo.Accounts',N'updated_at') IS NULL
    THROW 51021, 'Existing Accounts table does not match DBPulseLab fixture. No seed applied.', 1;
GO

-- The scripted workload uses indexed account_id ranges (1000..2499).
-- Never truncate, replace or top-up an existing non-empty table.
IF NOT EXISTS (SELECT 1 FROM dbo.Accounts)
BEGIN
    ;WITH Numbers AS (
        SELECT TOP (20000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS rn
        FROM sys.all_objects AS a
        CROSS JOIN sys.all_objects AS b
    )
    INSERT INTO dbo.Accounts(balance)
    SELECT CONVERT(BIGINT,100000)
    FROM Numbers;
    PRINT 'Seeded 20,000 local synthetic accounts.';
END;
GO

SELECT DB_NAME() AS database_name,
       COUNT_BIG(*) AS total_accounts,
       MIN(account_id) AS min_account_id,
       MAX(account_id) AS max_account_id
FROM dbo.Accounts;

-- A nonstandard existing fixture invalidates a controlled comparison.
-- Do not silently alter preexisting data or regenerate its identity values.
IF (SELECT COUNT_BIG(*) FROM dbo.Accounts) <> 20000
   OR (SELECT MIN(account_id) FROM dbo.Accounts) <> 1
   OR (SELECT MAX(account_id) FROM dbo.Accounts) <> 20000
    THROW 51022, 'Fixture requires exactly 20,000 accounts with IDs 1..20000; inspect existing data before A/B.', 1;
GO
