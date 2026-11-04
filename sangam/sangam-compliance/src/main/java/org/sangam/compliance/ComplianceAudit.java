// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Hashes;

import java.sql.Connection;
import java.util.Map;

/**
 * Compliance evidence on the existing core {@link AuditChain} (class-B hash chain, X.2.3.3–X.2.3.4). Nothing here
 * re-implements hashing: every event is an {@code AuditChain.append} in the caller's transaction, so the consent
 * ledger (proof of notice and consent), the breach timeline and the incident-reporting timeline are tamper evident and verified by {@link org.sangam.compliance.logs.AuditIntegrityVerifier}.
 *
 * <p>Chains:
 * <ul>
 *   <li>{@code cmp:<tenant>} — organisation events (notices, purposes, breaches, incidents, impact assessments, audits, contacts,
 *       transfer decisions, reports).</li>
 *   <li>{@code cmp:p:<32 hex>} — one chain per data principal (consent given / withdrawn, rights requests,
 *       nominations, guardian verification, retention notices). The id is a hash of tenant and principal reference, so
 *       no personal data appears in {@code chain_id}, and per-principal chains avoid one hot {@code ROOT_SEQ} row.</li>
 *   <li>The workflow root's own chain for events that belong to a case (erasure, purpose completion).</li>
 * </ul>
 * Payloads hold references and counts, not personal data; where a value is needed as proof, its SHA-256 is stored.
 */
public final class ComplianceAudit {

    public static final String NOTICE_PUBLISHED = "CMP_NOTICE_PUBLISHED";
    public static final String NOTICE_SENT = "CMP_NOTICE_SENT";
    public static final String PURPOSE_DEFINED = "CMP_PURPOSE_DEFINED";
    public static final String PROCESSING_REGISTERED = "CMP_PROCESSING_REGISTERED";
    public static final String CONSENT_GIVEN = "CMP_CONSENT_GIVEN";
    public static final String CONSENT_WITHDRAWN = "CMP_CONSENT_WITHDRAWN";
    public static final String OBJECTION_RECORDED = "CMP_LEGITIMATE_USE_OBJECTION";
    public static final String PURPOSE_BINDING_DENIED = "CMP_PURPOSE_BINDING_DENIED";
    public static final String GUARDIAN_VERIFIED = "CMP_GUARDIAN_VERIFIED";
    public static final String GUARDIAN_REJECTED = "CMP_GUARDIAN_REJECTED";
    public static final String CHILD_PROCESSING_BLOCKED = "CMP_CHILD_PROCESSING_BLOCKED";
    public static final String RIGHTS_REQUEST_RECEIVED = "CMP_RIGHTS_REQUEST_RECEIVED";
    public static final String RIGHTS_REQUEST_UPDATED = "CMP_RIGHTS_REQUEST_UPDATED";
    public static final String RIGHTS_REQUEST_CLOSED = "CMP_RIGHTS_REQUEST_CLOSED";
    public static final String RIGHTS_REQUEST_ESCALATED = "CMP_RIGHTS_REQUEST_ESCALATED";
    public static final String NOMINATION_CHANGED = "CMP_NOMINATION_CHANGED";
    public static final String RETENTION_NOTICE_SENT = "CMP_RETENTION_PRE_ERASURE_NOTICE";
    public static final String RETENTION_ERASED = "CMP_RETENTION_ERASED";
    public static final String RETENTION_HELD = "CMP_RETENTION_HELD";
    public static final String RETENTION_RETAINED = "CMP_RETENTION_RETAINED_BY_LAW";
    public static final String BREACH_REGISTERED = "CMP_BREACH_REGISTERED";
    public static final String BREACH_AUTHORITY_NOTIFIED = "CMP_BREACH_AUTHORITY_NOTIFIED";
    public static final String BREACH_PRINCIPALS_NOTIFIED = "CMP_BREACH_PRINCIPALS_NOTIFIED";
    public static final String BREACH_REPORT_SUBMITTED = "CMP_BREACH_DETAILED_REPORT";
    public static final String BREACH_EXTENSION = "CMP_BREACH_EXTENSION";
    public static final String BREACH_OVERDUE = "CMP_BREACH_OVERDUE";
    public static final String BREACH_CLOSED = "CMP_BREACH_CLOSED";
    public static final String INCIDENT_REGISTERED = "CMP_INCIDENT_REGISTERED";
    public static final String INCIDENT_REPORTED = "CMP_INCIDENT_REPORTED";
    public static final String INCIDENT_FOLLOW_UP = "CMP_INCIDENT_FOLLOW_UP";
    public static final String INCIDENT_OVERDUE = "CMP_INCIDENT_OVERDUE";
    public static final String LOG_SEGMENT_SEALED = "CMP_LOG_SEGMENT_SEALED";
    public static final String LOG_SEGMENT_PURGED = "CMP_LOG_SEGMENT_PURGED";
    public static final String LOG_SOURCE_REGISTERED = "CMP_LOG_SOURCE_REGISTERED";
    public static final String NTP_CHECKED = "CMP_NTP_CHECKED";
    public static final String AUDIT_VERIFIED = "CMP_AUDIT_CHAIN_VERIFIED";
    public static final String CONTACT_CHANGED = "CMP_CONTACT_CHANGED";
    public static final String ELEVATED_TIER_DESIGNATED = "CMP_ELEVATED_TIER_DESIGNATED";
    public static final String IMPACT_ASSESSMENT_RECORDED = "CMP_IMPACT_ASSESSMENT_RECORDED";
    public static final String AUDIT_EVIDENCE_RECORDED = "CMP_AUDIT_EVIDENCE_RECORDED";
    public static final String TRANSFER_DECIDED = "CMP_TRANSFER_DECIDED";
    public static final String DISCLOSURE_RECORDED = "CMP_DISCLOSURE_RECORDED";
    public static final String LAWFUL_REQUEST_CHANGED = "CMP_LAWFUL_REQUEST_CHANGED";
    public static final String REPORT_GENERATED = "CMP_EVIDENCE_REPORT_GENERATED";

    private final AuditChain chain;

    public ComplianceAudit(AuditChain chain) {
        this.chain = chain;
    }

    public static String tenantChain(String tenantId) {
        return "cmp:" + tenantId;
    }

    /** Per-principal chain id: no personal data in {@code chain_id} (VARCHAR(64)). */
    public static String principalChain(String tenantId, String principalRef) {
        return "cmp:p:" + Hashes.hex(Hashes.sha256(tenantId + "\u0000" + principalRef)).substring(0, 32);
    }

    /** Reference to a principal used in audit payloads: a hash, so the chain can be kept after erasure. */
    public static String principalToken(String tenantId, String principalRef) {
        return Hashes.hex(Hashes.sha256("principal:" + tenantId + "\u0000" + principalRef)).substring(0, 32);
    }

    public AuditChain.AuditEvent organisation(Connection c, String tenantId, String eventType, Map<String, ?> payload) {
        return chain.append(c, tenantId, tenantChain(tenantId), eventType, payload);
    }

    public AuditChain.AuditEvent principal(Connection c, String tenantId, String principalRef, String eventType,
                                           Map<String, ?> payload) {
        return chain.append(c, tenantId, principalChain(tenantId, principalRef), eventType, payload);
    }

    public AuditChain.AuditEvent root(Connection c, String tenantId, String rootId, String eventType, Map<String, ?> payload) {
        return chain.append(c, tenantId, rootId, eventType, payload);
    }

    public AuditChain chain() {
        return chain;
    }
}
