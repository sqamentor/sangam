--liquibase formatted sql

-- IR-8, SQL Server 2016 SP1+ (partitioning in every edition): ACT_HI_* placed on the partition scheme SANGAM_HI_PS
-- over SANGAM_HI_PF(datetime) RANGE RIGHT with monthly boundaries; NULL removal times (running instances) fall into the
-- first partition. Each table gets a CLUSTERED index on REMOVAL_TIME_ on the scheme; its ID_ primary key is re-created
-- NONCLUSTERED on [PRIMARY] (a unique index must contain the partitioning column to be aligned, and REMOVAL_TIME_ is
-- nullable). The engine's other indexes stay on [PRIMARY] (non-aligned), so partition SWITCH is not available;
-- removal-time cleanup still benefits from partition elimination.

--changeset sangam:IR8-21-mssql-function dbms:mssql context:partitioned-history splitStatements:false
--comment: partition function (monthly, 1 month back to 24 months ahead) and scheme
DECLARE @start DATETIME = DATEADD(MONTH, -1, DATEFROMPARTS(YEAR(GETUTCDATE()), MONTH(GETUTCDATE()), 1));
DECLARE @i INT = 0;
DECLARE @values NVARCHAR(MAX) = N'';
IF NOT EXISTS (SELECT 1 FROM sys.partition_functions WHERE name = 'SANGAM_HI_PF')
BEGIN
  WHILE @i < 26
  BEGIN
    SET @values = @values + CASE WHEN @i = 0 THEN N'' ELSE N', ' END
                + N'''' + CONVERT(NVARCHAR(30), DATEADD(MONTH, @i, @start), 126) + N'''';
    SET @i = @i + 1;
  END;
  EXEC (N'CREATE PARTITION FUNCTION SANGAM_HI_PF (datetime) AS RANGE RIGHT FOR VALUES (' + @values + N')');
END;
IF NOT EXISTS (SELECT 1 FROM sys.partition_schemes WHERE name = 'SANGAM_HI_PS')
  EXEC (N'CREATE PARTITION SCHEME SANGAM_HI_PS AS PARTITION SANGAM_HI_PF ALL TO ([PRIMARY])');
--rollback SELECT 1;

--changeset sangam:IR8-22-mssql-partition-table dbms:mssql context:partitioned-history splitStatements:false
--comment: moves one ACT_HI_* table onto the partition scheme (idempotent)
CREATE OR ALTER PROCEDURE SANGAM_HI_PARTITION_TABLE @table SYSNAME AS
BEGIN
  SET NOCOUNT ON;
  DECLARE @pk SYSNAME, @sql NVARCHAR(MAX);
  IF OBJECT_ID(@table, 'U') IS NULL
    THROW 50801, 'IR-8: history table not found (build the engine first)', 1;
  IF EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(@table) AND name = 'SANGAM_CX_' + @table)
    RETURN; -- already partitioned
  SELECT @pk = name FROM sys.key_constraints WHERE parent_object_id = OBJECT_ID(@table) AND type = 'PK';
  IF @pk IS NOT NULL
  BEGIN
    SET @sql = N'ALTER TABLE ' + QUOTENAME(@table) + N' DROP CONSTRAINT ' + QUOTENAME(@pk);
    EXEC (@sql);
    SET @sql = N'ALTER TABLE ' + QUOTENAME(@table) + N' ADD CONSTRAINT ' + QUOTENAME(@pk)
             + N' PRIMARY KEY NONCLUSTERED (ID_) ON [PRIMARY]';
    EXEC (@sql);
  END;
  SET @sql = N'CREATE CLUSTERED INDEX ' + QUOTENAME('SANGAM_CX_' + @table) + N' ON ' + QUOTENAME(@table)
           + N' (REMOVAL_TIME_) ON SANGAM_HI_PS (REMOVAL_TIME_)';
  EXEC (@sql);
END;
--rollback DROP PROCEDURE IF EXISTS SANGAM_HI_PARTITION_TABLE;

--changeset sangam:IR8-23-mssql-maintain dbms:mssql context:partitioned-history splitStatements:false
--comment: monthly maintenance: boundaries up to @months_ahead months from now
CREATE OR ALTER PROCEDURE SANGAM_HI_MAINTAIN @months_ahead INT AS
BEGIN
  SET NOCOUNT ON;
  DECLARE @i INT = 0, @b DATETIME;
  DECLARE @m DATETIME = DATEFROMPARTS(YEAR(GETUTCDATE()), MONTH(GETUTCDATE()), 1);
  WHILE @i <= @months_ahead
  BEGIN
    SET @b = DATEADD(MONTH, @i, @m);
    IF NOT EXISTS (SELECT 1 FROM sys.partition_range_values v JOIN sys.partition_functions f
                     ON f.function_id = v.function_id WHERE f.name = 'SANGAM_HI_PF' AND CAST(v.value AS DATETIME) = @b)
    BEGIN
      ALTER PARTITION SCHEME SANGAM_HI_PS NEXT USED [PRIMARY];
      ALTER PARTITION FUNCTION SANGAM_HI_PF () SPLIT RANGE (@b);
    END;
    SET @i = @i + 1;
  END;
END;
--rollback DROP PROCEDURE IF EXISTS SANGAM_HI_MAINTAIN;

--changeset sangam:IR8-24-mssql-convert dbms:mssql context:partitioned-history
--comment: moves the high-volume history tables onto the partition scheme
EXEC SANGAM_HI_PARTITION_TABLE 'ACT_HI_ACTINST';
EXEC SANGAM_HI_PARTITION_TABLE 'ACT_HI_TASKINST';
EXEC SANGAM_HI_PARTITION_TABLE 'ACT_HI_VARINST';
EXEC SANGAM_HI_PARTITION_TABLE 'ACT_HI_DETAIL';
EXEC SANGAM_HI_PARTITION_TABLE 'ACT_HI_IDENTITYLINK';
EXEC SANGAM_HI_PARTITION_TABLE 'ACT_HI_OP_LOG';
EXEC SANGAM_HI_PARTITION_TABLE 'ACT_HI_INCIDENT';
EXEC SANGAM_HI_PARTITION_TABLE 'ACT_HI_JOB_LOG';
EXEC SANGAM_HI_PARTITION_TABLE 'ACT_HI_EXT_TASK_LOG';
--rollback SELECT 1;
