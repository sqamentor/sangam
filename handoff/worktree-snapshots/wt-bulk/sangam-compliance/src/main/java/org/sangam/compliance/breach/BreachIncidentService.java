// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.breach;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.NoticeTemplate;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionPack.Language;
import org.sangam.compliance.ports.CompliancePorts.AuthoritySubmission;
import org.sangam.compliance.ports.CompliancePorts.LegalHoldPort;
import org.sangam.compliance.ports.CompliancePorts.PrincipalDirectory;
import org.sangam.compliance.ports.CompliancePorts.PrincipalMessage;
import org.sangam.compliance.ports.CompliancePorts.PrincipalNotifier;
import org.sangam.compliance.ports.CompliancePorts.SupervisoryAuthorityGateway;
import org.sangam.compliance.ports.CompliancePorts.WorkflowStarter;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Personal-data breach handling, driven by the pack's {@code breach} rules: the supervisory authority, the items of
 * the initial notification, the deadline and items of the detailed report, the items of the notice to affected data
 * subjects, and the linked incident report (IN: DPDP s.8(6), r.7 — Board "without delay", detailed report within 72
 * hours, five items to each principal; linked CERT-In Annexure I "xi Data Breach" with its own 6-hour clock;
 * CMP-024, CMP-061 … CMP-063). Tables {@code CMP_BREACH}, {@code CMP_BREACH_NOTICE}, {@code CMP_BREACH_ROOT}.
 *
 * <p>Registration places a legal hold on affected workflow roots through the integration {@code LegalHoldStore}
 * (evidence preservation; the retention scheduler will not erase them) and starts {@code sangam-breach-incident}.
 * Every submission is refused while a required pack item is missing. Item values are stored as JSON maps.
 */
public final class BreachIncidentService {

    public enum Status { REGISTERED, AUTHORITY_NOTIFIED, REPORTED, CLOSED }

    public record Breach(String breachId, Status status, Instant detectedAt, Instant awareAt, Instant reportDueAt, Instant extensionUntil,
                         Instant authorityNotifiedAt, Instant reportedAt, boolean reportLate, long subjectsAffected, long subjectsNotified,
                         List<String> incidentIds) {
        public Instant effectiveDeadline() {
            if (reportDueAt == null) {
                return null;
            }
            return extensionUntil != null && extensionUntil.isAfter(reportDueAt) ? extensionUntil : reportDueAt;
        }
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final SupervisoryAuthorityGateway authority;
    private final PrincipalNotifier notifier;
    private final PrincipalDirectory principals;
    private final LegalHoldPort holds;
    private final IncidentReportingService incidents;
    private final ContactDirectory contacts;
    private final NoticeTemplate.Catalogues templates;
    private final WorkflowStarter workflows;

    public BreachIncidentService(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config, SupervisoryAuthorityGateway authority,
                                 PrincipalNotifier notifier, PrincipalDirectory principals, LegalHoldPort holds, IncidentReportingService incidents,
                                 ContactDirectory contacts, NoticeTemplate.Catalogues templates, WorkflowStarter workflows) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.authority = authority;
        this.notifier = notifier == null ? PrincipalNotifier.NONE : notifier;
        this.principals = principals == null ? PrincipalDirectory.NONE : principals;
        this.holds = holds;
        this.incidents = incidents;
        this.contacts = contacts;
        this.templates = templates;
        this.workflows = workflows == null ? WorkflowStarter.NONE : workflows;
    }

    /**
     * @param facts        values for the pack's initial items (may be incomplete at registration)
     * @param incidentType incident catalogue code for the linked incident report; defaults to the pack's linked type
     */
    public Breach register(Connection c, String tenantId, Map<String, String> facts, Instant detectedAt, Instant awareAt,
                           Set<String> affectedSubjects, Set<String> affectedRoots, String incidentType, List<String> affectedSystems,
                           String actor) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.BreachRules rules = cfg.pack.breach();
        Instant now = Sql.now(c, dialect);
        Instant aware = awareAt == null ? now : awareAt;
        Instant due = cfg.breachReportHours > 0 ? aware.plus(Duration.ofHours(cfg.breachReportHours)) : null;
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_BREACH(tenant_id, breach_id, detected_at, aware_at, facts_json, status, authority_notified_at,"
                        + " authority_ref, report_due_at, extension_until, extension_ref, reported_at, report_ref, report_late,"
                        + " subjects_affected, subjects_notified, incident_ids_json, registered_by, registered_at, closed_at)"
                        + " VALUES (?,?,?,?,?,?,NULL,NULL,?,NULL,NULL,NULL,NULL,0,0,0,'[]',?,?,NULL)",
                tenantId, id, detectedAt == null ? aware : detectedAt, aware, Sql.json(facts == null ? Map.of() : facts), Status.REGISTERED,
                due, actor, now);
        if (affectedSubjects != null) {
            addAffected(c, tenantId, id, affectedSubjects);
        }
        if (affectedRoots != null) {
            for (String root : affectedRoots) {
                Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CMP_BREACH_ROOT(tenant_id, breach_id, root_id) VALUES (?,?,?)", tenantId, id, root);
                holds.place(c, tenantId, root, "breach:" + id, "personal data breach " + id + ": evidence preservation", actor);
            }
        }
        String type = incidentType != null ? incidentType : rules.linkedIncidentType();
        List<String> incidentIds = new ArrayList<>();
        if (type != null) {
            String description = facts == null ? null : facts.get("description");
            if (rules.linkedIncidentAuthority() != null) {
                incidentIds.add(incidents.register(c, tenantId, rules.linkedIncidentAuthority(), type, detectedAt, aware, affectedSystems,
                        Map.of(), description, id, actor).incidentId());
            } else {
                incidents.registerAll(c, tenantId, type, detectedAt, aware, affectedSystems, Map.of(), description, id, actor)
                        .forEach(i -> incidentIds.add(i.incidentId()));
            }
            Jdbc.update(c, "UPDATE CMP_BREACH SET incident_ids_json=? WHERE tenant_id=? AND breach_id=?", Sql.jsonList(incidentIds), tenantId, id);
        }
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_REGISTERED, Sql.kv("breach", id, "awareAt", aware.toString(),
                "reportDueAt", due == null ? null : due.toString(), "incidents", incidentIds,
                "roots", affectedRoots == null ? 0 : affectedRoots.size(), "actor", actor));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("sangamTenantId", tenantId);
        vars.put("sangamBusinessKey", id);
        vars.put("breachId", id);
        Instant d = due == null ? aware.plus(Duration.ofDays(30)) : due;
        vars.put("reportDueAt", d.toString());
        vars.put("reportWarnAt", aware.plus(Duration.between(aware, d).multipliedBy(2).dividedBy(3)).toString());
        String pi = workflows.start(c, tenantId, "sangam-breach-incident", id, vars);
        if (pi != null) {
            Jdbc.update(c, "UPDATE CMP_BREACH SET process_instance_id=? WHERE tenant_id=? AND breach_id=?", pi, tenantId, id);
        }
        return get(c, tenantId, id);
    }

    public int addAffected(Connection c, String tenantId, String breachId, Set<String> subjectRefs) {
        int n = 0;
        for (String p : subjectRefs) {
            if (Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CMP_BREACH_NOTICE(tenant_id, breach_id, principal_ref, delivery_ref, sent_at)"
                    + " VALUES (?,?,?,NULL,NULL)", tenantId, breachId, p)) {
                n++;
            }
        }
        Jdbc.update(c, "UPDATE CMP_BREACH SET subjects_affected=(SELECT COUNT(*) FROM CMP_BREACH_NOTICE n WHERE n.tenant_id=? AND"
                + " n.breach_id=?) WHERE tenant_id=? AND breach_id=?", tenantId, breachId, tenantId, breachId);
        return n;
    }

    public void updateFacts(Connection c, String tenantId, String breachId, Map<String, String> facts, String actor) {
        Map<String, Object> merged = facts(c, tenantId, breachId);
        merged.putAll(facts);
        Jdbc.update(c, "UPDATE CMP_BREACH SET facts_json=? WHERE tenant_id=? AND breach_id=?", Sql.json(merged), tenantId, breachId);
    }

    /** Initial notification to the supervisory authority with the pack's initial items. */
    public String notifyAuthority(Connection c, String tenantId, String breachId, String actor) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.BreachRules rules = cfg.pack.breach();
        Breach b = get(c, tenantId, breachId);
        if (b.authorityNotifiedAt() != null) {
            throw new ComplianceException(Code.INVALID_STATE, "the authority was already notified");
        }
        Map<String, Object> facts = facts(c, tenantId, breachId);
        Map<String, Object> content = require(rules.initialItems(), facts, "initial notification");
        content.put("controller", cfg.organisationName);
        content.put("awareAt", b.awareAt().toString());
        content.put("contact", contacts.publishedContact(c, tenantId).map(ContactDirectory.Contact::asMap).orElse(Map.of()));
        String ref = gateway().submit(new AuthoritySubmission(tenantId, rules.authority(), breachId, "INITIAL_NOTIFICATION", content));
        Instant now = Sql.now(c, dialect);
        Jdbc.update(c, "UPDATE CMP_BREACH SET status=?, authority_notified_at=?, authority_ref=? WHERE tenant_id=? AND breach_id=?",
                Status.AUTHORITY_NOTIFIED, now, ref, tenantId, breachId);
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_AUTHORITY_NOTIFIED, Sql.kv("breach", breachId, "authority", rules.authority(),
                "ref", ref, "hoursAfterAwareness", Duration.between(b.awareAt(), now).toHours(), "contentHash", Sql.evidenceHash(content), "actor", actor));
        return ref;
    }

    /** Notice to each affected data subject not yet notified, with the pack's subject items. */
    public int notifySubjects(Connection c, String tenantId, String breachId, Map<String, String> text, String actor) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        Map<String, Object> items = require(cfg.pack.breach().subjectItems(), new LinkedHashMap<>(text), "notice to data subjects");
        NoticeTemplate.Catalogue cat = templates.of(cfg.pack);
        List<String> pending = Jdbc.query(c, "SELECT principal_ref FROM CMP_BREACH_NOTICE WHERE tenant_id=? AND breach_id=? AND sent_at IS NULL"
                + " ORDER BY principal_ref", rs -> rs.getString(1), tenantId, breachId);
        StringBuilder body = new StringBuilder();
        items.values().forEach(v -> body.append(v).append('\n'));
        int n = 0;
        for (String p : pending) {
            Language lang = principals.preferredLanguage(c, tenantId, p).orElse(cfg.defaultLanguage);
            NoticeTemplate t = cat.get(lang.code()).or(() -> cat.get(cfg.defaultLanguage.code())).orElseThrow();
            String ref = notifier.send(c, new PrincipalMessage(tenantId, p, "BREACH_NOTICE", t.language(),
                    t.label("breach.subject", cfg.organisationName), body.toString(), Map.of("breachId", breachId)));
            Jdbc.update(c, "UPDATE CMP_BREACH_NOTICE SET delivery_ref=?, sent_at=" + dialect.now() + " WHERE tenant_id=? AND breach_id=?"
                    + " AND principal_ref=?", ref, tenantId, breachId, p);
            n++;
        }
        Jdbc.update(c, "UPDATE CMP_BREACH SET subjects_notified=(SELECT COUNT(*) FROM CMP_BREACH_NOTICE n WHERE n.tenant_id=? AND"
                + " n.breach_id=? AND n.sent_at IS NOT NULL) WHERE tenant_id=? AND breach_id=?", tenantId, breachId, tenantId, breachId);
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_PRINCIPALS_NOTIFIED, Sql.kv("breach", breachId, "sent", n,
                "textHash", Sql.evidenceHash(items), "actor", actor));
        return n;
    }

    /** Detailed report with the pack's detailed items, plus the generated report on notices to data subjects. */
    public String submitDetailedReport(Connection c, String tenantId, String breachId, Map<String, String> report, String actor) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.BreachRules rules = cfg.pack.breach();
        Breach b = get(c, tenantId, breachId);
        if (b.authorityNotifiedAt() == null) {
            throw new ComplianceException(Code.INVALID_STATE, "notify the authority first");
        }
        if (b.reportedAt() != null) {
            throw new ComplianceException(Code.INVALID_STATE, "the detailed report was already submitted");
        }
        Map<String, Object> content = require(rules.detailedItems(), new LinkedHashMap<>(report), "detailed report");
        content.put("noticesToDataSubjects", Sql.kv("affected", b.subjectsAffected(), "notified", b.subjectsNotified()));
        content.put("incidents", b.incidentIds());
        String ref = gateway().submit(new AuthoritySubmission(tenantId, rules.authority(), breachId, "DETAILED_REPORT", content));
        Instant now = Sql.now(c, dialect);
        boolean late = b.effectiveDeadline() != null && now.isAfter(b.effectiveDeadline());
        Jdbc.update(c, "UPDATE CMP_BREACH SET status=?, reported_at=?, report_ref=?, report_late=? WHERE tenant_id=? AND breach_id=?",
                Status.REPORTED, now, ref, late, tenantId, breachId);
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_REPORT_SUBMITTED, Sql.kv("breach", breachId, "ref", ref, "late", late,
                "contentHash", Sql.evidenceHash(content), "actor", actor));
        return ref;
    }

    public void recordExtension(Connection c, String tenantId, String breachId, Instant until, String authorityOrderRef, String actor) {
        Sql.requireText(authorityOrderRef, "authorityOrderRef");
        Jdbc.update(c, "UPDATE CMP_BREACH SET extension_until=?, extension_ref=? WHERE tenant_id=? AND breach_id=?", until, authorityOrderRef, tenantId, breachId);
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_EXTENSION, Sql.kv("breach", breachId, "until", until.toString(),
                "order", authorityOrderRef, "actor", actor));
    }

    public void close(Connection c, String tenantId, String breachId, String actor) {
        Breach b = get(c, tenantId, breachId);
        if (b.reportedAt() == null && config.forTenant(tenantId).pack.breach().detailedReportHours() > 0) {
            throw new ComplianceException(Code.INVALID_STATE, "the detailed report to the authority is outstanding");
        }
        for (String root : Jdbc.query(c, "SELECT root_id FROM CMP_BREACH_ROOT WHERE tenant_id=? AND breach_id=?", rs -> rs.getString(1), tenantId, breachId)) {
            holds.release(c, tenantId, root, "breach:" + breachId, actor);
        }
        Jdbc.update(c, "UPDATE CMP_BREACH SET status=?, closed_at=" + dialect.now() + " WHERE tenant_id=? AND breach_id=?", Status.CLOSED, tenantId, breachId);
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_CLOSED, Sql.kv("breach", breachId, "actor", actor));
    }

    public List<Breach> overdue(Connection c, String tenantId) {
        Instant now = Sql.now(c, dialect);
        List<Breach> out = new ArrayList<>();
        for (Breach b : Jdbc.query(c, SELECT + " WHERE tenant_id=? AND reported_at IS NULL", BreachIncidentService::map, tenantId)) {
            if (b.effectiveDeadline() != null && now.isAfter(b.effectiveDeadline())) {
                out.add(b);
                if (Jdbc.update(c, "UPDATE CMP_BREACH SET report_late=1 WHERE tenant_id=? AND breach_id=? AND report_late=0", tenantId, b.breachId()) == 1) {
                    audit.organisation(c, tenantId, ComplianceAudit.BREACH_OVERDUE, Sql.kv("breach", b.breachId(), "deadline", b.effectiveDeadline().toString()));
                }
            }
        }
        return out;
    }

    public List<Breach> between(Connection c, String tenantId, Instant from, Instant to) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND aware_at>=? AND aware_at<? ORDER BY aware_at", BreachIncidentService::map, tenantId, from, to);
    }

    public Breach get(Connection c, String tenantId, String breachId) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND breach_id=?", BreachIncidentService::map, tenantId, breachId)
                .orElseThrow(() -> ComplianceException.notFound("breach", breachId));
    }

    private Map<String, Object> facts(Connection c, String tenantId, String breachId) {
        return Jdbc.one(c, "SELECT facts_json FROM CMP_BREACH WHERE tenant_id=? AND breach_id=?", rs -> Sql.map(rs.getString(1)), tenantId, breachId)
                .orElseThrow(() -> ComplianceException.notFound("breach", breachId));
    }

    /** Picks the pack items from {@code values}; refuses when one is missing. */
    private static Map<String, Object> require(List<JurisdictionPack.ReportItem> items, Map<String, ?> values, String what) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (JurisdictionPack.ReportItem i : items) {
            Object v = values.get(i.key());
            if (v == null || String.valueOf(v).isBlank()) {
                missing.add(i.label() + (i.citation().isBlank() ? "" : " (" + i.citation() + ")"));
            } else {
                out.put(i.key(), v);
            }
        }
        if (!missing.isEmpty()) {
            throw new ComplianceException(Code.REPORT_INCOMPLETE, what + " lacks " + missing);
        }
        return out;
    }

    private SupervisoryAuthorityGateway gateway() {
        if (authority == null) {
            throw new IllegalStateException("SupervisoryAuthorityGateway is not configured");
        }
        return authority;
    }

    private static final String SELECT = "SELECT breach_id, status, detected_at, aware_at, report_due_at, extension_until, authority_notified_at,"
            + " reported_at, report_late, subjects_affected, subjects_notified, incident_ids_json FROM CMP_BREACH";

    private static Breach map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Breach(rs.getString(1), Status.valueOf(rs.getString(2)), Jdbc.instant(rs, "detected_at"), Jdbc.instant(rs, "aware_at"),
                Jdbc.instant(rs, "report_due_at"), Jdbc.instant(rs, "extension_until"), Jdbc.instant(rs, "authority_notified_at"),
                Jdbc.instant(rs, "reported_at"), Sql.bool(rs.getInt(9)), rs.getLong(10), rs.getLong(11), Sql.list(rs.getString(12)));
    }
}
