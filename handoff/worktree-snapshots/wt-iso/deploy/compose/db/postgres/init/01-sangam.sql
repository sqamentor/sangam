-- SPDX-License-Identifier: Apache-2.0
-- Runs once on an empty data directory (docker-entrypoint-initdb.d). The schema itself is created by
-- Liquibase (sangam-persistence) and by Operaton at server start-up.
ALTER DATABASE sangam SET timezone TO 'UTC';
ALTER DATABASE sangam SET default_transaction_isolation TO 'read committed';
-- Read-only role for reporting / backup verification.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sangam_readonly') THEN
    CREATE ROLE sangam_readonly NOLOGIN;
  END IF;
END $$;
GRANT CONNECT ON DATABASE sangam TO sangam_readonly;
