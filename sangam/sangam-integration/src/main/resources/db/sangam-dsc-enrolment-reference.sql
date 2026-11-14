-- SPDX-License-Identifier: Apache-2.0
-- Reference DDL of the table org.sangam.integration.dsc.JdbcDscCertificateEnrolments reads and writes (PostgreSQL
-- flavour; H2 in MODE=PostgreSQL accepts it). NOT a changelog: the changelogs belong to sangam-persistence (W4-G
-- generator, W4-H REQUEST). Owner decision 227: one row per tenant and DSC certificate; at most one live enrolment
-- (PENDING or APPROVED); an admin other than the signer approves it; signing accepts only an APPROVED certificate.
CREATE TABLE INTEGRATION_DSC_CERTIFICATE (
  tenant_id               VARCHAR(64)   NOT NULL,
  certificate_sha256      VARCHAR(64)   NOT NULL,   -- SHA-256 of the DER, lower-case hex: the binding
  enrolment_id            VARCHAR(64)   NOT NULL,   -- server-issued, 128-bit random, hex; new on every enrolment
  principal_id            VARCHAR(255)  NOT NULL,   -- the signer who enrolled it
  issuer_dn               VARCHAR(1024) NOT NULL,   -- RFC 2253, derived from the DER
  serial_hex              VARCHAR(64)   NOT NULL,   -- derived from the DER
  subject_dn              VARCHAR(1024) NOT NULL,   -- shown, never trusted
  not_before              TIMESTAMP WITH TIME ZONE NOT NULL,
  not_after               TIMESTAMP WITH TIME ZONE NOT NULL,
  certificate             BYTEA         NOT NULL,   -- the DER, public
  status                  VARCHAR(16)   NOT NULL,   -- PENDING, APPROVED, REJECTED or REVOKED
  enrolled_at             TIMESTAMP WITH TIME ZONE NOT NULL,
  decided_by              VARCHAR(255),
  decided_at              TIMESTAMP WITH TIME ZONE,
  decision_reason         VARCHAR(512),
  approval_seq            BIGINT        DEFAULT 0 NOT NULL,   -- +1 on every move to APPROVED, kept across re-enrolment
  PRIMARY KEY (tenant_id, certificate_sha256)
);
CREATE UNIQUE INDEX INTEGRATION_DSC_CERTIFICATE_ID ON INTEGRATION_DSC_CERTIFICATE (tenant_id, enrolment_id);
CREATE INDEX INTEGRATION_DSC_CERTIFICATE_PRINCIPAL ON INTEGRATION_DSC_CERTIFICATE (tenant_id, principal_id);
CREATE INDEX INTEGRATION_DSC_CERTIFICATE_STATUS ON INTEGRATION_DSC_CERTIFICATE (tenant_id, status);
