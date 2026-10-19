/*
 DBPulse / Phase 1. Run in SSMS as a local sysadmin.
 Only creates/changes the dedicated DBPulseLab database.
 Never run load against a production database.
*/
USE [master];
GO
IF DB_ID(N'DBPulseLab') IS NULL
BEGIN
    CREATE DATABASE [DBPulseLab];
END;
GO
ALTER DATABASE [DBPulseLab]
SET QUERY_STORE = ON (
    OPERATION_MODE = READ_WRITE,
    QUERY_CAPTURE_MODE = ALL, -- LAB only: capture short/fast queries too.
    WAIT_STATS_CAPTURE_MODE = ON,
    MAX_STORAGE_SIZE_MB = 128,
    SIZE_BASED_CLEANUP_MODE = AUTO,
    INTERVAL_LENGTH_MINUTES = 1
);
GO
USE [DBPulseLab];
GO
IF OBJECT_ID(N'dbo.Accounts', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Accounts
    (
        account_id INT IDENTITY(1,1) NOT NULL
            CONSTRAINT PK_DBPulse_Accounts PRIMARY KEY CLUSTERED,
        balance BIGINT NOT NULL CONSTRAINT DF_DBPulse_Accounts_Balance DEFAULT (100000),
        updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_DBPulse_Accounts_Updated DEFAULT (SYSUTCDATETIME())
    );
END;
GO
-- Rerunnable: seed only when the table is empty.
IF NOT EXISTS (SELECT 1 FROM dbo.Accounts)
BEGIN
    ;WITH n AS
    (
        SELECT TOP (20000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
        FROM sys.all_objects a CROSS JOIN sys.all_objects b
    )
    INSERT INTO dbo.Accounts(balance)
    SELECT 100000 FROM n;
END;
GO
SELECT DB_NAME() AS db_name,
       COUNT_BIG(*) AS seeded_accounts,
       MIN(account_id) AS min_account_id,
       MAX(account_id) AS max_account_id
FROM dbo.Accounts;
GO
SELECT actual_state_desc, desired_state_desc, query_capture_mode_desc
FROM sys.database_query_store_options;
GO
