-- SPDX-License-Identifier: Apache-2.0
-- Reference DDL of every table sangam-security reads or writes (PostgreSQL flavour; H2 in MODE=PostgreSQL accepts it).
-- NOT a changelog: WS-ENGINE owns the Liquibase changelogs in sangam-persistence (ARCHITECTURE.md §3). This file is the
-- contract the integrator reconciles with sangam-persistence/src/main/resources/db/changelog/*/sangam-0001-baseline.sql.
-- Types per dialect follow core Dialect tokens: VARCHAR->VARCHAR2/NVARCHAR, TEXT->CLOB/NVARCHAR(MAX)/LONGTEXT,
-- TIMESTAMP WITH TIME ZONE->DATETIMEOFFSET/DATETIME(6), SMALLINT 0/1 booleans everywhere.
-- Core tables used unchanged: AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ (core AuditChain), AUTH_POLICY_VERSION.

-- ---------------------------------------------------------------- VI.4 effective grants (A-3, A-13, A-16)
CREATE TABLE AUTH_EFFECTIVE (
  tenant_id      VARCHAR(64)  NOT NULL,
  principal_id   VARCHAR(255) NOT NULL,          -- user:/group:/role:/seat: prefixed
  resource_type  VARCHAR(64)  NOT NULL,          -- or '*'
  resource_id    VARCHAR(255) NOT NULL,          -- id, '*', or scope 'procinst:<id>' / 'def:<key>' / 'bk:<key>' ...
  deny_flag      SMALLINT     NOT NULL,
  perms          BIGINT       NOT NULL,
  condition_id   VARCHAR(64),                    -- NULL = unconditional (core AuthorizationPolicy reads this)
  cond_key       VARCHAR(64)  NOT NULL,          -- COALESCE(condition_id,'-'): part of the key (NEW vs persistence)
  policy_version BIGINT       NOT NULL,
  PRIMARY KEY (tenant_id, principal_id, resource_type, resource_id, deny_flag, cond_key)
);
-- every EXISTS branch is a seek on (tenant, type, resource_id, principal) (A-16, D-55)
CREATE INDEX AUTH_EFFECTIVE_RES ON AUTH_EFFECTIVE (tenant_id, resource_type, resource_id, principal_id, deny_flag);
CREATE INDEX AUTH_EFFECTIVE_PRIN ON AUTH_EFFECTIVE (tenant_id, principal_id, resource_type);

CREATE TABLE AUTH_POLICY_VERSION (
  tenant_id      VARCHAR(64) NOT NULL PRIMARY KEY,
  policy_version BIGINT      NOT NULL
);

-- grant sources: AUTH_EFFECTIVE.perms = OR(perms) of all sources of a key (incremental maintenance)
CREATE TABLE AUTH_GRANT_SOURCE (
  tenant_id     VARCHAR(64)  NOT NULL,
  principal_id  VARCHAR(255) NOT NULL,
  resource_type VARCHAR(64)  NOT NULL,
  resource_id   VARCHAR(255) NOT NULL,
  deny_flag     SMALLINT     NOT NULL,
  cond_key      VARCHAR(64)  NOT NULL,
  source_id     VARCHAR(255) NOT NULL,           -- policy:<id> role:<id> task:<id> privilege:<id> worker:<p>:<topic>
  source_key    VARCHAR(64)  NOT NULL,           -- lower-case hex SHA-256 of source_id; replaces it in the key
  perms         BIGINT       NOT NULL,
  PRIMARY KEY (tenant_id, principal_id, resource_type, resource_id, deny_flag, cond_key, source_key)
);
CREATE INDEX AUTH_GRANT_SOURCE_SRC ON AUTH_GRANT_SOURCE (tenant_id, source_id);
CREATE INDEX AUTH_GRANT_SOURCE_PRIN ON AUTH_GRANT_SOURCE (tenant_id, principal_id, source_id);

CREATE TABLE AUTH_CONDITION (
  tenant_id     VARCHAR(64) NOT NULL,
  condition_id  VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  expression    TEXT        NOT NULL,            -- Condition JSON
  PRIMARY KEY (tenant_id, condition_id)
);
CREATE INDEX AUTH_CONDITION_TYPE ON AUTH_CONDITION (tenant_id, resource_type);

-- materialised membership: member (user:p / group:g / seat:s) -> principal (group:/role:/seat:), effective-dated
CREATE TABLE AUTH_MEMBERSHIP (
  tenant_id    VARCHAR(64)  NOT NULL,
  member_id    VARCHAR(255) NOT NULL,
  principal_id VARCHAR(255) NOT NULL,
  source_id    VARCHAR(255) NOT NULL,            -- idp, posting:<id>, rolebinding:<id>, grp:<g>:<p>
  valid_from   TIMESTAMP WITH TIME ZONE NOT NULL,
  valid_to     TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (tenant_id, member_id, principal_id, source_id)
);
CREATE INDEX AUTH_MEMBERSHIP_SRC ON AUTH_MEMBERSHIP (tenant_id, source_id);

-- ---------------------------------------------------------------- admin: policies, roles, groups, privileges, tokens
CREATE TABLE SEC_POLICY (
  tenant_id VARCHAR(64) NOT NULL, policy_id VARCHAR(64) NOT NULL, name VARCHAR(255), effect VARCHAR(8) NOT NULL,
  resource_type VARCHAR(64) NOT NULL, resource_id VARCHAR(255) NOT NULL, definition TEXT NOT NULL,
  status VARCHAR(16) NOT NULL, version BIGINT NOT NULL, updated_by VARCHAR(255), updated_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (tenant_id, policy_id)
);
CREATE TABLE SEC_POLICY_CHANGE (
  tenant_id VARCHAR(64) NOT NULL, change_id VARCHAR(64) NOT NULL, policy_id VARCHAR(64) NOT NULL,
  operation VARCHAR(16) NOT NULL, definition TEXT NOT NULL, requested_by VARCHAR(255) NOT NULL,
  requested_at TIMESTAMP WITH TIME ZONE NOT NULL, status VARCHAR(16) NOT NULL, decided_by VARCHAR(255),
  decided_at TIMESTAMP WITH TIME ZONE, reason VARCHAR(1000),
  PRIMARY KEY (tenant_id, change_id)
);
CREATE TABLE SEC_ROLE (
  tenant_id VARCHAR(64) NOT NULL, role_id VARCHAR(64) NOT NULL, name VARCHAR(255), rules TEXT NOT NULL,
  updated_by VARCHAR(255), updated_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, role_id)
);
CREATE TABLE SEC_ROLE_BINDING (
  tenant_id VARCHAR(64) NOT NULL, binding_id VARCHAR(64) NOT NULL, role_id VARCHAR(64) NOT NULL,
  member_id VARCHAR(255) NOT NULL, valid_from TIMESTAMP WITH TIME ZONE NOT NULL, valid_to TIMESTAMP WITH TIME ZONE,
  created_by VARCHAR(255), PRIMARY KEY (tenant_id, binding_id)
);
CREATE INDEX SEC_ROLE_BINDING_ROLE ON SEC_ROLE_BINDING (tenant_id, role_id);
CREATE TABLE SEC_GROUP (
  tenant_id VARCHAR(64) NOT NULL, group_id VARCHAR(255) NOT NULL, name VARCHAR(255), external_ref VARCHAR(255),
  source VARCHAR(16) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, group_id)
);
CREATE TABLE SEC_GROUP_MEMBER (
  tenant_id VARCHAR(64) NOT NULL, group_id VARCHAR(255) NOT NULL, person_id VARCHAR(64) NOT NULL,
  source VARCHAR(16) NOT NULL, added_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, group_id, person_id)
);
CREATE INDEX SEC_GROUP_MEMBER_P ON SEC_GROUP_MEMBER (tenant_id, person_id);
CREATE TABLE SEC_PRIVILEGE (
  tenant_id VARCHAR(64) NOT NULL, privilege_id VARCHAR(64) NOT NULL, name VARCHAR(255), PRIMARY KEY (tenant_id, privilege_id)
);
CREATE TABLE SEC_PRIVILEGE_MAPPING (
  tenant_id VARCHAR(64) NOT NULL, privilege_id VARCHAR(64) NOT NULL, principal_id VARCHAR(255) NOT NULL,
  deny_flag SMALLINT NOT NULL, PRIMARY KEY (tenant_id, privilege_id, principal_id)
);
CREATE TABLE SEC_API_TOKEN (
  token_id VARCHAR(64) NOT NULL PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, owner_person_id VARCHAR(64) NOT NULL,
  name VARCHAR(255), secret_hash VARCHAR(64) NOT NULL, scopes TEXT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL, last_used_at TIMESTAMP WITH TIME ZONE, revoked_at TIMESTAMP WITH TIME ZONE,
  created_by VARCHAR(255)
);
CREATE INDEX SEC_API_TOKEN_OWNER ON SEC_API_TOKEN (tenant_id, owner_person_id);
CREATE TABLE SEC_WORKER_BINDING (
  tenant_id VARCHAR(64) NOT NULL, worker_person_id VARCHAR(64) NOT NULL, topic VARCHAR(255) NOT NULL,
  created_by VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, worker_person_id, topic)
);
CREATE TABLE SEC_BULK_PLAN (
  tenant_id VARCHAR(64) NOT NULL, plan_id VARCHAR(64) NOT NULL, operation VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL, item_count BIGINT NOT NULL, count_capped SMALLINT NOT NULL,
  requested_by VARCHAR(255) NOT NULL, reason VARCHAR(1000) NOT NULL, status VARCHAR(32) NOT NULL,
  approved_by VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, plan_id)
);
CREATE TABLE SEC_FIELD_RULE (
  tenant_id VARCHAR(64) NOT NULL, resource_type VARCHAR(64) NOT NULL, scope_key VARCHAR(255) NOT NULL,
  field_name VARCHAR(255) NOT NULL, required_perm VARCHAR(32) NOT NULL,
  PRIMARY KEY (tenant_id, resource_type, scope_key, field_name)
);

-- ---------------------------------------------------------------- tenants (AJ-01)
CREATE TABLE SEC_TENANT (
  tenant_id VARCHAR(64) NOT NULL PRIMARY KEY, name_en VARCHAR(255) NOT NULL, name_hi VARCHAR(255), name_local VARCHAR(255),
  hosting_profile VARCHAR(64), status VARCHAR(16) NOT NULL, config TEXT NOT NULL, quotas TEXT NOT NULL,
  four_eyes SMALLINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, activated_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE SEC_TENANT_CHECKLIST (
  tenant_id VARCHAR(64) NOT NULL, item VARCHAR(64) NOT NULL, evidence_ref VARCHAR(1000), recorded_by VARCHAR(255),
  recorded_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, item)
);
-- SL-43 per-tenant quota counting windows (JdbcTenantQuotas; shared by all nodes)
CREATE TABLE SEC_TENANT_QUOTA_WINDOW (
  tenant_id VARCHAR(64) NOT NULL, quota_name VARCHAR(64) NOT NULL, window_start BIGINT NOT NULL, used BIGINT NOT NULL,
  refused SMALLINT NOT NULL, PRIMARY KEY (tenant_id, quota_name, window_start)
);

-- ---------------------------------------------------------------- organisation (IX.5)
CREATE TABLE SEC_PERSON (
  tenant_id VARCHAR(64) NOT NULL, person_id VARCHAR(64) NOT NULL, display_name VARCHAR(255), external_subject VARCHAR(255),
  email VARCHAR(255), status VARCHAR(16) NOT NULL, source VARCHAR(16) DEFAULT 'LOCAL' NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, person_id)
);
CREATE UNIQUE INDEX SEC_PERSON_SUBJECT ON SEC_PERSON (tenant_id, external_subject);
CREATE TABLE SEC_ORG_LEVEL (
  tenant_id VARCHAR(64) NOT NULL, level_no INTEGER NOT NULL, level_type VARCHAR(64) NOT NULL, PRIMARY KEY (tenant_id, level_no)
);
CREATE TABLE SEC_ORG_UNIT (
  tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL, level_type VARCHAR(64) NOT NULL, level_no INTEGER NOT NULL,
  name_en VARCHAR(255), name_hi VARCHAR(255), head_seat_id VARCHAR(64), status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, unit_id)
);
CREATE TABLE SEC_ORG_UNIT_PARENT (
  tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL, parent_id VARCHAR(64),
  valid_from TIMESTAMP WITH TIME ZONE NOT NULL, valid_to TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, unit_id, valid_from)
);
CREATE INDEX SEC_ORG_UNIT_PARENT_P ON SEC_ORG_UNIT_PARENT (tenant_id, parent_id);
-- effective-dated renames; before the first rename the name is SEC_ORG_UNIT.name_en/name_hi (never updated)
CREATE TABLE SEC_ORG_UNIT_NAME (
  tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL, valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
  valid_to TIMESTAMP WITH TIME ZONE, name_en VARCHAR(255) NOT NULL, name_hi VARCHAR(255), order_ref VARCHAR(255),
  created_by VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, unit_id, valid_from)
);
CREATE TABLE SEC_SEAT (
  tenant_id VARCHAR(64) NOT NULL, seat_id VARCHAR(64) NOT NULL, designation VARCHAR(255) NOT NULL, rank_no INTEGER NOT NULL,
  pool_flag SMALLINT NOT NULL, escalation_seat_id VARCHAR(64), status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL, abolished_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, seat_id)
);
CREATE TABLE SEC_SEAT_PLACEMENT (
  tenant_id VARCHAR(64) NOT NULL, seat_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL,
  valid_from TIMESTAMP WITH TIME ZONE NOT NULL, valid_to TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, seat_id, valid_from)
);
CREATE INDEX SEC_SEAT_PLACEMENT_U ON SEC_SEAT_PLACEMENT (tenant_id, unit_id);
CREATE TABLE SEC_POSTING (
  tenant_id VARCHAR(64) NOT NULL, posting_id VARCHAR(64) NOT NULL, seat_id VARCHAR(64) NOT NULL, person_id VARCHAR(64) NOT NULL,
  kind VARCHAR(32) NOT NULL, valid_from TIMESTAMP WITH TIME ZONE NOT NULL, valid_to TIMESTAMP WITH TIME ZONE,
  order_ref VARCHAR(255), power_cap VARCHAR(40), created_by VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at TIMESTAMP WITH TIME ZONE, expiry_notified SMALLINT NOT NULL, PRIMARY KEY (tenant_id, posting_id)
);
CREATE INDEX SEC_POSTING_SEAT ON SEC_POSTING (tenant_id, seat_id, valid_from);
CREATE INDEX SEC_POSTING_PERSON ON SEC_POSTING (tenant_id, person_id, valid_from);
CREATE TABLE SEC_LEAVE (
  tenant_id VARCHAR(64) NOT NULL, leave_id VARCHAR(64) NOT NULL, person_id VARCHAR(64) NOT NULL,
  valid_from TIMESTAMP WITH TIME ZONE NOT NULL, valid_to TIMESTAMP WITH TIME ZONE NOT NULL, substitute_person_id VARCHAR(64),
  requested_by VARCHAR(255), approved_by VARCHAR(255), status VARCHAR(16) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (tenant_id, leave_id)
);
CREATE INDEX SEC_LEAVE_PERSON ON SEC_LEAVE (tenant_id, person_id, valid_from);

-- ---------------------------------------------------------------- delegation of powers (IX.5 rule 3)
CREATE TABLE SEC_POWER_TABLE (
  tenant_id VARCHAR(64) NOT NULL, table_key VARCHAR(64) NOT NULL, version INTEGER NOT NULL, status VARCHAR(16) NOT NULL,
  effective_from TIMESTAMP WITH TIME ZONE NOT NULL, effective_to TIMESTAMP WITH TIME ZONE, dmn_xml TEXT NOT NULL,
  dmn_hash VARCHAR(64) NOT NULL, decision_id VARCHAR(255) NOT NULL, authority_ref VARCHAR(1000), change_note VARCHAR(1000),
  author VARCHAR(255) NOT NULL, approver VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, table_key, version)
);

-- ---------------------------------------------------------------- SoD and approvals (IX.5 rule 5, VI.4 rule 5)
CREATE TABLE SEC_CASE_ACTOR (
  tenant_id VARCHAR(64) NOT NULL, case_id VARCHAR(255) NOT NULL, duty VARCHAR(32) NOT NULL, person_id VARCHAR(64) NOT NULL,
  seat_id VARCHAR(64) NOT NULL, acted_for_seat_id VARCHAR(64), task_id VARCHAR(64), exception_id VARCHAR(64),
  acted_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, case_id, duty, person_id, seat_id)
);
CREATE INDEX SEC_CASE_ACTOR_P ON SEC_CASE_ACTOR (tenant_id, person_id);
CREATE TABLE SEC_SOD_EXCEPTION (
  tenant_id VARCHAR(64) NOT NULL, exception_id VARCHAR(64) NOT NULL, case_id VARCHAR(255) NOT NULL,
  person_id VARCHAR(64) NOT NULL, seat_id VARCHAR(64), duty VARCHAR(32) NOT NULL, reason VARCHAR(1000) NOT NULL,
  requested_by VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL, approved_by VARCHAR(255), approver_seat_id VARCHAR(64),
  approval_reason VARCHAR(1000), expires_at TIMESTAMP WITH TIME ZONE, decided_at TIMESTAMP WITH TIME ZONE,
  used_at TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, exception_id)
);
CREATE INDEX SEC_SOD_EXCEPTION_CASE ON SEC_SOD_EXCEPTION (tenant_id, case_id, person_id, duty);
CREATE TABLE SEC_APPROVAL (
  tenant_id VARCHAR(64) NOT NULL, approval_id VARCHAR(64) NOT NULL, case_id VARCHAR(255) NOT NULL, task_id VARCHAR(64),
  task_version BIGINT NOT NULL, payload_hash VARCHAR(64) NOT NULL, material TEXT NOT NULL,
  approver_person_id VARCHAR(64) NOT NULL, approver_seat_id VARCHAR(64), acted_for_seat_id VARCHAR(64),
  rule_ref VARCHAR(255), power_limit VARCHAR(40), status VARCHAR(16) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  invalidated_at TIMESTAMP WITH TIME ZONE, invalidated_reason VARCHAR(1000), PRIMARY KEY (tenant_id, approval_id)
);
CREATE INDEX SEC_APPROVAL_CASE ON SEC_APPROVAL (tenant_id, case_id, status);

-- ---------------------------------------------------------------- tenant data keys (FieldEncryptor)
CREATE TABLE SEC_TENANT_KEY (
  tenant_id VARCHAR(64) NOT NULL, key_id VARCHAR(80) NOT NULL, version INTEGER NOT NULL,
  status VARCHAR(16) NOT NULL,                     -- ACTIVE | DECRYPT_ONLY | DESTROYED
  kek_id VARCHAR(64) NOT NULL, wrapped_key VARCHAR(255),   -- base64(nonce||AES-GCM(DEK)); NULL once destroyed
  created_at TIMESTAMP WITH TIME ZONE NOT NULL, retired_at TIMESTAMP WITH TIME ZONE, destroyed_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (tenant_id, version)
);
CREATE UNIQUE INDEX SEC_TENANT_KEY_ID ON SEC_TENANT_KEY (tenant_id, key_id);
