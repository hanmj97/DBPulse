-- Execute manually in SSMS using an ADMINISTRATOR, ONLY against the local DBPulseLab database.
-- The monitoring account does NOT need ALTER DATABASE permissions.
-- QUERY_CAPTURE_MODE=ALL is for this dedicated local test DB, not a production default.
USE master;
GO
ALTER DATABASE [DBPulseLab]
SET QUERY_STORE = ON (
    OPERATION_MODE = READ_WRITE,
    QUERY_CAPTURE_MODE = ALL,
    MAX_STORAGE_SIZE_MB = 256
);
GO
USE DBPulseLab;
GO
-- SQL Server 2022+ (including SQL Server 2025) Query Store read access.
GRANT VIEW DATABASE PERFORMANCE STATE TO [dbpulse_monitor];
GO
SELECT actual_state_desc, desired_state_desc, readonly_reason
FROM sys.database_query_store_options;
GO
