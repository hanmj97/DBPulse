-- DBPulseLab-only deadlock event test. EXECUTE ONLY ON THE LOCAL TEST INSTANCE.
-- Use a Windows/admin or lab-only writer account, NOT dbpulse_monitor.
-- First run ONLY this setup section once in SSMS:
USE DBPulseLab;
GO

IF OBJECT_ID(N'dbo.DeadlockDemo', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.DeadlockDemo (
        Id INT NOT NULL PRIMARY KEY,
        Counter INT NOT NULL
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM dbo.DeadlockDemo WHERE Id = 1)
    INSERT INTO dbo.DeadlockDemo (Id, Counter) VALUES (1, 0);
IF NOT EXISTS (SELECT 1 FROM dbo.DeadlockDemo WHERE Id = 2)
    INSERT INTO dbo.DeadlockDemo (Id, Counter) VALUES (2, 0);
GO

-- Next, open TWO separate SSMS tabs using a writer account. Do NOT rerun the
-- setup while those tests are active.
--
-- SESSION A (first tab; start first):
-- USE DBPulseLab;
-- BEGIN TRY
--     BEGIN TRAN;
--     UPDATE dbo.DeadlockDemo SET Counter=Counter+1 WHERE Id=1;
--     WAITFOR DELAY '00:00:06';
--     UPDATE dbo.DeadlockDemo SET Counter=Counter+1 WHERE Id=2;
--     COMMIT;
-- END TRY
-- BEGIN CATCH
--     IF @@TRANCOUNT > 0 ROLLBACK;
--     SELECT ERROR_NUMBER() AS ErrorNumber, ERROR_MESSAGE() AS ErrorMessage;
-- END CATCH;
--
-- SESSION B (start in another tab IMMEDIATELY after A):
-- USE DBPulseLab;
-- BEGIN TRY
--     BEGIN TRAN;
--     UPDATE dbo.DeadlockDemo SET Counter=Counter+1 WHERE Id=2;
--     WAITFOR DELAY '00:00:06';
--     UPDATE dbo.DeadlockDemo SET Counter=Counter+1 WHERE Id=1;
--     COMMIT;
-- END TRY
-- BEGIN CATCH
--     IF @@TRANCOUNT > 0 ROLLBACK;
--     SELECT ERROR_NUMBER() AS ErrorNumber, ERROR_MESSAGE() AS ErrorMessage;
-- END CATCH;
--
-- At least one session normally becomes the SQL Server deadlock victim (1205).
-- A deadlock is timing-sensitive; 1205 is not guaranteed every run.
-- DBPulse reads the resulting system_health xml_deadlock_report asynchronously.
-- NEVER run this script on production, external users' DBs or Dataops_mssql.
