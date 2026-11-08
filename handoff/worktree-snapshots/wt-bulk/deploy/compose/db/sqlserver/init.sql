-- SPDX-License-Identifier: Apache-2.0
-- Idempotent SQL Server bootstrap (run by the sqlserver-init one-shot service).
-- RCSI is mandatory for Sangam on SQL Server (ARCHITECTURE.md 1.5, VI.1.6); FULL recovery enables 5-min log
-- backups for RPO <= 5 min (ADR-D-12).
IF DB_ID(N'sangam') IS NULL
    CREATE DATABASE sangam COLLATE Latin1_General_100_CI_AS_SC_UTF8;
GO
ALTER DATABASE sangam SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;
ALTER DATABASE sangam SET ALLOW_SNAPSHOT_ISOLATION ON;
ALTER DATABASE sangam SET RECOVERY FULL;
GO
IF SUSER_ID(N'sangam') IS NULL
    CREATE LOGIN sangam WITH PASSWORD = N'$(SANGAM_DB_PASSWORD)', CHECK_POLICY = OFF, DEFAULT_DATABASE = sangam;
GO
USE sangam;
GO
IF USER_ID(N'sangam') IS NULL
    CREATE USER sangam FOR LOGIN sangam;
ALTER ROLE db_owner ADD MEMBER sangam;
GO
SELECT name, is_read_committed_snapshot_on, snapshot_isolation_state_desc, recovery_model_desc
  FROM sys.databases WHERE name = N'sangam';
GO
