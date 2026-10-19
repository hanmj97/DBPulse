-- Run manually in SSMS, using an administrative login on your LOCAL SQL Server.
-- Isolated database: does not modify Dataops_mssql or any other database.
USE master;
GO
IF DB_ID(N'DBPulseLab') IS NULL
BEGIN
    CREATE DATABASE DBPulseLab;
END;
GO
SELECT name, state_desc FROM sys.databases WHERE name = N'DBPulseLab';
GO
