// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.accountability;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.lawful.ProcessingRegister;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Accountability evidence: elevated-tier designation (IN: Significant Data Fiduciary, s.10), impact assessments
 * (IN: DPIA every 12 months, r.13(1)) and periodic audit evidence whose kinds, periods, independence and accreditation
 * requirements come from the pack's {@code accountability.evidence} (IN: data audit, algorithmic due diligence,
 * CERT-In comprehensive audit, application security audit, VAPT, internal audit, access review, STQC, backup test,
 * ISO 27001). Tables {@code CMP_TIER_STATUS}, {@code CMP_IMPACT_ASSESSMENT}, {@code CMP_AUDIT_EVIDENCE}.
 *
 * <p>Records are evidence, not the assessment itself: people do the assessment; Sangam stores who, when, scope,
 * findings and the report hash, computes the next due date and shows overdue items in the evidence report.
 */
public final class AccountabilityService {

    public record ImpactAssessment(String assessmentId, String activityId, String scope, List<String> risks, List<String> measures,
                                   String residualRisk, String assessor, boolean independent, Instant assessedAt, Instant nextDueAt,
                                   String authorityReportRef) {
    }

    public record AuditEvidence(String auditId, String kind, Instant periodFrom, Instant periodTo, String auditor,
                                String accreditationRef, boolean independent, Instant completedAt, List<String> findings,
                                String reportHash, Instant nextDueAt, String authorityReportRef) {
    }

    public record ObligationStatus(String id, String description, boolean met, Instant dueAt, String detail) {
        public Map<String, Object> asMap() {
            return Sql.kv("id", id, "description", description, "met", met, "dueAt", dueAt == null ? null : dueAt.toString(), "detail", detail);
        }
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final ContactDirectory contacts;
    private final ProcessingRegister register;

    public AccountabilityService(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config, ContactDirectory contacts,
                                 ProcessingRegister register) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.contacts = contacts;
        this.register = register;
    }

    public void designate(Connection c, String tenantId, String notificationRef, Instant notifiedOn, String actor) {
        Sql.requireText(notificationRef, "notificationRef");
        Jdbc.update(c, "DELETE FROM CMP_TIER_STATUS WHERE tenant_id=?", tenantId);
        Jdbc.update(c, "INSERT INTO CMP_TIER_STATUS(tenant_id, designated, notification_ref, notified_on, recorded_by, recorded_at)"
                + " VALUES (?,1,?,?,?," + dialect.now() + ")", tenantId, notificationRef, notifiedOn, actor);
        audit.organisation(c, tenantId, ComplianceAudit.ELEVATED_TIER_DESIGNATED, Sql.kv("notification", notificationRef,
                "notifiedOn", notifiedOn.toString(), "tier", config.forTenant(tenantId).pack.elevatedTierName(), "actor", actor));
    }

    public boolean elevated(Connection c, String tenantId) {
        return config.forTenant(tenantId).elevatedTier || Jdbc.one(c, "SELECT designated FROM CMP_TIER_STATUS WHERE tenant_id=?",
                rs -> Sql.bool(rs.getInt(1)), tenantId).orElse(false);
    }

    public Optional<Instant> notifiedOn(Connection c, String tenantId) {
        return Jdbc.one(c, "SELECT notified_on FROM CMP_TIER_STATUS WHERE tenant_id=?", rs -> Jdbc.instant(rs, "notified_on"), tenantId);
    }

    public ImpactAssessment recordImpactAssessment(Connection c, String tenantId, String activityId, String scope, List<String> risks,
                                                   List<String> measures, String residualRisk, String assessor, boolean independent,
                                                   Instant assessedAt, String authorityReportRef, String actor) {
        Sql.requireText(scope, "scope");
        Sql.requireText(assessor, "assessor");
        if (risks == null || risks.isEmpty() || measures == null || measures.isEmpty()) {
            throw ComplianceException.invalid("an impact assessment records the risks and the measures");
        }
        if (activityId != null && register.list(c, tenantId).stream().noneMatch(a -> a.activityId().equals(activityId))) {
            throw ComplianceException.notFound("processing activity", activityId);
        }
        Instant next = plusMonths(assessedAt, config.forTenant(tenantId).pack.impactAssessmentMonths());
        String id = Sql.newId();
        String hash = Sql.evidenceHash(Sql.kv("scope", scope, "risks", risks, "measures", measures, "residual", residualRisk));
        Jdbc.update(c, "INSERT INTO CMP_IMPACT_ASSESSMENT(tenant_id, assessment_id, activity_id, scope, risks_json, measures_json,"
                        + " residual_risk, assessor, independent, assessed_at, next_due_at, authority_report_ref, evidence_hash)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, id, activityId, scope, Sql.jsonList(risks), Sql.jsonList(measures), residualRisk, assessor, independent,
                assessedAt, next, authorityReportRef, hash);
        audit.organisation(c, tenantId, ComplianceAudit.IMPACT_ASSESSMENT_RECORDED, Sql.kv("assessment", id, "activity", activityId,
                "assessedAt", assessedAt.toString(), "nextDueAt", next.toString(), "hash", hash, "actor", actor));
        return latestImpactAssessment(c, tenantId).orElseThrow();
    }

    public AuditEvidence recordAudit(Connection c, String tenantId, String kindCode, Instant periodFrom, Instant periodTo, String auditor,
                                     String accreditationRef, boolean independent, Instant completedAt, List<String> findings,
                                     String reportHash, String authorityReportRef, String actor) {
        Sql.requireText(auditor, "auditor");
        Sql.requireText(reportHash, "reportHash");
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        JurisdictionPack.EvidenceKind kind = pack.evidenceKind(kindCode).orElseThrow(() ->
                ComplianceException.invalid("evidence kind " + kindCode + " is not defined by jurisdiction " + pack.code()));
        if (kind.requiresIndependent() && !independent) {
            throw ComplianceException.invalid(kind.code() + " must be done by an independent auditor (" + kind.citation() + ")");
        }
        if (kind.requiresAccreditationRef() && (accreditationRef == null || accreditationRef.isBlank())) {
            throw ComplianceException.invalid(kind.code() + " needs the auditor's accreditation / empanelment reference (" + kind.citation() + ")");
        }
        Instant next = plusMonths(completedAt, kind.months());
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_AUDIT_EVIDENCE(tenant_id, audit_id, kind, period_from, period_to, auditor, accreditation_ref,"
                        + " independent, completed_at, findings_json, report_hash, next_due_at, authority_report_ref, recorded_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, id, kind.code(), periodFrom, periodTo, auditor, accreditationRef, independent, completedAt,
                Sql.jsonList(findings == null ? List.of() : findings), reportHash, next, authorityReportRef, actor);
        audit.organisation(c, tenantId, ComplianceAudit.AUDIT_EVIDENCE_RECORDED, Sql.kv("audit", id, "kind", kind.code(),
                "completedAt", completedAt.toString(), "nextDueAt", next.toString(), "reportHash", reportHash, "actor", actor));
        return latestAudit(c, tenantId, kind.code()).orElseThrow();
    }

    public Optional<ImpactAssessment> latestImpactAssessment(Connection c, String tenantId) {
        return Jdbc.query(c, "SELECT assessment_id, activity_id, scope, risks_json, measures_json, residual_risk, assessor, independent,"
                        + " assessed_at, next_due_at, authority_report_ref FROM CMP_IMPACT_ASSESSMENT WHERE tenant_id=? ORDER BY assessed_at DESC",
                rs -> new ImpactAssessment(rs.getString(1), rs.getString(2), rs.getString(3), Sql.list(rs.getString(4)),
                        Sql.list(rs.getString(5)), rs.getString(6), rs.getString(7), Sql.bool(rs.getInt(8)), Jdbc.instant(rs, "assessed_at"),
                        Jdbc.instant(rs, "next_due_at"), rs.getString(11)), tenantId).stream().findFirst();
    }

    public Optional<AuditEvidence> latestAudit(Connection c, String tenantId, String kind) {
        return Jdbc.query(c, "SELECT audit_id, kind, period_from, period_to, auditor, accreditation_ref, independent, completed_at,"
                        + " findings_json, report_hash, next_due_at, authority_report_ref FROM CMP_AUDIT_EVIDENCE WHERE tenant_id=? AND kind=?"
                        + " ORDER BY completed_at DESC",
                rs -> new AuditEvidence(rs.getString(1), rs.getString(2), Jdbc.instant(rs, "period_from"), Jdbc.instant(rs, "period_to"),
                        rs.getString(5), rs.getString(6), Sql.bool(rs.getInt(7)), Jdbc.instant(rs, "completed_at"), Sql.list(rs.getString(9)),
                        rs.getString(10), Jdbc.instant(rs, "next_due_at"), rs.getString(12)), tenantId, kind).stream().findFirst();
    }

    /** Status of contacts, the elevated-tier obligations and every periodic evidence kind of the pack. */
    public List<ObligationStatus> status(Connection c, String tenantId) {
        Instant now = Sql.now(c, dialect);
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        boolean elevated = elevated(c, tenantId);
        List<ObligationStatus> out = new ArrayList<>();
        out.add(new ObligationStatus("CONTACT:PUBLISHED", "A published contact is designated", contacts.publishedContact(c, tenantId).isPresent(),
                null, contacts.publishedContact(c, tenantId).map(Contact -> Contact.role()).orElse("missing")));
        for (JurisdictionPack.ContactRole r : pack.contactRoles()) {
            boolean requiredByAttribute = r.requiredForAttributes().stream().anyMatch(a -> config.forTenant(tenantId).has(a));
            if (r.mustBeResident() && !elevated && !requiredByAttribute) {
                continue; // resident roles in packs are elevated-tier officers
            }
            if (!r.requiredForAttributes().isEmpty() && !requiredByAttribute && !elevated) {
                continue;
            }
            Optional<ContactDirectory.Contact> ct = contacts.current(c, tenantId, r.code());
            out.add(new ObligationStatus("CONTACT:" + r.code(), r.description() + " (" + r.citation() + ")",
                    ct.isPresent() && (!r.mustBeResident() || ct.get().resident()), null, ct.isPresent() ? null : "not designated"));
        }
        if (elevated) {
            Optional<ImpactAssessment> ia = latestImpactAssessment(c, tenantId);
            Instant first = notifiedOn(c, tenantId).map(n -> plusMonths(n, pack.impactAssessmentMonths())).orElse(null);
            Instant due = ia.map(ImpactAssessment::nextDueAt).orElse(first);
            out.add(new ObligationStatus("IMPACT_ASSESSMENT", pack.elevatedTierName() + ": impact assessment every "
                    + pack.impactAssessmentMonths() + " months", due == null || now.isBefore(due), due,
                    ia.map(x -> "last " + x.assessedAt()).orElse("none recorded")));
            out.add(new ObligationStatus("IMPACT_ASSESSMENT_REPORT", pack.elevatedTierName() + ": significant observations reported to the authority",
                    ia.map(x -> x.authorityReportRef() != null && !x.authorityReportRef().isBlank()).orElse(false), null, null));
        }
        if (pack.priorConsultationRequired()) {
            Optional<ImpactAssessment> ia = latestImpactAssessment(c, tenantId);
            boolean highResidual = ia.map(x -> "HIGH".equalsIgnoreCase(x.residualRisk())).orElse(false);
            if (highResidual) {
                Optional<AuditEvidence> pc = pack.priorConsultationEvidence() == null ? Optional.empty()
                        : latestAudit(c, tenantId, pack.priorConsultationEvidence());
                boolean met = pc.isPresent() && !pc.get().completedAt().isBefore(ia.get().assessedAt());
                out.add(new ObligationStatus("PRIOR_CONSULTATION", "Prior consultation of the authority before processing with high residual"
                        + " risk (" + pack.priorConsultationCitation() + ")", met, null, met ? null : "high residual risk without consultation"));
            }
        }
        for (JurisdictionPack.EvidenceKind k : pack.evidenceKinds()) {
            if (k.elevatedTierOnly() && !elevated) {
                continue;
            }
            Optional<AuditEvidence> a = latestAudit(c, tenantId, k.code());
            Instant due = a.map(AuditEvidence::nextDueAt).orElse(null);
            out.add(new ObligationStatus("AUDIT:" + k.code(), k.description() + " every " + k.months() + " months (" + k.citation() + ")",
                    a.isPresent() && now.isBefore(due), due, a.map(x -> "last " + x.completedAt()).orElse("none recorded")));
        }
        return out;
    }

    static Instant plusMonths(Instant at, int months) {
        return at.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();
    }
}
