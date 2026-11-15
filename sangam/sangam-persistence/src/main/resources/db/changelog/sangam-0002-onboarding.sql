--liquibase formatted sql

-- sangam-onboarding run state (org.sangam.onboarding.flow.OnboardingStore.TEMPLATES), one changeset per dialect.
-- Decisions 205 and 379: the PostgreSQL changeset also names cockroachdb, Liquibase's short name for a CockroachDB
-- server (a dbms value is not part of the checksum, CockroachDbChangelogDbmsTest). Likewise the MySQL changeset names
-- mariadb, Liquibase's short name for a MariaDB server (SL-112, MariaDbChangelogDbmsTest).

--changeset sangam:onb-run-postgresql dbms:postgresql,cockroachdb
CREATE TABLE ONB_RUN (run_id VARCHAR(64) NOT NULL PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, status VARCHAR(64) NOT NULL, updated_ms BIGINT NOT NULL, document_json TEXT NOT NULL);
CREATE INDEX ONB_RUN_TENANT ON ONB_RUN (tenant_id, updated_ms);

--changeset sangam:onb-run-oracle dbms:oracle
CREATE TABLE ONB_RUN (run_id VARCHAR2(64 CHAR) NOT NULL PRIMARY KEY, tenant_id VARCHAR2(64 CHAR) NOT NULL, status VARCHAR2(64 CHAR) NOT NULL, updated_ms NUMBER(19) NOT NULL, document_json CLOB NOT NULL);
CREATE INDEX ONB_RUN_TENANT ON ONB_RUN (tenant_id, updated_ms);

--changeset sangam:onb-run-mssql dbms:mssql
CREATE TABLE ONB_RUN (run_id NVARCHAR(64) NOT NULL PRIMARY KEY, tenant_id NVARCHAR(64) NOT NULL, status NVARCHAR(64) NOT NULL, updated_ms BIGINT NOT NULL, document_json NVARCHAR(MAX) NOT NULL);
CREATE INDEX ONB_RUN_TENANT ON ONB_RUN (tenant_id, updated_ms);

--changeset sangam:onb-run-mysql dbms:mysql,mariadb
CREATE TABLE ONB_RUN (run_id VARCHAR(64) NOT NULL PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, status VARCHAR(64) NOT NULL, updated_ms BIGINT NOT NULL, document_json LONGTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE INDEX ONB_RUN_TENANT ON ONB_RUN (tenant_id, updated_ms);

--changeset sangam:onb-run-h2 dbms:h2
CREATE TABLE ONB_RUN (run_id VARCHAR(64) NOT NULL PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, status VARCHAR(64) NOT NULL, updated_ms BIGINT NOT NULL, document_json CLOB NOT NULL);
CREATE INDEX ONB_RUN_TENANT ON ONB_RUN (tenant_id, updated_ms);
