// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Transactions;

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
 *   <li>{@code cmp:d:<32 hex>} — one <b>denial</b> chain per data principal (same hash as {@code cmp:p:}) for refusals
 *       that roll the caller back ({@link #denial}: purpose binding, child safeguards).</li>
 * </ul>
 * Payloads hold references and counts, not personal data; where a value is needed as proof, its SHA-256 is stored.
 *
 * <p><b>Denials (M-009, XII.4 #3).</b> A refusal throws, so the caller's transaction rolls back and an in-line event
 * would be lost. {@link #denial} therefore commits the event on its own, through {@code denialTx}. Two waits could hang
 * the caller there, and both are excluded:
 * <ul>
 *   <li><i>Pool wait (the SL-43 deadlock pattern).</i> The caller already holds a main-pool connection (engine command,
 *       REST transaction). {@code denialTx} must therefore be a <b>leaf</b> pool, separate from the one callers hold,
 *       whose connections are never held while another connection is requested (the server wires
 *       {@code sangam-compliance-audit} in {@code ComplianceWiring}, as {@code QuotaWiring} does for the quota audit).
 *       A wait for a leaf connection can never be part of a wait cycle.</li>
 *   <li><i>Row-lock wait ({@code ROOT_SEQ}).</i> {@code AuditChain.append} locks the chain's {@code ROOT_SEQ} row. If
 *       the separate transaction appended to a chain the caller had already appended to (the principal chain, after a
 *       consent in the same command), it would wait for the caller, which waits for it. The denial chain is written only
 *       by {@link #denial}, so only other short denial transactions ever hold its row, and they never wait for a caller
 *       (the {@code quota:<tenant>} chain of the quota audit follows the same rule).</li>
 * </ul>
 * With {@code denialTx == null} (tests, embedders without a separate pool) the event is appended in-line, on the
 * denial chain, and rolls back with the caller.
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
    /*
     * Owner decision 299: the X.4.0.3 Security catalogue names (SANGAM.md OJ-06), written on the organisation chain in
     * the same transaction as the incident change, next to the specific CMP_INCIDENT_* event of that change.
     */
    /** OJ-06 step 1: an incident report was opened; payload carries noticed_at (the start of the authority's clock). */
    public static final String SEC_INCIDENT_OPENED = "SEC_INCIDENT_OPENED";
    /** OJ-06 step 2: the incident type from the authority's catalogue (IN: CERT-In Annexure I), personal-data flag. */
    public static final String SEC_INCIDENT_CLASSIFIED = "SEC_INCIDENT_CLASSIFIED";
    /** OJ-06 step 6: the report was submitted to the authority; payload carries the submission references. */
    public static final String SEC_REPORT_SUBMITTED = "SEC_REPORT_SUBMITTED";
    /** OJ-06 step 8: a reported incident was closed. */
    public static final String SEC_INCIDENT_CLOSED = "SEC_INCIDENT_CLOSED";
    /**
     * OJ-06 step 7, owner decision 308: the 72 hour detailed breach report to the Data Protection Board, with the
     * notices to data principals; written only for the IN pack (DPDP s.8(6), r.7), next to {@link #BREACH_REPORT_SUBMITTED}.
     */
    public static final String DPDP_BREACH_REPORTED = "DPDP_BREACH_REPORTED";
    /**
     * Owner decision 348 (X.4.0.3 Security): an officer read a data principal's records (consents, rights requests)
     * that are not the officer's own. Written on that principal's chain, in the same transaction as the read, so the
     * principal's access trail shows every read by someone else (IN: DPDP Rules r.6(1)(c)); payload: the records read,
     * how many, the request id for a single record, and the reader.
     */
    public static final String PERSONAL_DATA_READ = "PERSONAL_DATA_READ";
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
    public static final String RESTRICTION_CHANGED = "CMP_RESTRICTION_CHANGED";
    public static final String RIGHTS_REQUEST_EXTENDED = "CMP_RIGHTS_REQUEST_EXTENDED";
    public static final String BREACH_RISK_ASSESSED = "CMP_BREACH_RISK_ASSESSED";
    public static final String BREACH_PLAN = "CMP_BREACH_NOTIFICATION_PLAN";
    public static final String BREACH_NOTIFICATION_SENT = "CMP_BREACH_NOTIFICATION_SENT";
    public static final String BREACH_DELAYED = "CMP_BREACH_LAW_ENFORCEMENT_DELAY";
    /** M-078: a national identifier was placed in the data vault (payload: reference key, type, masked number). */
    public static final String NATIONAL_ID_VAULTED = "CMP_NATIONAL_ID_VAULTED";
    /** M-078: a vaulted national identifier was read back (payload adds the purpose and the actor). */
    public static final String NATIONAL_ID_REVEALED = "CMP_NATIONAL_ID_REVEALED";

    private final AuditChain chain;
    private final Transactions denialTx;

    public ComplianceAudit(AuditChain chain) {
        this(chain, null);
    }

    /** @param denialTx leaf transactions for {@link #denial} (see the class comment); null = in-line */
    public ComplianceAudit(AuditChain chain, Transactions denialTx) {
        this.chain = chain;
        this.denialTx = denialTx;
    }

    public static String tenantChain(String tenantId) {
        return "cmp:" + tenantId;
    }

    /** Per-principal chain id: no personal data in {@code chain_id} (VARCHAR(64)). */
    public static String principalChain(String tenantId, String principalRef) {
        return AuditChain.hashedChain("cmp:p:", tenantId, principalRef);   // X1-06: the one chain-id formula
    }

    /** Per-principal denial chain id ({@link #denial}); never appended to in a caller's transaction. */
    public static String denialChain(String tenantId, String principalRef) {
        return AuditChain.hashedChain("cmp:d:", tenantId, principalRef);
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

    /**
     * Owner decision 348: {@code reader} read {@code count} {@code records} of {@code principalRef} (a list, or the one
     * record {@code recordId}); nothing is written when the reader is the principal (her own records).
     *
     * @return the event, or null when the read was the principal's own
     */
    public AuditChain.AuditEvent personalDataRead(Connection c, String tenantId, String principalRef, String reader, String records,
                                                  int count, String recordId) {
        if (principalRef == null || principalRef.equals(reader)) {
            return null;
        }
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("records", records);
        p.put("count", count);
        if (recordId != null) {
            p.put("recordId", recordId);
        }
        p.put("principal", principalToken(tenantId, principalRef));
        p.put("actor", reader);
        return principal(c, tenantId, principalRef, PERSONAL_DATA_READ, p);
    }

    public AuditChain.AuditEvent root(Connection c, String tenantId, String rootId, String eventType, Map<String, ?> payload) {
        return chain.append(c, tenantId, rootId, eventType, payload);
    }

    /**
     * A refusal that the caller follows with a throw: appended to the principal's {@linkplain #denialChain denial
     * chain} and committed on its own through {@code denialTx}, so it survives the caller's rollback (M-009). In-line on
     * {@code c} when no {@code denialTx} is configured.
     */
    public AuditChain.AuditEvent denial(Connection c, String tenantId, String principalRef, String eventType,
                                        Map<String, ?> payload) {
        String chainId = denialChain(tenantId, principalRef);
        if (denialTx == null) {
            return chain.append(c, tenantId, chainId, eventType, payload);
        }
        return denialTx.inTransaction(leaf -> chain.append(leaf, tenantId, chainId, eventType, payload));
    }

    public AuditChain chain() {
        return chain;
    }
}
