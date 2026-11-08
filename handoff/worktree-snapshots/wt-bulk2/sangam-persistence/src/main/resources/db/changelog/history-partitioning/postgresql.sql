--liquibase formatted sql

-- IR-8, PostgreSQL 11+: ACT_HI_* range-partitioned by removal_time_ (monthly) with a DEFAULT partition for NULL
-- (running instances) and out-of-range values. PostgreSQL requires every PRIMARY KEY / UNIQUE index of a partitioned
-- table to contain the partition key, and removal_time_ is nullable, so the ID_ primary key becomes a plain index
-- (ids stay unique by generation: UUID / cell ids). Unique indexes of these tables become non-unique for the same reason.
-- Rows move between partitions when the engine sets removal_time_ (supported since PostgreSQL 11).

--changeset sangam:IR8-01-pg-add-partitions dbms:postgresql context:partitioned-history splitStatements:false
--comment: creates monthly partitions [from, from + months) of one partitioned history table (idempotent)
CREATE OR REPLACE FUNCTION sangam_hi_add_partitions(p_table text, p_from date, p_months int) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
  t text := lower(p_table);
  d timestamp := date_trunc('month', p_from::timestamp);
  i int;
  lo timestamp;
  hi timestamp;
BEGIN
  FOR i IN 0 .. p_months - 1 LOOP
    lo := d + make_interval(months => i);
    hi := d + make_interval(months => i + 1);
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                   t || '_p' || to_char(lo, 'YYYYMM'), t, lo, hi);
  END LOOP;
END $$;
--rollback DROP FUNCTION IF EXISTS sangam_hi_add_partitions(text, date, int);

--changeset sangam:IR8-02-pg-partition-table dbms:postgresql context:partitioned-history splitStatements:false
--comment: converts one ACT_HI_* table into a range-partitioned table, keeping its rows and (non-unique) indexes
CREATE OR REPLACE FUNCTION sangam_hi_partition_table(p_table text, p_months_back int, p_months_ahead int) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
  t text := lower(p_table);
  old text := lower(p_table) || '_unpart';
  sch text;
  r record;
  defs text[] := '{}';
  def text;
BEGIN
  SELECT n.nspname INTO sch FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE c.relname = t AND pg_table_is_visible(c.oid);
  IF sch IS NULL THEN
    RAISE EXCEPTION 'IR-8: table % not found (build the engine first)', t;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid
              WHERE c.relname = t AND pg_table_is_visible(c.oid)) THEN
    RETURN; -- already partitioned
  END IF;
  -- remember the engine's secondary indexes (unique ones become non-unique), then free their names
  FOR r IN SELECT ic.relname AS iname, pg_get_indexdef(i.indexrelid) AS idef, i.indisprimary, i.indisunique
             FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid JOIN pg_class ic ON ic.oid = i.indexrelid
            WHERE c.relname = t AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = sch) LOOP
    IF NOT r.indisprimary THEN
      def := r.idef;
      IF r.indisunique THEN
        def := replace(def, 'CREATE UNIQUE INDEX', 'CREATE INDEX');
      END IF;
      defs := defs || def;
      EXECUTE format('DROP INDEX %I.%I', sch, r.iname);
    END IF;
  END LOOP;
  EXECUTE format('ALTER TABLE %I.%I RENAME TO %I', sch, t, old);
  EXECUTE format('CREATE TABLE %I.%I (LIKE %I.%I INCLUDING DEFAULTS) PARTITION BY RANGE (removal_time_)', sch, t, sch, old);
  EXECUTE format('CREATE TABLE %I.%I PARTITION OF %I.%I DEFAULT', sch, t || '_pdef', sch, t);
  PERFORM sangam_hi_add_partitions(t, (date_trunc('month', now()) - make_interval(months => p_months_back))::date,
                                   p_months_back + p_months_ahead);
  EXECUTE format('INSERT INTO %I.%I SELECT * FROM %I.%I', sch, t, sch, old);
  FOREACH def IN ARRAY defs LOOP
    EXECUTE def; -- the definitions name the table as it was called when they were read, i.e. the new parent
  END LOOP;
  EXECUTE format('CREATE INDEX %I ON %I.%I (id_)', t || '_id', sch, t);
  EXECUTE format('DROP TABLE %I.%I', sch, old);
END $$;
--rollback DROP FUNCTION IF EXISTS sangam_hi_partition_table(text, int, int);

--changeset sangam:IR8-03-pg-maintain dbms:postgresql context:partitioned-history splitStatements:false
--comment: monthly maintenance: partitions for the next p_months_ahead months of every partitioned ACT_HI_* table
CREATE OR REPLACE FUNCTION sangam_hi_maintain(p_months_ahead int) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE r record;
BEGIN
  FOR r IN SELECT c.relname FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid
            WHERE c.relname LIKE 'act\_hi\_%' AND pg_table_is_visible(c.oid) LOOP
    PERFORM sangam_hi_add_partitions(r.relname, current_date, p_months_ahead);
  END LOOP;
END $$;
--rollback DROP FUNCTION IF EXISTS sangam_hi_maintain(int);

--changeset sangam:IR8-04-pg-convert dbms:postgresql context:partitioned-history
--comment: converts the high-volume history tables (1 month back, 24 months ahead)
SELECT sangam_hi_partition_table('ACT_HI_ACTINST', 1, 24);
SELECT sangam_hi_partition_table('ACT_HI_TASKINST', 1, 24);
SELECT sangam_hi_partition_table('ACT_HI_VARINST', 1, 24);
SELECT sangam_hi_partition_table('ACT_HI_DETAIL', 1, 24);
SELECT sangam_hi_partition_table('ACT_HI_IDENTITYLINK', 1, 24);
SELECT sangam_hi_partition_table('ACT_HI_OP_LOG', 1, 24);
SELECT sangam_hi_partition_table('ACT_HI_INCIDENT', 1, 24);
SELECT sangam_hi_partition_table('ACT_HI_JOB_LOG', 1, 24);
SELECT sangam_hi_partition_table('ACT_HI_EXT_TASK_LOG', 1, 24);
--rollback SELECT 1;
