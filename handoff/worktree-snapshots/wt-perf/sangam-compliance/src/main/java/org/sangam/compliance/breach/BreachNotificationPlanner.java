// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.breach;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Threshold-driven breach notification plans from the pack's {@code breach.notifications} plus the region's
 * additional rules (table {@code CMP_BREACH_PLAN}), the documented risk assessment and law-enforcement delays.
 *
 * <ul>
 *   <li>{@link #assessRisk}: the pack's risk factors must all be documented. Under the presumption
 *       {@code NOTIFIABLE_UNLESS_LOW_PROBABILITY} (US-HIPAA 164.402: an impermissible use or disclosure is a breach unless
 *       a four-factor assessment shows a low probability of compromise) the outcome is NOT_NOTIFIABLE only with that
 *       finding; the assessment is kept as proof (164.414(b)). Under {@code NOTIFY_IF_RISK} (EU-GDPR Art. 33(1)) the
 *       authority is notified unless the breach is unlikely to result in a risk; subjects only if high risk (Art. 34).</li>
 *   <li>{@link #plan}: each rule applies by affected count, per-region count (e.g. media notice for more than 500
 *       residents of a State, 164.406) and high risk; deadlines from awareness, after the end of the calendar year
 *       (164.408(c): fewer than 500, within 60 days of year end) or without delay; region rules (US state laws,
 *       member-state practice) are added or replace a pack rule of the same code.</li>
 *   <li>{@link #lawEnforcementDelay}: a written statement delays notices for the period it states; an oral one for at
 *       most the pack's {@code oralMaxDays} (164.412: 30 days). Due dates move by the delay.</li>
 * </ul>
 * The IN pack keeps its fixed-step flow in {@link BreachIncidentService}; it can use plans too.
 */
public final class BreachNotificationPlanner {

    public enum Outcome { NOTIFIABLE, NOT_NOTIFIABLE }

    public record PlannedNotification(String ruleCode, String recipient, String authority, Instant dueAt, String status, Instant sentAt,
                                      boolean late, String citation) {
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final BreachIncidentService breaches;

    public BreachNotificationPlanner(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config, BreachIncidentService breaches) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.breaches = breaches;
    }

    public Outcome assessRisk(Connection c, String tenantId, String breachId, Map<String, String> factorFindings,
                              boolean lowProbabilityOfCompromise, boolean highRisk, String assessor) {
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        breaches.get(c, tenantId, breachId);
        List<String> missing = new ArrayList<>();
        for (JurisdictionPack.ReportItem f : pack.breachRiskFactors()) {
            String v = factorFindings == null ? null : factorFindings.get(f.key());
            if (v == null || v.isBlank()) {
                missing.add(f.label() + (f.citation().isBlank() ? "" : " (" + f.citation() + ")"));
            }
        }
        if (!missing.isEmpty()) {
            throw new ComplianceException(Code.REPORT_INCOMPLETE, "risk assessment lacks " + missing);
        }
        Outcome outcome = lowProbabilityOfCompromise ? Outcome.NOT_NOTIFIABLE : Outcome.NOTIFIABLE;
        Map<String, Object> record = new LinkedHashMap<>(factorFindings == null ? Map.of() : factorFindings);
        record.put("lowProbabilityOfCompromise", lowProbabilityOfCompromise);
        record.put("presumption", pack.breachPresumption());
        record.put("assessor", assessor);
        Jdbc.update(c, "UPDATE CMP_BREACH SET risk_outcome=?, risk_json=?, high_risk=? WHERE tenant_id=? AND breach_id=?",
                outcome, Sql.json(record), highRisk, tenantId, breachId);
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_RISK_ASSESSED, Sql.kv("breach", breachId, "outcome", outcome.name(),
                "highRisk", highRisk, "assessmentHash", Sql.evidenceHash(record), "actor", assessor));
        return outcome;
    }

    /** Builds (or rebuilds) the plan; {@code affectedPerRegion} counts affected subjects by region code (e.g. US state). */
    public List<PlannedNotification> plan(Connection c, String tenantId, String breachId, int affected, Map<String, Integer> affectedPerRegion,
                                          String actor) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        BreachIncidentService.Breach b = breaches.get(c, tenantId, breachId);
        Object[] risk = Jdbc.one(c, "SELECT risk_outcome, high_risk, delay_until FROM CMP_BREACH WHERE tenant_id=? AND breach_id=?",
                rs -> new Object[]{rs.getString(1), Sql.bool(rs.getInt(2)), Jdbc.instant(rs, "delay_until")}, tenantId, breachId).orElseThrow();
        if (risk[0] == null && !cfg.pack.breachRiskFactors().isEmpty()) {
            throw new ComplianceException(Code.INVALID_STATE, "document the risk assessment first");
        }
        Jdbc.update(c, "DELETE FROM CMP_BREACH_PLAN WHERE tenant_id=? AND breach_id=? AND status='PLANNED'", tenantId, breachId);
        if (Outcome.NOT_NOTIFIABLE.name().equals(risk[0])) {
            audit.organisation(c, tenantId, ComplianceAudit.BREACH_PLAN, Sql.kv("breach", breachId, "notifications", 0, "reason", "NOT_NOTIFIABLE"));
            return list(c, tenantId, breachId);
        }
        boolean highRisk = (Boolean) risk[1];
        Instant delayUntil = (Instant) risk[2];
        int maxRegion = affectedPerRegion == null ? 0 : affectedPerRegion.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (JurisdictionPack.BreachNotificationRule r : cfg.breachRules()) {
            int regionCount = cfg.region != null && cfg.region.breachRules().contains(r) && affectedPerRegion != null
                    ? affectedPerRegion.getOrDefault(cfg.region.code(), 0) : maxRegion;
            if (affected < r.minAffected() || affected > r.maxAffected() || regionCount < r.minAffectedInRegion()
                    || r.requiresHighRisk() && !highRisk || r.whenAttribute() != null && !cfg.has(r.whenAttribute())) {
                continue;
            }
            Instant due = dueAt(cfg, r, b.awareAt());
            if (delayUntil != null && due != null && delayUntil.isAfter(b.awareAt())) {
                due = due.plus(Duration.between(b.awareAt(), delayUntil));
            }
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CMP_BREACH_PLAN(tenant_id, breach_id, rule_code, recipient, authority, due_at, status,"
                    + " sent_at, ref, late, citation) VALUES (?,?,?,?,?,?,'PLANNED',NULL,NULL,0,?)", tenantId, breachId, r.code(), r.recipient(),
                    r.authority(), due, r.citation());
        }
        List<PlannedNotification> plan = list(c, tenantId, breachId);
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_PLAN, Sql.kv("breach", breachId, "affected", affected, "maxRegion", maxRegion,
                "highRisk", highRisk, "notifications", plan.stream().map(PlannedNotification::ruleCode).toList(), "actor", actor));
        return plan;
    }

    static Instant dueAt(ComplianceConfig cfg, JurisdictionPack.BreachNotificationRule r, Instant aware) {
        int hours = cfg.limit("breach." + r.code() + ".deadlineHours", r.deadlineHours());
        return switch (r.deadlineMode()) {
            case "AFTER_YEAR_END" -> {
                int year = aware.atZone(cfg.pack.timeZone()).getYear();
                yield LocalDate.of(year + 1, 1, 1).atStartOfDay(cfg.pack.timeZone()).toInstant().plus(Duration.ofHours(hours));
            }
            case "WITHOUT_DELAY" -> hours > 0 ? aware.plus(Duration.ofHours(hours)) : aware;
            default -> aware.plus(Duration.ofHours(hours));
        };
    }

    public void recordSent(Connection c, String tenantId, String breachId, String ruleCode, String ref, String actor) {
        Instant now = Sql.now(c, dialect);
        PlannedNotification p = list(c, tenantId, breachId).stream().filter(x -> x.ruleCode().equals(ruleCode)).findFirst()
                .orElseThrow(() -> ComplianceException.notFound("planned notification", ruleCode));
        boolean late = p.dueAt() != null && now.isAfter(p.dueAt());
        Jdbc.update(c, "UPDATE CMP_BREACH_PLAN SET status='SENT', sent_at=?, ref=?, late=? WHERE tenant_id=? AND breach_id=? AND rule_code=?",
                now, ref, late, tenantId, breachId, ruleCode);
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_NOTIFICATION_SENT, Sql.kv("breach", breachId, "rule", ruleCode, "ref", ref,
                "late", late, "actor", actor));
    }

    /** Law-enforcement delay; an oral statement is capped at the pack's oral maximum. */
    public Instant lawEnforcementDelay(Connection c, String tenantId, String breachId, Instant until, boolean written, String statementRef,
                                       String actor) {
        Sql.requireText(statementRef, "statementRef");
        ComplianceConfig cfg = config.forTenant(tenantId);
        Instant now = Sql.now(c, dialect);
        Instant effective = until;
        if (!written && cfg.pack.lawEnforcementOralDelayDays() > 0) {
            Instant cap = now.plus(Duration.ofDays(cfg.pack.lawEnforcementOralDelayDays()));
            effective = until == null || until.isAfter(cap) ? cap : until;
        }
        if (effective == null) {
            throw ComplianceException.invalid("a delay needs an end date");
        }
        Jdbc.update(c, "UPDATE CMP_BREACH SET delay_until=?, delay_ref=? WHERE tenant_id=? AND breach_id=?", effective, statementRef, tenantId, breachId);
        audit.organisation(c, tenantId, ComplianceAudit.BREACH_DELAYED, Sql.kv("breach", breachId, "until", effective.toString(),
                "written", written, "statement", statementRef, "actor", actor));
        return effective;
    }

    public List<PlannedNotification> overdue(Connection c, String tenantId) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND status='PLANNED' AND due_at<" + dialect.now() + " ORDER BY due_at",
                BreachNotificationPlanner::map, tenantId);
    }

    public List<PlannedNotification> list(Connection c, String tenantId, String breachId) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND breach_id=? ORDER BY due_at", BreachNotificationPlanner::map, tenantId, breachId);
    }

    private static final String SELECT = "SELECT rule_code, recipient, authority, due_at, status, sent_at, late, citation FROM CMP_BREACH_PLAN";

    private static PlannedNotification map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlannedNotification(rs.getString(1), rs.getString(2), rs.getString(3), Jdbc.instant(rs, "due_at"), rs.getString(5),
                Jdbc.instant(rs, "sent_at"), Sql.bool(rs.getInt(7)), rs.getString(8));
    }
}
