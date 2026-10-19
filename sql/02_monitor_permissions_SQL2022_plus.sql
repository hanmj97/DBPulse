-- SQL Server 2022+ local development example. Review carefully before executing.
-- Prerequisite: create a SQL-auth LOGIN called dbpulse_monitor in SSMS first.
-- Do not grant sysadmin, db_owner, or data modification permissions.
-- Note: VIEW SERVER PERFORMANCE STATE is a server-wide monitoring permission.
USE master;
GO
IF SUSER_ID(N'dbpulse_monitor') IS NULL
    THROW 50001, 'Create the SQL login dbpulse_monitor before running this script.', 1;
GO
GRANT VIEW SERVER PERFORMANCE STATE TO [dbpulse_monitor];
GO
USE DBPulseLab;
GO
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = N'dbpulse_monitor')
    CREATE USER [dbpulse_monitor] FOR LOGIN [dbpulse_monitor];
GO
GRANT CONNECT TO [dbpulse_monitor];
GO
-- Test: connect to DBPulseLab using dbpulse_monitor and execute:
-- SELECT COUNT_BIG(*) FROM sys.dm_exec_requests;
