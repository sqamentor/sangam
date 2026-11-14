-- SPDX-License-Identifier: Apache-2.0
-- Reference DDL of the table org.sangam.integration.dsc.JdbcDscPreparedStore reads and writes (PostgreSQL flavour;
-- H2 in MODE=PostgreSQL accepts it). NOT a changelog: the changelogs belong to sangam-persistence (W4-G generator,
-- W4-H REQUEST). Owner decision 165: one prepared DSC signature, bound to tenant, task, document version, signer
-- principal, signer certificate and the server-issued prepare_id; it expires and is used at most once.
CREATE TABLE INTEGRATION_DSC_PREPARED (
  tenant_id               VARCHAR(64)  NOT NULL,
  prepare_id              VARCHAR(64)  NOT NULL,   -- server-issued, 128-bit random, hex
  task_id                 VARCHAR(64)  NOT NULL,
  document_id             VARCHAR(64)  NOT NULL,
  document_version        INTEGER      NOT NULL,   -- the version that was prepared
  document_sha256         VARCHAR(64)  NOT NULL,
  principal_id            VARCHAR(255) NOT NULL,   -- the signer: the principal who prepared it
  signer_cert_sha256      VARCHAR(64)  NOT NULL,
  enrolment_id            VARCHAR(64)  NOT NULL,   -- the approved enrolment checked at prepare (decision 241)
  enrolment_approval_seq  BIGINT       NOT NULL,   -- its approval_seq then: the signature counts only while unchanged
  intended_signer_name    VARCHAR(255) NOT NULL,   -- shown, never trusted (decision 227: binding = signer_cert_sha256)
  algorithm               VARCHAR(32)  NOT NULL,   -- SHA256withRSA or SHA256withECDSA
  prepared_digest_sha256  VARCHAR(64)  NOT NULL,
  signed_attributes       BYTEA        NOT NULL,   -- DER of the CMS signed attributes the token signs
  signing_time            TIMESTAMP WITH TIME ZONE NOT NULL,
  prepared_pdf            BYTEA,                   -- dropped (NULL) once the entry has an outcome
  created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at              TIMESTAMP WITH TIME ZONE NOT NULL,
  used_at                 TIMESTAMP WITH TIME ZONE,
  outcome                 VARCHAR(32),             -- VERIFIED_VALID, VERIFIED_INVALID or INDETERMINATE
  reason_code             VARCHAR(64),
  signed_document_version INTEGER,
  completed_at            TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (tenant_id, prepare_id)
);
CREATE INDEX INTEGRATION_DSC_PREPARED_TASK ON INTEGRATION_DSC_PREPARED (tenant_id, task_id);
CREATE INDEX INTEGRATION_DSC_PREPARED_EXPIRY ON INTEGRATION_DSC_PREPARED (expires_at);
