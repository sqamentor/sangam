// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.lawful;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Requests for information from authorised agencies (table {@code CMP_LAWFUL_REQUEST}) with requester verification,
 * maker-checker approval, a response deadline and a disclosure record per data subject. The legal bases, their
 * deadlines, rank requirements and whether a disclosure is withheld from access responses come from the pack's
 * {@code lawfulRequestBases} (IN: CERT-In s.70B(6) with the Deputy-Secretary rule, IT Rules r.3(1)(j) 72 hours,
 * DPDP r.23, IT Act s.69/69B, court orders; CMP-084, CMP-089, CMP-093, CMP-116, CMP-129).
 */
public final class LawfulRequestService {

    public enum Status { RECEIVED, VERIFIED, APPROVED, RESPONDED, REJECTED }

    public record LawfulRequest(String requestId, String authority, String officerName, String officerDesignation, String basis,
                                String reference, String scope, Status status, Instant receivedAt, Instant dueAt, String receivedBy,
                                Instant respondedAt, boolean late) {
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final DisclosureRegister disclosures;

    public LawfulRequestService(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config, DisclosureRegister disclosures) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.disclosures = disclosures;
    }

    public LawfulRequest receive(Connection c, String tenantId, String authority, String officerName, String officerDesignation,
                                 String basisCode, String reference, String scope, Instant dueAt, String receivedBy) {
        Sql.requireText(authority, "authority");
        Sql.requireText(reference, "reference");
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.LawfulRequestBasis basis = basis(cfg, basisCode);
        Instant now = Sql.now(c, dialect);
        Instant due = dueAt != null ? dueAt : now.plus(Duration.ofHours(cfg.lawfulRequestHours));
        if (basis.maxHours() > 0) {
            Instant cap = now.plus(Duration.ofHours(basis.maxHours()));
            due = due.isAfter(cap) ? cap : due;
        }
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_LAWFUL_REQUEST(tenant_id, request_id, authority, officer_name, officer_designation, legal_basis,"
                        + " reference, scope, status, received_by, received_at, due_at, late) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0)",
                tenantId, id, authority, officerName, officerDesignation, basis.code(), reference, scope, Status.RECEIVED, receivedBy, now, due);
        event(c, tenantId, id, "RECEIVED", receivedBy, Sql.kv("basis", basis.code(), "citation", basis.citation(), "dueAt", due.toString()));
        return get(c, tenantId, id);
    }

    /** Verifies the requester; where the basis has a rank requirement the verifier attests it. */
    public void verify(Connection c, String tenantId, String requestId, boolean rankAdequate, String verifiedBy) {
        LawfulRequest r = require(c, tenantId, requestId, Status.RECEIVED);
        JurisdictionPack.LawfulRequestBasis basis = basis(config.forTenant(tenantId), r.basis());
        if (basis.requiresRankAttestation() && !rankAdequate) {
            reject(c, tenantId, requestId, "requester rank requirement not met: " + basis.rankRequirement(), verifiedBy);
            return;
        }
        Jdbc.update(c, "UPDATE CMP_LAWFUL_REQUEST SET status=?, verified_by=?, verified_at=" + dialect.now() + ", rank_ok=?"
                + " WHERE tenant_id=? AND request_id=?", Status.VERIFIED, verifiedBy, rankAdequate, tenantId, requestId);
        event(c, tenantId, requestId, "VERIFIED", verifiedBy, Sql.kv("rankAdequate", rankAdequate));
    }

    public void approve(Connection c, String tenantId, String requestId, String approver) {
        LawfulRequest r = require(c, tenantId, requestId, Status.VERIFIED);
        if (approver == null || approver.equals(r.receivedBy())) {
            throw new ComplianceException(Code.FOUR_EYES_REQUIRED, "the approver must differ from the receiver");
        }
        Jdbc.update(c, "UPDATE CMP_LAWFUL_REQUEST SET status=?, approved_by=?, approved_at=" + dialect.now()
                + " WHERE tenant_id=? AND request_id=?", Status.APPROVED, approver, tenantId, requestId);
        event(c, tenantId, requestId, "APPROVED", approver, Sql.kv());
    }

    public void respond(Connection c, String tenantId, String requestId, String responseRef, List<String> principalRefs,
                        Set<String> dataCategories, String responder) {
        LawfulRequest r = require(c, tenantId, requestId, Status.APPROVED);
        JurisdictionPack.LawfulRequestBasis basis = basis(config.forTenant(tenantId), r.basis());
        Instant now = Sql.now(c, dialect);
        boolean late = now.isAfter(r.dueAt());
        Jdbc.update(c, "UPDATE CMP_LAWFUL_REQUEST SET status=?, responded_by=?, responded_at=?, response_ref=?, late=?"
                + " WHERE tenant_id=? AND request_id=?", Status.RESPONDED, responder, now, responseRef, late, tenantId, requestId);
        DisclosureRegister.RecipientKind kind;
        try {
            kind = DisclosureRegister.RecipientKind.valueOf(basis.recipientKind());
        } catch (IllegalArgumentException e) {
            kind = DisclosureRegister.RecipientKind.STATE_AGENCY;
        }
        if (principalRefs != null) {
            for (String p : principalRefs) {
                disclosures.record(c, tenantId, p, r.authority(), kind, null, dataCategories, basis.code() + ":" + r.reference(),
                        basis.withheldFromAccess());
            }
        }
        event(c, tenantId, requestId, "RESPONDED", responder, Sql.kv("late", late, "subjects", principalRefs == null ? 0 : principalRefs.size()));
    }

    public void reject(Connection c, String tenantId, String requestId, String reason, String actor) {
        Jdbc.update(c, "UPDATE CMP_LAWFUL_REQUEST SET status=?, rejection_reason=? WHERE tenant_id=? AND request_id=?",
                Status.REJECTED, reason, tenantId, requestId);
        event(c, tenantId, requestId, "REJECTED", actor, Sql.kv("reason", reason));
    }

    public List<LawfulRequest> overdue(Connection c, String tenantId) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND status IN ('RECEIVED','VERIFIED','APPROVED') AND due_at<"
                + dialect.now() + " ORDER BY due_at", LawfulRequestService::map, tenantId);
    }

    public List<LawfulRequest> between(Connection c, String tenantId, Instant from, Instant to) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND received_at>=? AND received_at<? ORDER BY received_at",
                LawfulRequestService::map, tenantId, from, to);
    }

    public LawfulRequest get(Connection c, String tenantId, String requestId) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND request_id=?", LawfulRequestService::map, tenantId, requestId)
                .orElseThrow(() -> ComplianceException.notFound("lawful request", requestId));
    }

    private static JurisdictionPack.LawfulRequestBasis basis(ComplianceConfig cfg, String code) {
        return cfg.pack.lawfulRequestBasis(code).orElseThrow(() -> ComplianceException.invalid("legal basis " + code
                + " is not defined by jurisdiction " + cfg.pack.code()));
    }

    private LawfulRequest require(Connection c, String tenantId, String requestId, Status expected) {
        LawfulRequest r = get(c, tenantId, requestId);
        if (r.status() != expected) {
            throw new ComplianceException(Code.INVALID_STATE, "lawful request is " + r.status() + ", expected " + expected);
        }
        return r;
    }

    private void event(Connection c, String tenantId, String requestId, String step, String actor, Map<String, Object> extra) {
        Map<String, Object> ev = new LinkedHashMap<>(extra);
        ev.put("request", requestId);
        ev.put("step", step);
        ev.put("actor", actor);
        audit.organisation(c, tenantId, ComplianceAudit.LAWFUL_REQUEST_CHANGED, ev);
    }

    private static final String SELECT = "SELECT request_id, authority, officer_name, officer_designation, legal_basis, reference,"
            + " scope, status, received_at, due_at, received_by, responded_at, late FROM CMP_LAWFUL_REQUEST";

    private static LawfulRequest map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LawfulRequest(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), Status.valueOf(rs.getString(8)), Jdbc.instant(rs, "received_at"), Jdbc.instant(rs, "due_at"),
                rs.getString(11), Jdbc.instant(rs, "responded_at"), Sql.bool(rs.getInt(13)));
    }
}
