// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.breach;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.ports.CompliancePorts.IncidentAuthorityGateway;
import org.sangam.compliance.ports.CompliancePorts.IncidentSubmission;
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

/**
 * Cyber-incident reporting to an incident authority of the pack (role {@code INCIDENT}): incident-type catalogue,
 * deadline and warning hours, recipients and form items are pack data (IN: CERT-In Directions 28-04-2022 para (ii),
 * Annexure I, 6 hours, FAQ Q30 follow-ups, state CERTs; CMP-087, CMP-088, CMP-090, CMP-108, CMP-148, CMP-149).
 * Table {@code CMP_INCIDENT}. The BPMN pack process {@code sangam-incident-report} carries the warning and deadline
 * timers. The form carries every pack form item, the contact role named in the authority's form items, the time zone
 * and the log/time-source statements; unknown items are reported as such and completed by {@link #followUp}.
 */
public final class IncidentReportingService {

    public enum Status { OPEN, REPORTED, CLOSED }

    public record Incident(String incidentId, String authority, String type, Instant occurredAt, Instant noticedAt, Instant dueAt,
                           Status status, Instant reportedAt, boolean late, String breachId, int followUps) {
    }

    @FunctionalInterface
    public interface EvidenceContext {
        Map<String, Object> describe(Connection c, String tenantId);
    }

    /** M-010c: the compliance roles whose seats {@code sangam-incident-report} routes its user tasks to. */
    public static final List<String> SEAT_ROLES = List.of("CISO", "CISO-HEAD");

    /** Contact role the pack uses for the authority's point of contact: {@code <AUTHORITY>_POC} with dashes removed. */
    public static String pocRole(String authorityCode) {
        return authorityCode.replace("-", "_") + "_POC";
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final ContactDirectory contacts;
    private final IncidentAuthorityGateway gateway;
    private final WorkflowStarter workflows;
    private volatile EvidenceContext evidence = (c, t) -> Map.of();

    public IncidentReportingService(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config, ContactDirectory contacts,
                                    IncidentAuthorityGateway gateway, WorkflowStarter workflows) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.contacts = contacts;
        this.gateway = gateway;
        this.workflows = workflows == null ? WorkflowStarter.NONE : workflows;
    }

    public void evidenceContext(EvidenceContext context) {
        this.evidence = context;
    }

    /**
     * M-010c: the seat variables {@code sangam-incident-report} routes its user tasks to, from the tenant's
     * {@code sangam.compliance.seats.<ROLE>}; refused with {@code INVALID_STATE} when one is missing. Empty without a
     * workflow starter (no process is started, so no task is routed). Callers check it before they write anything.
     */
    public Map<String, Object> requireSeats(ComplianceConfig cfg) {
        return workflows == WorkflowStarter.NONE ? Map.of() : cfg.seatVariables(SEAT_ROLES);
    }

    /** Registers an incident for every pack authority of role INCIDENT whose catalogue has the type; returns them. */
    public List<Incident> registerAll(Connection c, String tenantId, String type, Instant occurredAt, Instant noticedAt,
                                      List<String> affectedSystems, Map<String, Object> indicators, String description, String breachId,
                                      String actor) {
        requireSeats(config.forTenant(tenantId)); // before the first incident row (M-010c)
        List<Incident> out = new ArrayList<>();
        for (JurisdictionPack.Authority a : config.forTenant(tenantId).pack.authoritiesWithRole("INCIDENT")) {
            if (a.incidentType(type).isPresent()) {
                out.add(register(c, tenantId, a.code(), type, occurredAt, noticedAt, affectedSystems, indicators, description, breachId, actor));
            }
        }
        return out;
    }

    public Incident register(Connection c, String tenantId, String authorityCode, String type, Instant occurredAt, Instant noticedAt,
                             List<String> affectedSystems, Map<String, Object> indicators, String description, String breachId, String actor) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.Authority a = authority(cfg, authorityCode);
        if (a.incidentType(type).isEmpty()) {
            throw ComplianceException.invalid("incident type " + type + " is not in the catalogue of " + a.code());
        }
        Instant now = Sql.now(c, dialect);
        Instant noticed = noticedAt == null ? now : noticedAt;
        Instant due = a.deadlineHours() > 0 ? noticed.plus(Duration.ofHours(a.deadlineHours())) : null;
        Instant warn = a.warnHours() > 0 ? noticed.plus(Duration.ofHours(a.warnHours())) : due;
        Map<String, Object> seats = requireSeats(cfg);
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_INCIDENT(tenant_id, incident_id, authority, incident_type, occurred_at, noticed_at, due_at, warn_at,"
                        + " status, reported_at, late, systems_json, description, indicators_json, actions_taken, breach_id, report_refs_json,"
                        + " follow_ups, registered_by, registered_at) VALUES (?,?,?,?,?,?,?,?,?,NULL,0,?,?,?,NULL,?,'[]',0,?,?)",
                tenantId, id, a.code(), type, occurredAt, noticed, due, warn, Status.OPEN, Sql.jsonList(affectedSystems), description,
                Sql.json(indicators == null ? Map.of() : indicators), breachId, actor, now);
        // decision 299, OJ-06 steps 1 and 2: the incident is opened with noticed_at fixed, and classified against the
        // authority's catalogue (checked above); a breach link means personal data is involved
        JurisdictionPack.IncidentType classified = a.incidentType(type).orElseThrow();
        audit.organisation(c, tenantId, ComplianceAudit.SEC_INCIDENT_OPENED, Sql.kv("incident", id, "authority", a.code(),
                "noticedAt", noticed.toString(), "dueAt", due == null ? null : due.toString(), "breach", breachId, "actor", actor));
        audit.organisation(c, tenantId, ComplianceAudit.SEC_INCIDENT_CLASSIFIED, Sql.kv("incident", id, "authority", a.code(),
                "type", classified.code(), "label", classified.label(), "personalDataLikely", classified.personalDataLikely(),
                "personalData", breachId != null, "breach", breachId, "actor", actor));
        audit.organisation(c, tenantId, ComplianceAudit.INCIDENT_REGISTERED, Sql.kv("incident", id, "authority", a.code(), "type", type,
                "noticedAt", noticed.toString(), "dueAt", due == null ? null : due.toString(), "breach", breachId, "actor", actor));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("sangamTenantId", tenantId);
        vars.put("sangamBusinessKey", id);
        vars.put("incidentId", id);
        vars.put("incidentDueAt", (due == null ? now.plus(Duration.ofDays(1)) : due).toString());
        vars.put("incidentWarnAt", (warn == null ? now.plus(Duration.ofHours(12)) : warn).toString());
        vars.putAll(seats);
        String pi = workflows.start(c, tenantId, "sangam-incident-report", id, vars);
        if (pi != null) {
            Jdbc.update(c, "UPDATE CMP_INCIDENT SET process_instance_id=? WHERE tenant_id=? AND incident_id=?", pi, tenantId, id);
        }
        return get(c, tenantId, id);
    }

    public Map<String, Object> buildForm(Connection c, String tenantId, String incidentId) {
        Incident i = get(c, tenantId, incidentId);
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.Authority a = authority(cfg, i.authority());
        Map<String, Object> row = Jdbc.one(c, "SELECT systems_json, description, indicators_json, actions_taken FROM CMP_INCIDENT"
                        + " WHERE tenant_id=? AND incident_id=?",
                rs -> Sql.kv("affectedSystems", Sql.list(rs.getString(1)), "description", rs.getString(2),
                        "indicators", Sql.map(rs.getString(3)), "actionsTaken", rs.getString(4)), tenantId, incidentId).orElseThrow();
        Map<String, Object> values = new LinkedHashMap<>(row);
        values.put("organisation", cfg.organisationName);
        values.put("sector", cfg.sector);
        values.put("pointOfContact", contacts.current(c, tenantId, pocRole(a.code())).map(ContactDirectory.Contact::asMap).orElse(null));
        values.put("incidentType", Sql.kv("code", i.type(), "label", a.incidentType(i.type()).map(JurisdictionPack.IncidentType::label).orElse("")));
        values.put("occurredAt", i.occurredAt() == null ? "unknown" : i.occurredAt().toString());
        values.put("noticedAt", i.noticedAt().toString());
        values.put("timeZone", "UTC (ISO-8601 Z); local zone " + cfg.pack.timeZone().getId());
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("authority", a.name());
        for (JurisdictionPack.ReportItem item : a.formItems()) {
            Object v = values.get(item.key());
            f.put(item.key(), v == null || String.valueOf(v).isBlank() ? "not yet known" : v);
        }
        f.put("logs", evidence.describe(c, tenantId));
        f.put("reportDueAt", i.dueAt() == null ? null : i.dueAt().toString());
        f.put("followUps", i.followUps());
        return f;
    }

    public List<String> submit(Connection c, String tenantId, String incidentId, String actionsTaken, String actor) {
        Incident i = get(c, tenantId, incidentId);
        if (i.status() != Status.OPEN) {
            throw new ComplianceException(Code.INVALID_STATE, "incident already " + i.status());
        }
        if (gateway == null) {
            throw new IllegalStateException("IncidentAuthorityGateway is not configured");
        }
        if (actionsTaken != null) {
            Jdbc.update(c, "UPDATE CMP_INCIDENT SET actions_taken=? WHERE tenant_id=? AND incident_id=?", actionsTaken, tenantId, incidentId);
        }
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.Authority a = authority(cfg, i.authority());
        boolean needsPoc = a.formItems().stream().anyMatch(x -> x.key().equals("pointOfContact"));
        if (needsPoc && contacts.current(c, tenantId, pocRole(a.code())).isEmpty()) {
            throw new ComplianceException(Code.REPORT_INCOMPLETE, "designate the " + a.code() + " point of contact first (role " + pocRole(a.code()) + ")");
        }
        Map<String, Object> form = buildForm(c, tenantId, incidentId);
        List<String> refs = new ArrayList<>();
        for (String r : recipients(cfg, a)) {
            refs.add(r + "=" + gateway.submit(new IncidentSubmission(a.code(), tenantId, incidentId, r, false, form)));
        }
        Instant now = Sql.now(c, dialect);
        boolean late = i.dueAt() != null && now.isAfter(i.dueAt());
        Jdbc.update(c, "UPDATE CMP_INCIDENT SET status=?, reported_at=?, late=?, report_refs_json=? WHERE tenant_id=? AND incident_id=?",
                Status.REPORTED, now, late, Sql.jsonList(refs), tenantId, incidentId);
        // decision 299, OJ-06 step 6: submission time and references recorded
        audit.organisation(c, tenantId, ComplianceAudit.SEC_REPORT_SUBMITTED, Sql.kv("incident", incidentId, "authority", a.code(),
                "references", refs, "submittedAt", now.toString(), "late", late, "actor", actor));
        audit.organisation(c, tenantId, ComplianceAudit.INCIDENT_REPORTED, Sql.kv("incident", incidentId, "authority", a.code(), "late", late,
                "recipients", recipients(cfg, a), "formHash", Sql.evidenceHash(form), "actor", actor));
        return refs;
    }

    public void followUp(Connection c, String tenantId, String incidentId, Map<String, Object> details, String actor) {
        Incident i = get(c, tenantId, incidentId);
        if (i.status() != Status.REPORTED) {
            throw new ComplianceException(Code.INVALID_STATE, "a follow-up needs a reported incident");
        }
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.Authority a = authority(cfg, i.authority());
        Map<String, Object> form = buildForm(c, tenantId, incidentId);
        form.put("followUp", details == null ? Map.of() : details);
        for (String r : recipients(cfg, a)) {
            gateway.submit(new IncidentSubmission(a.code(), tenantId, incidentId, r, true, form));
        }
        Jdbc.update(c, "UPDATE CMP_INCIDENT SET follow_ups=follow_ups+1 WHERE tenant_id=? AND incident_id=?", tenantId, incidentId);
        audit.organisation(c, tenantId, ComplianceAudit.INCIDENT_FOLLOW_UP, Sql.kv("incident", incidentId, "formHash", Sql.evidenceHash(form), "actor", actor));
    }

    /**
     * Closes a reported incident (SANGAM.md OJ-06 step 8: closure requires the root cause and the lessons learned). Both
     * are required non-blank text; without them the close is refused with {@code INCIDENT_CLOSURE_INCOMPLETE} (422)
     * before anything is written. They are stored on the incident ({@code root_cause}, {@code lessons_learned},
     * {@code closed_at}, {@code closed_by}) and carried by {@code SEC_INCIDENT_CLOSED} (decision 299), written in the
     * same transaction only when this call changed the incident from REPORTED to CLOSED (a repeated or early close
     * changes nothing and writes nothing).
     */
    public void close(Connection c, String tenantId, String incidentId, String rootCause, String lessonsLearned, String actor) {
        List<String> missing = missingClosureFields(rootCause, lessonsLearned);
        if (!missing.isEmpty()) {
            throw new ComplianceException(Code.INCIDENT_CLOSURE_INCOMPLETE, closureRefusal(incidentId, missing));
        }
        Instant now = Sql.now(c, dialect);
        if (Jdbc.update(c, "UPDATE CMP_INCIDENT SET status=?, root_cause=?, lessons_learned=?, closed_at=?, closed_by=? WHERE tenant_id=?"
                + " AND incident_id=? AND status=?", Status.CLOSED, rootCause, lessonsLearned, now, actor, tenantId, incidentId, Status.REPORTED) == 1) {
            audit.organisation(c, tenantId, ComplianceAudit.SEC_INCIDENT_CLOSED, Sql.kv("incident", incidentId, "rootCause", rootCause,
                    "lessonsLearned", lessonsLearned, "closedAt", now.toString(), "actor", actor));
        }
    }

    /** Request field names of the closure rule (OJ-06 step 8), as named in the contract (W4-M ACK 2026-11-13T07:51:04Z). */
    public static final String ROOT_CAUSE = "rootCause";
    public static final String LESSONS_LEARNED = "lessonsLearned";

    /**
     * The one closure rule (OJ-06 step 8), shared by {@link #close} and the follow-up task guard
     * ({@code org.sangam.compliance.bpmn.IncidentClosureGuardListener}): the names of the missing or blank fields, in
     * order; empty when the incident may be closed.
     */
    public static List<String> missingClosureFields(Object rootCause, Object lessonsLearned) {
        List<String> missing = new ArrayList<>();
        if (rootCause == null || rootCause.toString().isBlank()) {
            missing.add(ROOT_CAUSE);
        }
        if (lessonsLearned == null || lessonsLearned.toString().isBlank()) {
            missing.add(LESSONS_LEARNED);
        }
        return missing;
    }

    /** Detail text of an {@code INCIDENT_CLOSURE_INCOMPLETE} refusal. */
    public static String closureRefusal(String incidentId, List<String> missing) {
        return "closing incident " + incidentId + " needs " + missing
                + " (SANGAM.md OJ-06 step 8: closure requires the root cause and the lessons learned)";
    }

    public List<Incident> overdue(Connection c, String tenantId) {
        List<Incident> l = Jdbc.query(c, SELECT + " WHERE tenant_id=? AND status='OPEN' AND due_at IS NOT NULL AND due_at<" + dialect.now()
                + " ORDER BY due_at", IncidentReportingService::map, tenantId);
        for (Incident i : l) {
            if (Jdbc.update(c, "UPDATE CMP_INCIDENT SET late=1 WHERE tenant_id=? AND incident_id=? AND late=0", tenantId, i.incidentId()) == 1) {
                audit.organisation(c, tenantId, ComplianceAudit.INCIDENT_OVERDUE, Sql.kv("incident", i.incidentId(), "dueAt", String.valueOf(i.dueAt())));
            }
        }
        return l;
    }

    public List<Incident> between(Connection c, String tenantId, Instant from, Instant to) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND noticed_at>=? AND noticed_at<? ORDER BY noticed_at", IncidentReportingService::map, tenantId, from, to);
    }

    public Incident get(Connection c, String tenantId, String incidentId) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND incident_id=?", IncidentReportingService::map, tenantId, incidentId)
                .orElseThrow(() -> ComplianceException.notFound("incident", incidentId));
    }

    private static List<String> recipients(ComplianceConfig cfg, JurisdictionPack.Authority a) {
        List<String> r = new ArrayList<>(cfg.recipients(a.code()));
        r.addAll(cfg.additionalIncidentRecipients);
        return r;
    }

    private static JurisdictionPack.Authority authority(ComplianceConfig cfg, String code) {
        try {
            return cfg.authority(code);
        } catch (IllegalArgumentException e) {
            throw ComplianceException.invalid(e.getMessage());
        }
    }

    private static final String SELECT = "SELECT incident_id, authority, incident_type, occurred_at, noticed_at, due_at, status, reported_at,"
            + " late, breach_id, follow_ups FROM CMP_INCIDENT";

    private static Incident map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Incident(rs.getString(1), rs.getString(2), rs.getString(3), Jdbc.instant(rs, "occurred_at"), Jdbc.instant(rs, "noticed_at"),
                Jdbc.instant(rs, "due_at"), Status.valueOf(rs.getString(7)), Jdbc.instant(rs, "reported_at"), Sql.bool(rs.getInt(9)),
                rs.getString(10), rs.getInt(11));
    }
}
