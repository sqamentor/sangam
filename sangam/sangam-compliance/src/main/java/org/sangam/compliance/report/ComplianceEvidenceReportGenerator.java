// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.report;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.accountability.AccountabilityService;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.breach.BreachIncidentService;
import org.sangam.compliance.breach.IncidentReportingService;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.ConsentNoticeService;
import org.sangam.compliance.consent.ConsentService;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.lawful.LawfulRequestService;
import org.sangam.compliance.lawful.LegitimateUseRegistry;
import org.sangam.compliance.lawful.ProcessingRegister;
import org.sangam.compliance.logs.AuditIntegrityVerifier;
import org.sangam.compliance.logs.LogArchiveService;
import org.sangam.compliance.logs.NtpTimeSourceVerifier;
import org.sangam.compliance.retention.RetentionScheduler;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.compliance.security.SecurityPracticesChecklist;
import org.sangam.compliance.support.Html;
import org.sangam.compliance.support.Sql;
import org.sangam.compliance.transfer.CrossBorderTransferGuard;
import org.sangam.compliance.transfer.DataLocalisationPolicy;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compliance evidence report for a tenant and a period: configuration and pack, notices and languages, consents,
 * rights requests and timeliness, retention states, breaches and authority deadlines, incident reports and deadlines,
 * lawful requests, log archive and integrity of the audit chains, time-source checks, accountability evidence,
 * localisation findings, transfer decisions, the security checklist, and the pack's obligation catalogue with an
 * automated status per obligation (SUPPORTED, ATTENTION, MANUAL, DEPLOYER). Output as canonical JSON and as HTML;
 * the report hash is stored in {@code CMP_REPORT} and audited. IN: the evidence the Board, CERT-In or an auditor asks
 * for (CMP-022, CMP-082, CMP-089, CMP-105, CMP-109).
 */
public final class ComplianceEvidenceReportGenerator {

    public record Report(String reportId, String hash, Map<String, Object> content, String json, String html) {
    }

    /** Everything the generator reads. */
    public record Sources(ComplianceConfig.Provider config, LegitimateUseRegistry purposes, ProcessingRegister register,
                          ConsentNoticeService notices, ConsentService consents, DataSubjectRightsService rights,
                          RetentionScheduler retention, BreachIncidentService breaches, IncidentReportingService incidents,
                          LawfulRequestService lawfulRequests, LogArchiveService logs, NtpTimeSourceVerifier ntp,
                          AuditIntegrityVerifier integrity, AccountabilityService accountability, ContactDirectory contacts,
                          DataLocalisationPolicy localisation, CrossBorderTransferGuard transfers, SecurityPracticesChecklist checklist) {
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final Sources s;

    public ComplianceEvidenceReportGenerator(Dialect dialect, ComplianceAudit audit, Sources sources) {
        this.dialect = dialect;
        this.audit = audit;
        this.s = sources;
    }

    public Report generate(Connection c, String tenantId, Instant from, Instant to, boolean verifyChains, String actor) {
        ComplianceConfig cfg = s.config().forTenant(tenantId);
        JurisdictionPack pack = cfg.pack;
        Instant now = Sql.now(c, dialect);
        Map<String, List<String>> findings = new LinkedHashMap<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tenant", tenantId);
        r.put("jurisdiction", Sql.kv("code", pack.code(), "name", pack.name(),
                "instruments", pack.instruments().stream().map(i -> i.title() + " (" + i.reference() + ")").toList()));
        r.put("period", Sql.kv("from", from.toString(), "to", to.toString()));
        r.put("generatedAt", now.toString());
        r.put("configuration", cfg.summary());
        add(findings, "config", cfg.validate());
        add(findings, "config", cfg.publicationGaps());

        Map<String, String> languageGaps = s.notices().languageGaps(tenantId);
        r.put("notices", Sql.kv("languagesEnabled", cfg.enabledLanguages.size(), "languageGaps", languageGaps));
        languageGaps.forEach((l, g) -> add(findings, "consent", List.of("notice language " + l + ": " + g)));
        r.put("purposes", s.purposes().list(c, tenantId).stream().map(p -> Sql.kv("code", p.code(), "basis", p.basis(),
                "active", p.active(), "exemptions", new ArrayList<>(p.exemptions()))).toList());
        r.put("processingActivities", s.register().list(c, tenantId).size());
        r.put("consents", new LinkedHashMap<>(s.consents().counts(c, tenantId, from, to)));

        Map<String, Object> rights = s.rights().statistics(c, tenantId, from, to);
        r.put("rights", rights);
        rights.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m && m.get("openOverdue") instanceof Long n && n > 0) {
                add(findings, "rights", List.of(n + " overdue " + k + " request(s)"));
            }
        });

        r.put("retention", Sql.kv("states", new LinkedHashMap<>(s.retention().stateCounts(c, tenantId)),
                "overdueUnprocessed", s.retention().overdue(c, tenantId)));
        if (s.retention().overdue(c, tenantId) > 0) {
            add(findings, "retention", List.of("retention scheduler has unprocessed due items"));
        }

        List<Map<String, Object>> breaches = new ArrayList<>();
        for (BreachIncidentService.Breach b : s.breaches().between(c, tenantId, from, to)) {
            breaches.add(Sql.kv("breach", b.breachId(), "status", b.status().name(), "awareAt", b.awareAt().toString(),
                    "authorityNotifiedAt", str(b.authorityNotifiedAt()), "reportDueAt", str(b.effectiveDeadline()),
                    "reportedAt", str(b.reportedAt()), "late", b.reportLate(), "subjectsAffected", b.subjectsAffected(),
                    "subjectsNotified", b.subjectsNotified()));
            if (b.reportLate()) {
                add(findings, "breach", List.of("breach " + b.breachId() + " detailed report late"));
            }
        }
        s.breaches().overdue(c, tenantId).forEach(b -> add(findings, "breach", List.of("breach " + b.breachId() + " report overdue")));
        r.put("breaches", breaches);

        List<Map<String, Object>> incidents = new ArrayList<>();
        for (IncidentReportingService.Incident i : s.incidents().between(c, tenantId, from, to)) {
            incidents.add(Sql.kv("incident", i.incidentId(), "authority", i.authority(), "type", i.type(), "noticedAt", i.noticedAt().toString(),
                    "dueAt", str(i.dueAt()), "reportedAt", str(i.reportedAt()), "late", i.late(), "followUps", i.followUps()));
            if (i.late()) {
                add(findings, "incident", List.of("incident " + i.incidentId() + " reported late to " + i.authority()));
            }
        }
        s.incidents().overdue(c, tenantId).forEach(i -> add(findings, "incident", List.of("incident " + i.incidentId() + " overdue")));
        r.put("incidents", incidents);

        r.put("lawfulRequests", s.lawfulRequests().between(c, tenantId, from, to).stream().map(l -> Sql.kv("request", l.requestId(),
                "basis", l.basis(), "status", l.status().name(), "late", l.late())).toList());
        s.lawfulRequests().overdue(c, tenantId).forEach(l -> add(findings, "lawful", List.of("lawful request " + l.requestId() + " overdue")));

        Map<String, Object> logs = new LinkedHashMap<>(s.logs().describe(c, tenantId));
        if (logs.get("nonCompliantSources") instanceof List<?> bad && !bad.isEmpty()) {
            add(findings, "logs", List.of("log sources below retention / residency: " + bad));
        }
        if (verifyChains) {
            AuditIntegrityVerifier.Result v = s.integrity().verifyTenant(c, tenantId, 0);
            logs.put("integrity", Sql.kv("ok", v.ok(), "chains", v.chains(), "events", v.events(),
                    "failures", v.failures().stream().map(f -> f.chainId() + "@" + f.firstBadSeq() + ": " + f.reason()).toList()));
            if (!v.ok()) {
                add(findings, "logs", List.of(v.failures().size() + " audit chain(s) failed verification"));
            }
        }
        r.put("logs", logs);
        Object ntp = s.ntp().latest(c, tenantId).map(m -> (Object) m).orElse("no check recorded");
        r.put("timeSource", ntp);
        if (!(ntp instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("ok")))) {
            add(findings, "ntp", List.of("no passing time-source check"));
        }

        List<AccountabilityService.ObligationStatus> acc = s.accountability().status(c, tenantId);
        r.put("accountability", acc.stream().map(AccountabilityService.ObligationStatus::asMap).toList());
        acc.stream().filter(x -> !x.met()).forEach(x -> add(findings, "accountability", List.of(x.description() + " not met")));
        r.put("contacts", s.contacts().all(c, tenantId).stream().map(ContactDirectory.Contact::asMap).toList());

        List<String> loc = s.localisation().findings(tenantId, LocalDate.ofInstant(now, pack.timeZone()));
        r.put("localisation", loc);
        add(findings, "transfer", loc);
        r.put("transfers", Sql.kv("denied", s.transfers().deniedCount(c, tenantId, from, to),
                "allowedAbroad", s.transfers().allowedAbroadCount(c, tenantId, from, to)));

        List<SecurityPracticesChecklist.ItemResult> sec = s.checklist().evaluate(tenantId, now);
        r.put("securityChecklist", sec.stream().map(SecurityPracticesChecklist.ItemResult::asMap).toList());
        sec.stream().filter(x -> x.result() != SecurityPracticesChecklist.Result.PASS)
                .forEach(x -> add(findings, "security", List.of(x.id() + " " + x.result())));

        List<Map<String, Object>> obligations = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ObligationCatalogue.Obligation o : ObligationCatalogue.load(pack)) {
            String status;
            if ("DF".equals(o.bearer())) {
                status = "DEPLOYER";
            } else if ("SDF".equals(o.bearer()) && !s.accountability().elevated(c, tenantId)) {
                status = "NOT_APPLICABLE";
            } else if ("none".equals(o.check())) {
                status = "MANUAL";
            } else if (findings.containsKey(o.check())) {
                status = "ATTENTION";
            } else {
                status = "SUPPORTED";
            }
            totals.merge(status, 1, Integer::sum);
            obligations.add(Sql.kv("id", o.id(), "citation", o.citation(), "bearer", o.bearer(), "area", o.check(), "status", status,
                    "inForceFrom", String.valueOf(pack.inForceFrom(o.citation())), "verification", o.verification(), "text", o.text()));
        }
        r.put("obligationTotals", totals);
        r.put("obligations", obligations);
        Map<String, Object> f = new LinkedHashMap<>();
        findings.forEach((k, v) -> f.put(k, v));
        r.put("findings", f);

        String json = CanonicalJson.write(r);
        String hash = Sql.hashHex(json);
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_REPORT(tenant_id, report_id, period_from, period_to, report_hash, generated_by, generated_at)"
                + " VALUES (?,?,?,?,?,?,?)", tenantId, id, from, to, hash, actor, now);
        audit.organisation(c, tenantId, ComplianceAudit.REPORT_GENERATED, Sql.kv("report", id, "hash", hash, "findings", findings.size(), "actor", actor));
        return new Report(id, hash, r, json, html(r, hash));
    }

    private static void add(Map<String, List<String>> findings, String area, List<String> items) {
        if (items != null && !items.isEmpty()) {
            findings.computeIfAbsent(area, k -> new ArrayList<>()).addAll(items);
        }
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    @SuppressWarnings("unchecked")
    static String html(Map<String, Object> r, String hash) {
        StringBuilder h = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Compliance evidence report</title>"
                + "<style>body{font-family:sans-serif;margin:24px}table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 6px;"
                + "font-size:13px;vertical-align:top}.ATTENTION{background:#fde2e1}.SUPPORTED{background:#e3f6e5}.DEPLOYER{background:#eef}</style>"
                + "</head><body>");
        Map<String, Object> j = (Map<String, Object>) r.get("jurisdiction");
        h.append("<h1>Compliance evidence report</h1><p>Tenant ").append(Html.esc(r.get("tenant"))).append(" &middot; jurisdiction ")
                .append(Html.esc(j.get("name"))).append(" &middot; generated ").append(Html.esc(r.get("generatedAt")))
                .append("<br>Report SHA-256 ").append(Html.esc(hash)).append("</p>");
        h.append("<h2>Findings</h2>");
        Map<String, Object> f = (Map<String, Object>) r.get("findings");
        if (f.isEmpty()) {
            h.append("<p>None.</p>");
        } else {
            h.append("<ul>");
            f.forEach((k, v) -> ((List<Object>) v).forEach(x -> h.append("<li><b>").append(Html.esc(k)).append("</b>: ").append(Html.esc(x)).append("</li>")));
            h.append("</ul>");
        }
        h.append("<h2>Obligations</h2><p>").append(Html.esc(r.get("obligationTotals"))).append("</p><table><tr><th>Id</th><th>Citation</th>"
                + "<th>Bearer</th><th>Area</th><th>Status</th><th>In force</th><th>Obligation</th></tr>");
        for (Object o : (List<Object>) r.get("obligations")) {
            Map<String, Object> m = (Map<String, Object>) o;
            h.append("<tr class=\"").append(Html.esc(m.get("status"))).append("\"><td>").append(Html.esc(m.get("id"))).append("</td><td>")
                    .append(Html.esc(m.get("citation"))).append("</td><td>").append(Html.esc(m.get("bearer"))).append("</td><td>")
                    .append(Html.esc(m.get("area"))).append("</td><td>").append(Html.esc(m.get("status"))).append("</td><td>")
                    .append(Html.esc(m.get("inForceFrom"))).append("</td><td>").append(Html.esc(m.get("text"))).append("</td></tr>");
        }
        h.append("</table>");
        for (String section : List.of("configuration", "notices", "consents", "rights", "retention", "breaches", "incidents", "lawfulRequests",
                "logs", "timeSource", "accountability", "contacts", "localisation", "transfers", "securityChecklist")) {
            h.append("<h2>").append(Html.esc(section)).append("</h2><pre>").append(Html.esc(CanonicalJson.write(r.get(section)))).append("</pre>");
        }
        return h.append("</body></html>").toString();
    }
}
