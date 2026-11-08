--liquibase formatted sql

-- IR-8, Oracle 19c+ (Enterprise Edition with the Partitioning option, or 23ai Free): ACT_HI_* range-partitioned by
-- REMOVAL_TIME_ with monthly partitions and a MAXVALUE partition P_MAX, which also holds NULL removal times (running
-- instances; Oracle sorts NULL above every value). Interval partitioning is NOT used: it cannot have a MAXVALUE
-- partition and rejects NULL keys (ORA-14300). The conversion is ONLINE (ALTER TABLE .. MODIFY PARTITION BY, 12.2+);
-- the ID_ primary key stays a global unique index, other indexes become local. ROW MOVEMENT is enabled because the
-- engine sets REMOVAL_TIME_ after insert.

--changeset sangam:IR8-11-ora-add-partitions dbms:oracle context:partitioned-history splitStatements:false endDelimiter:/
--comment: splits P_MAX so monthly partitions exist up to p_months_ahead months from now (idempotent)
CREATE OR REPLACE PROCEDURE SANGAM_HI_ADD_PARTITIONS(p_table IN VARCHAR2, p_months_ahead IN NUMBER) AS
  v_table VARCHAR2(128) := UPPER(p_table);
  v_month DATE := TRUNC(SYSDATE, 'MM');
  v_name VARCHAR2(128);
  v_cnt NUMBER;
BEGIN
  FOR i IN 0 .. p_months_ahead - 1 LOOP
    v_name := 'P_' || TO_CHAR(ADD_MONTHS(v_month, i), 'YYYYMM');
    SELECT COUNT(*) INTO v_cnt FROM USER_TAB_PARTITIONS WHERE TABLE_NAME = v_table AND PARTITION_NAME = v_name;
    IF v_cnt = 0 THEN
      EXECUTE IMMEDIATE 'ALTER TABLE ' || v_table || ' SPLIT PARTITION P_MAX AT (TIMESTAMP '''
        || TO_CHAR(ADD_MONTHS(v_month, i + 1), 'YYYY-MM-DD') || ' 00:00:00'') INTO (PARTITION ' || v_name
        || ', PARTITION P_MAX) UPDATE INDEXES';
    END IF;
  END LOOP;
END;
/
--rollback DROP PROCEDURE SANGAM_HI_ADD_PARTITIONS;

--changeset sangam:IR8-12-ora-partition-table dbms:oracle context:partitioned-history splitStatements:false endDelimiter:/
--comment: converts one ACT_HI_* table online into a range-partitioned table (idempotent)
CREATE OR REPLACE PROCEDURE SANGAM_HI_PARTITION_TABLE(p_table IN VARCHAR2, p_months_back IN NUMBER,
                                                      p_months_ahead IN NUMBER) AS
  v_table VARCHAR2(128) := UPPER(p_table);
  v_start DATE := ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -p_months_back);
  v_cnt NUMBER;
  v_sql CLOB;
BEGIN
  SELECT COUNT(*) INTO v_cnt FROM USER_TABLES WHERE TABLE_NAME = v_table;
  IF v_cnt = 0 THEN
    RAISE_APPLICATION_ERROR(-20801, 'IR-8: table ' || v_table || ' not found (build the engine first)');
  END IF;
  SELECT COUNT(*) INTO v_cnt FROM USER_PART_TABLES WHERE TABLE_NAME = v_table;
  IF v_cnt > 0 THEN
    RETURN; -- already partitioned
  END IF;
  -- first partition holds everything below the start month; then one partition per month
  v_sql := 'ALTER TABLE ' || v_table || ' MODIFY PARTITION BY RANGE (REMOVAL_TIME_) (PARTITION P_OLD VALUES LESS THAN '
        || '(TIMESTAMP ''' || TO_CHAR(v_start, 'YYYY-MM-DD') || ' 00:00:00'')';
  FOR i IN 0 .. p_months_back + p_months_ahead - 1 LOOP
    v_sql := v_sql || ', PARTITION P_' || TO_CHAR(ADD_MONTHS(v_start, i), 'YYYYMM') || ' VALUES LESS THAN (TIMESTAMP '''
          || TO_CHAR(ADD_MONTHS(v_start, i + 1), 'YYYY-MM-DD') || ' 00:00:00'')';
  END LOOP;
  v_sql := v_sql || ', PARTITION P_MAX VALUES LESS THAN (MAXVALUE)) ONLINE UPDATE INDEXES';
  EXECUTE IMMEDIATE 'ALTER TABLE ' || v_table || ' ENABLE ROW MOVEMENT';
  EXECUTE IMMEDIATE v_sql;
END;
/
--rollback DROP PROCEDURE SANGAM_HI_PARTITION_TABLE;

--changeset sangam:IR8-13-ora-maintain dbms:oracle context:partitioned-history splitStatements:false endDelimiter:/
--comment: monthly maintenance for every partitioned ACT_HI_* table
CREATE OR REPLACE PROCEDURE SANGAM_HI_MAINTAIN(p_months_ahead IN NUMBER) AS
BEGIN
  FOR r IN (SELECT TABLE_NAME FROM USER_PART_TABLES WHERE TABLE_NAME LIKE 'ACT\_HI\_%' ESCAPE '\') LOOP
    SANGAM_HI_ADD_PARTITIONS(r.TABLE_NAME, p_months_ahead);
  END LOOP;
END;
/
--rollback DROP PROCEDURE SANGAM_HI_MAINTAIN;

--changeset sangam:IR8-14-ora-convert dbms:oracle context:partitioned-history splitStatements:false endDelimiter:/
--comment: converts the high-volume history tables (1 month back, 24 months ahead)
BEGIN
  SANGAM_HI_PARTITION_TABLE('ACT_HI_ACTINST', 1, 24);
  SANGAM_HI_PARTITION_TABLE('ACT_HI_TASKINST', 1, 24);
  SANGAM_HI_PARTITION_TABLE('ACT_HI_VARINST', 1, 24);
  SANGAM_HI_PARTITION_TABLE('ACT_HI_DETAIL', 1, 24);
  SANGAM_HI_PARTITION_TABLE('ACT_HI_IDENTITYLINK', 1, 24);
  SANGAM_HI_PARTITION_TABLE('ACT_HI_OP_LOG', 1, 24);
  SANGAM_HI_PARTITION_TABLE('ACT_HI_INCIDENT', 1, 24);
  SANGAM_HI_PARTITION_TABLE('ACT_HI_JOB_LOG', 1, 24);
  SANGAM_HI_PARTITION_TABLE('ACT_HI_EXT_TASK_LOG', 1, 24);
END;
/
--rollback SELECT 1 FROM DUAL;
