// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.rights;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.ConsentService;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.lawful.DisclosureRegister;
import org.sangam.compliance.lawful.LegitimateUseRegistry;
import org.sangam.compliance.lawful.ProcessingRegister;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.ports.CompliancePorts.ErasureOutcome;
import org.sangam.compliance.ports.CompliancePorts.ErasurePort;
import org.sangam.compliance.ports.CompliancePorts.ErasureStatus;
import org.sangam.compliance.ports.CompliancePorts.LegalHoldPort;
import org.sangam.compliance.ports.CompliancePorts.PersonalDataPort;
import org.sangam.compliance.ports.CompliancePorts.PrincipalAuthenticator;
import org.sangam.compliance.ports.CompliancePorts.WorkflowStarter;
import org.sangam.compliance.retention.RetentionScheduler;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Data-subject rights (table {@code CMP_RIGHTS_REQUEST}). The rights, their categories, citations, SLA defaults and
 * maxima and their BPMN process keys come from the pack's {@code rights}; each request starts its process, whose
 * service tasks call back into this service through {@code bpmn.ComplianceSteps}.
 * IN: DPDP ss.11-14, r.9, r.14; SPDI r.5(6), r.5(9) (CMP-035 … CMP-041, CMP-067, CMP-076 … CMP-079, CMP-122, CMP-123).
 *
 * <ul>
 *   <li>Intake: the requester is authenticated against the published identifier type; a nominee acts only through an
 *       invoked nomination. The due date is the configured SLA capped at the pack maximum (and conditional limits).
 *       Exercising a right counts as an approach: retention clocks restart.</li>
 *   <li>ACCESS: summary of personal data, processing activities, and recipients (without withheld disclosures).</li>
 *   <li>RECTIFICATION: accepted corrections applied through {@link PersonalDataPort}.</li>
 *   <li>ERASURE: consents withdrawn, then each root erased through {@link ErasurePort} unless a legal hold, a law that
 *       requires retention, or an applied exemption keeps it.</li>
 *   <li>GRIEVANCE: resolved within the period; the response names the authority complaint route.</li>
 *   <li>Every response carries the published contact.</li>
 * </ul>
 */
public final class DataSubjectRightsService {

    public enum Status { RECEIVED, IN_PROGRESS, RESOLVED, PARTIALLY_RESOLVED, REJECTED }

    public record Submission(String principalRef, String right, String identifierType, String identifierValue, Map<String, String> proof,
                             String nomineeRef, String channel, Map<String, Object> details) {
    }

    public record RightsRequest(String requestId, String principalRef, String right, RightCategory category, Status status,
                                Instant receivedAt, Instant dueAt, Instant closedAt, String processInstanceId, boolean escalated,
                                boolean late, String outcome, String responseText) {
    }

    public record ErasureReport(List<String> erased, Map<String, String> retained) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final PrincipalAuthenticator authenticator;
    private final NominationService nominations;
    private final ConsentService consents;
    private final LegitimateUseRegistry purposes;
    private final ProcessingRegister register;
    private final DisclosureRegister disclosures;
    private final PersonalDataPort personalData;
    private final RetentionScheduler retention;
    private final LegalHoldPort holds;
    private final ErasurePort erasure;
    private final ContactDirectory contacts;
    private final WorkflowStarter workflows;
    private volatile RestrictionService restrictions;

    public void restrictions(RestrictionService restrictions) {
        this.restrictions = restrictions;
    }

    public DataSubjectRightsService(Dialect dialect, Transactions tx, ComplianceAudit audit, ComplianceConfig.Provider config,
                                    PrincipalAuthenticator authenticator, NominationService nominations, ConsentService consents,
                                    LegitimateUseRegistry purposes, ProcessingRegister register, DisclosureRegister disclosures,
                                    PersonalDataPort personalData, RetentionScheduler retention, LegalHoldPort holds,
                                    ErasurePort erasure, ContactDirectory contacts, WorkflowStarter workflows) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
        this.config = config;
        this.authenticator = authenticator;
        this.nominations = nominations;
        this.consents = consents;
        this.purposes = purposes;
        this.register = register;
        this.disclosures = disclosures;
        this.personalData = personalData;
        this.retention = retention;
        this.holds = holds;
        this.erasure = erasure;
        this.contacts = contacts;
        this.workflows = workflows == null ? WorkflowStarter.NONE : workflows;
    }

    public RightsRequest submit(Connection c, String tenantId, Submission s) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.Right right = cfg.pack.right(s.right()).orElseThrow(() ->
                ComplianceException.invalid("right " + s.right() + " is not defined by jurisdiction " + cfg.pack.code()));
        Sql.requireText(s.identifierType(), "identifierType");
        Optional<String> who = authenticator == null ? Optional.empty()
                : authenticator.authenticate(c, tenantId, s.identifierType(), s.identifierValue(), s.proof() == null ? Map.of() : s.proof());
        if (who.isEmpty()) {
            throw new ComplianceException(Code.PRINCIPAL_NOT_AUTHENTICATED, "the requester could not be authenticated");
        }
        String principal;
        if (s.nomineeRef() != null) {
            if (!who.get().equals(s.nomineeRef())) {
                throw new ComplianceException(Code.PRINCIPAL_NOT_AUTHENTICATED, "the nominee could not be authenticated");
            }
            principal = Sql.requireText(s.principalRef(), "principalRef");
            nominations.requireInvoked(c, tenantId, principal, s.nomineeRef());
        } else {
            principal = who.get();
            if (s.principalRef() != null && !s.principalRef().equals(principal)) {
                throw new ComplianceException(Code.PRINCIPAL_NOT_AUTHENTICATED, "the authenticated subject does not match the request");
            }
        }
        Instant now = Sql.now(c, dialect);
        Instant due;
        int days;
        if (right.defaultMonths() > 0) {
            due = now.atZone(cfg.pack.timeZone()).plusMonths(right.defaultMonths()).toInstant();
            days = (int) Duration.between(now, due).toDays();
        } else {
            days = cfg.rightSlaDays(right.code(), now);
            if (days < 1) {
                throw new ComplianceException(Code.SLA_EXCEEDS_LEGAL_MAXIMUM, "SLA for " + right.code() + " is not positive");
            }
            due = now.plus(Duration.ofDays(days));
        }
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_RIGHTS_REQUEST(tenant_id, request_id, principal_ref, right_code, category, status, identifier_type,"
                        + " channel, acting_nominee_ref, received_at, due_at, closed_at, process_instance_id, details_json, outcome, outcome_hash,"
                        + " response_text, escalated, late) VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,?,NULL,NULL,NULL,0,0)",
                tenantId, id, principal, right.code(), right.category(), Status.RECEIVED, s.identifierType(), s.channel(), s.nomineeRef(),
                now, due, Sql.json(s.details() == null ? Map.of() : s.details()));
        audit.principal(c, tenantId, principal, ComplianceAudit.RIGHTS_REQUEST_RECEIVED, Sql.kv("request", id, "right", right.code(),
                "citation", right.citation(), "dueAt", due.toString(), "slaDays", days, "byNominee", s.nomineeRef() != null));
        if (retention != null) {
            retention.onPrincipalActivity(c, tenantId, principal);
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("sangamTenantId", tenantId);
        vars.put("sangamBusinessKey", id);
        vars.put("requestId", id);
        vars.put("rightCode", right.code());
        vars.put("dueAt", due.toString());
        vars.put("warnAt", now.plus(Duration.ofDays(Math.max(1, days * 3L / 4))).toString());
        String pi = workflows.start(c, tenantId, right.processKey(), id, vars);
        if (pi != null) {
            Jdbc.update(c, "UPDATE CMP_RIGHTS_REQUEST SET process_instance_id=? WHERE tenant_id=? AND request_id=?", pi, tenantId, id);
        }
        return get(c, tenantId, id);
    }

    public void markInProgress(Connection c, String tenantId, String requestId, String actor) {
        RightsRequest r = get(c, tenantId, requestId);
        if (r.status() == Status.RECEIVED) {
            Jdbc.update(c, "UPDATE CMP_RIGHTS_REQUEST SET status=? WHERE tenant_id=? AND request_id=?", Status.IN_PROGRESS, tenantId, requestId);
            step(c, tenantId, r, "IN_PROGRESS", actor, Map.of());
        }
    }

    /**
     * One extension of the response period with a written notice of the reason and the new date, given within the
     * original period (US-HIPAA 164.524(b)(2)(ii), 164.526(b)(2), 164.528(c)(1); EU-GDPR Art. 12(3)).
     */
    public RightsRequest extend(Connection c, String tenantId, String requestId, String reason, String actor) {
        Sql.requireText(reason, "reason");
        RightsRequest r = get(c, tenantId, requestId);
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack.Right right = cfg.pack.right(r.right()).orElseThrow();
        if (!right.extendable()) {
            throw new ComplianceException(Code.INVALID_STATE, "the right " + right.code() + " allows no extension (" + right.citation() + ")");
        }
        if (r.closedAt() != null || Jdbc.one(c, "SELECT extended FROM CMP_RIGHTS_REQUEST WHERE tenant_id=? AND request_id=?",
                rs -> rs.getInt(1), tenantId, requestId).orElse(0) == 1) {
            throw new ComplianceException(Code.INVALID_STATE, "only one extension is allowed for an open request");
        }
        Instant now = Sql.now(c, dialect);
        if (now.isAfter(r.dueAt())) {
            throw new ComplianceException(Code.INVALID_STATE, "the extension notice must be given within the original period");
        }
        Instant due = right.extensionMonths() > 0 ? r.dueAt().atZone(cfg.pack.timeZone()).plusMonths(right.extensionMonths()).toInstant()
                : r.dueAt().plus(Duration.ofDays(right.extensionDays()));
        Jdbc.update(c, "UPDATE CMP_RIGHTS_REQUEST SET due_at=?, extended=1, extension_reason=? WHERE tenant_id=? AND request_id=?",
                due, reason, tenantId, requestId);
        audit.principal(c, tenantId, r.principalRef(), ComplianceAudit.RIGHTS_REQUEST_EXTENDED, Sql.kv("request", requestId,
                "right", r.right(), "newDueAt", due.toString(), "reasonHash", Sql.hashHex(reason), "actor", actor));
        return get(c, tenantId, requestId);
    }

    /**
     * Accounting of disclosures over the pack look-back ({@code accountingLookbackDays}), leaving out disclosures on
     * bases the pack excludes (US-HIPAA 164.528(a)(1): treatment, payment, operations, authorisation ...) and withheld ones.
     */
    public List<Map<String, Object>> buildAccounting(Connection c, String tenantId, String requestId, String actor) {
        RightsRequest r = open(c, tenantId, requestId, RightCategory.ACCOUNTING);
        ComplianceConfig cfg = config.forTenant(tenantId);
        Instant from = Sql.now(c, dialect).minus(Duration.ofDays(cfg.limit("accountingLookbackDays", 36500)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (DisclosureRegister.Disclosure d : disclosures.all(c, tenantId, r.principalRef())) {
            if (d.withheldFromAccess() || d.disclosedAt() == null || d.disclosedAt().isBefore(from)) {
                continue;
            }
            boolean excluded = d.purposeCode() != null && purposes.find(c, tenantId, d.purposeCode())
                    .flatMap(p -> cfg.pack.legalBasis(p.basis())).map(JurisdictionPack.LegalBasis::excludedFromAccounting).orElse(false);
            if (!excluded) {
                out.add(Sql.kv("date", d.disclosedAt().toString(), "recipient", d.recipient(), "recipientKind", d.recipientKind().name(),
                        "dataCategories", new ArrayList<>(d.dataCategories()), "purpose", d.purposeCode(), "legalRef", d.legalRef()));
            }
        }
        close(c, tenantId, requestId, Status.RESOLVED, "ACCOUNTING " + out.size() + " DISCLOSURES " + Sql.evidenceHash(out), actor);
        return out;
    }

    /**
     * Denies (fully or partly) an access request under a pack access exception (US: 45 CFR 171.201-205, 171.301-303 ...).
     * Without a valid exception an access request cannot be refused where the pack defines exceptions.
     */
    public void denyWithException(Connection c, String tenantId, String requestId, String exceptionCode, boolean partial, String reason,
                                  String actor) {
        Sql.requireText(reason, "reason");
        RightsRequest r = get(c, tenantId, requestId);
        if (r.category() != RightCategory.ACCESS && r.category() != RightCategory.PORTABILITY) {
            throw new ComplianceException(Code.INVALID_STATE, "access exceptions apply to access requests");
        }
        JurisdictionPack.AccessException ex = config.forTenant(tenantId).pack.accessException(exceptionCode).orElseThrow(() ->
                ComplianceException.invalid("access exception " + exceptionCode + " is not defined by the jurisdiction pack"));
        Jdbc.update(c, "UPDATE CMP_RIGHTS_REQUEST SET access_exception=? WHERE tenant_id=? AND request_id=?", ex.code(), tenantId, requestId);
        close(c, tenantId, requestId, partial ? Status.PARTIALLY_RESOLVED : Status.REJECTED,
                "EXCEPTION " + ex.code() + " (" + ex.citation() + "): " + reason, actor);
    }

    /** Records an agreed restriction (US-HIPAA 164.522; EU-GDPR Art. 18) and closes the request. */
    public void applyRestriction(Connection c, String tenantId, String requestId, String recipientPattern, String purposeCode,
                                 boolean accepted, String reason, String actor) {
        RightsRequest r = open(c, tenantId, requestId, RightCategory.RESTRICTION);
        if (!accepted) {
            Sql.requireText(reason, "reason");
            close(c, tenantId, requestId, Status.REJECTED, "RESTRICTION DECLINED: " + reason, actor);
            return;
        }
        if (restrictions == null) {
            throw new IllegalStateException("RestrictionService is not configured");
        }
        RestrictionService.Restriction x = restrictions.add(c, tenantId, r.principalRef(), recipientPattern, purposeCode, reason,
                config.forTenant(tenantId).pack.right(r.right()).map(JurisdictionPack.Right::citation).orElse(""), actor);
        close(c, tenantId, requestId, Status.RESOLVED, "RESTRICTION " + x.restrictionId(), actor);
    }

    /**
     * Objection (EU-GDPR Art. 21): processing on an objectable basis stops unless the controller records compelling
     * legitimate grounds; consent purposes are withdrawn instead.
     */
    public void applyObjection(Connection c, String tenantId, String requestId, String purposeCode, boolean compellingGrounds,
                               String reason, String actor) {
        RightsRequest r = open(c, tenantId, requestId, RightCategory.OBJECTION);
        ComplianceConfig cfg = config.forTenant(tenantId);
        PurposeDefinition p = purposes.require(c, tenantId, purposeCode);
        if (p.consentBased(cfg.pack)) {
            consents.withdrawPurpose(c, tenantId, r.principalRef(), purposeCode, "rights-request:" + requestId, r.principalRef());
            close(c, tenantId, requestId, Status.RESOLVED, "CONSENT WITHDRAWN " + purposeCode, actor);
        } else if (compellingGrounds) {
            Sql.requireText(reason, "reason");
            close(c, tenantId, requestId, Status.REJECTED, "COMPELLING GROUNDS: " + reason, actor);
        } else {
            purposes.recordObjection(c, tenantId, r.principalRef(), purposeCode, "rights-request:" + requestId);
            close(c, tenantId, requestId, Status.RESOLVED, "OBJECTION RECORDED " + purposeCode, actor);
        }
    }

    /** Human review of a solely automated decision (EU-GDPR Art. 22(3)). */
    public void recordHumanReview(Connection c, String tenantId, String requestId, String outcome, String reviewer) {
        open(c, tenantId, requestId, RightCategory.AUTOMATED_DECISION);
        Sql.requireText(outcome, "outcome");
        close(c, tenantId, requestId, Status.RESOLVED, "HUMAN REVIEW by " + reviewer + ": " + outcome, reviewer);
    }

    public Map<String, Object> buildAccessSummary(Connection c, String tenantId, String requestId, String actor) {
        RightsRequest r = get(c, tenantId, requestId);
        if (r.category() != RightCategory.PORTABILITY) {
            r = open(c, tenantId, requestId, RightCategory.ACCESS);
        } else if (r.closedAt() != null) {
            throw new ComplianceException(Code.INVALID_STATE, "request " + requestId + " is closed");
        }
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("personalData", personalData == null ? Map.of() : personalData.summarise(c, tenantId, r.principalRef()));
        Set<String> purposeCodes = new LinkedHashSet<>(consents.activePurposes(c, tenantId, r.principalRef()));
        if (retention != null) {
            retention.forPrincipal(c, tenantId, r.principalRef()).forEach(i -> purposeCodes.add(i.purposeCode()));
        }
        List<Map<String, Object>> activities = new ArrayList<>();
        for (String p : purposeCodes) {
            Optional<PurposeDefinition> d = purposes.find(c, tenantId, p);
            for (ProcessingRegister.ProcessingActivity a : register.byPurpose(c, tenantId, p)) {
                activities.add(Sql.kv("activity", a.activityId(), "purpose", p, "purposeName", d.map(x -> x.name(pack.defaultLanguage())).orElse(p),
                        "basis", d.flatMap(x -> pack.legalBasis(x.basis())).map(JurisdictionPack.LegalBasis::citation).orElse(""),
                        "description", a.description(), "dataCategories", new ArrayList<>(a.dataCategories()), "processors", a.processors()));
            }
        }
        summary.put("processingActivities", activities);
        List<Map<String, Object>> shared = new ArrayList<>();
        for (DisclosureRegister.Disclosure d : disclosures.forAccessSummary(c, tenantId, r.principalRef())) {
            shared.add(Sql.kv("recipient", d.recipient(), "recipientKind", d.recipientKind().name(),
                    "dataCategories", new ArrayList<>(d.dataCategories()), "disclosedAt", d.disclosedAt() == null ? null : d.disclosedAt().toString()));
        }
        summary.put("sharedWith", shared);
        summary.put("contact", contacts.publishedContact(c, tenantId).map(ContactDirectory.Contact::asMap).orElse(Map.of()));
        String hash = Sql.evidenceHash(summary);
        Jdbc.update(c, "UPDATE CMP_RIGHTS_REQUEST SET outcome_hash=? WHERE tenant_id=? AND request_id=?", hash, tenantId, requestId);
        step(c, tenantId, r, "ACCESS_SUMMARY_BUILT", actor, Map.of("summaryHash", hash, "activities", activities.size(), "sharedWith", shared.size()));
        return summary;
    }

    public List<String> applyCorrection(Connection c, String tenantId, String requestId, Map<String, Object> corrections, boolean accepted,
                                        String reason, String actor) {
        RightsRequest r = open(c, tenantId, requestId, RightCategory.RECTIFICATION);
        if (!accepted) {
            Sql.requireText(reason, "reason");
            close(c, tenantId, requestId, Status.REJECTED, "DECLINED: " + reason, actor);
            return List.of();
        }
        if (personalData == null) {
            throw new IllegalStateException("PersonalDataPort is not configured");
        }
        List<String> changed = personalData.correct(c, tenantId, r.principalRef(), corrections == null ? Map.of() : corrections, actor);
        close(c, tenantId, requestId, Status.RESOLVED, "CORRECTED " + changed, actor);
        return changed;
    }

    public ErasureReport executeErasure(String tenantId, String requestId, String actor) {
        RightsRequest r = tx.inTransaction(c -> open(c, tenantId, requestId, RightCategory.ERASURE));
        ComplianceConfig cfg = config.forTenant(tenantId);
        if (personalData == null) {
            throw new IllegalStateException("PersonalDataPort is not configured");
        }
        List<String> roots = tx.inTransaction(c -> {
            for (String p : consents.activePurposes(c, tenantId, r.principalRef())) {
                consents.withdrawPurpose(c, tenantId, r.principalRef(), p, "rights-request:" + requestId, r.principalRef());
            }
            return personalData.rootsOf(c, tenantId, r.principalRef());
        });
        List<String> keys = tx.inTransaction(c -> personalData.correlationKeysOf(c, tenantId, r.principalRef()));
        List<String> erased = new ArrayList<>();
        Map<String, String> retained = new LinkedHashMap<>();
        for (String root : roots) {
            String why = tx.inTransaction(c -> retentionReason(c, tenantId, root, cfg));
            if (why != null) {
                retained.put(root, why);
                continue;
            }
            ErasureOutcome out = erasure.erase(tenantId, root, keys, actor, "data-subject erasure request " + requestId);
            if (out.status() == ErasureStatus.ERASED) {
                erased.add(root);
            } else {
                retained.put(root, out.status() == ErasureStatus.REFUSED_LEGAL_HOLD ? "LEGAL_HOLD" : "FAILED:" + out.detail());
            }
        }
        Status st = retained.isEmpty() ? Status.RESOLVED : erased.isEmpty() ? Status.REJECTED : Status.PARTIALLY_RESOLVED;
        String outcome = "ERASED " + erased.size() + " RETAINED " + retained;
        tx.inTransaction(c -> {
            close(c, tenantId, requestId, st, outcome, actor);
            return null;
        });
        return new ErasureReport(erased, retained);
    }

    private String retentionReason(Connection c, String tenantId, String root, ComplianceConfig cfg) {
        if (holds.isHeld(c, tenantId, root)) {
            return "LEGAL_HOLD " + holds.activeHoldIds(c, tenantId, root);
        }
        List<String> applied = cfg.appliedExemptions.stream().filter(e -> cfg.pack.exemption(e)
                .map(JurisdictionPack.Exemption::disappliesErasure).orElse(false)).toList();
        if (!applied.isEmpty()) {
            return "EXEMPT " + applied;
        }
        for (String p : Jdbc.query(c, "SELECT DISTINCT purpose_code FROM CMP_RETENTION_ITEM WHERE tenant_id=? AND root_id=? AND state<>'ERASED'",
                rs -> rs.getString(1), tenantId, root)) {
            Optional<PurposeDefinition> d = purposes.find(c, tenantId, p);
            if (d.isEmpty()) {
                continue;
            }
            if (d.get().retentionRequiredByLaw(cfg.pack)) {
                return "RETENTION_REQUIRED_BY_LAW purpose " + p;
            }
            if (d.get().erasureDisapplied(cfg.pack)) {
                return "EXEMPT " + d.get().exemptions();
            }
        }
        return null;
    }

    public void resolveGrievance(Connection c, String tenantId, String requestId, String response, String actor) {
        open(c, tenantId, requestId, RightCategory.GRIEVANCE);
        Sql.requireText(response, "response");
        String url = config.forTenant(tenantId).authorityComplaintUrl;
        close(c, tenantId, requestId, Status.RESOLVED, response + (url.isBlank() ? "" : "\nComplaint to the authority: " + url), actor);
    }

    public void reject(Connection c, String tenantId, String requestId, String reason, String actor) {
        Sql.requireText(reason, "reason");
        RightsRequest req = get(c, tenantId, requestId);
        if ((req.category() == RightCategory.ACCESS || req.category() == RightCategory.PORTABILITY)
                && !config.forTenant(tenantId).pack.accessExceptions().isEmpty()) {
            throw new ComplianceException(Code.INVALID_STATE, "an access request can only be refused under a pack access exception"
                    + " (denyWithException)");
        }
        close(c, tenantId, requestId, Status.REJECTED, "REJECTED: " + reason, actor);
    }

    public void close(Connection c, String tenantId, String requestId, Status status, String outcome, String actor) {
        RightsRequest r = get(c, tenantId, requestId);
        if (r.closedAt() != null) {
            throw new ComplianceException(Code.INVALID_STATE, "request " + requestId + " is already closed");
        }
        Instant now = Sql.now(c, dialect);
        boolean late = now.isAfter(r.dueAt());
        String contact = contacts.publishedContact(c, tenantId).map(ct -> "Contact: " + ct.name()
                + (ct.designation() == null ? "" : ", " + ct.designation()) + ", " + ct.email()).orElse("Contact: (not designated)");
        String trimmed = outcome.length() > 4000 ? outcome.substring(0, 4000) : outcome;
        Jdbc.update(c, "UPDATE CMP_RIGHTS_REQUEST SET status=?, closed_at=?, outcome=?, response_text=?, late=? WHERE tenant_id=? AND request_id=?",
                status, now, trimmed, trimmed + "\n" + contact, late, tenantId, requestId);
        audit.principal(c, tenantId, r.principalRef(), ComplianceAudit.RIGHTS_REQUEST_CLOSED, Sql.kv("request", requestId, "right", r.right(),
                "status", status.name(), "late", late, "outcomeHash", Sql.hashHex(outcome), "actor", actor));
    }

    public List<RightsRequest> escalateOverdue(Connection c, String tenantId) {
        List<RightsRequest> overdue = Jdbc.query(c, SELECT + " WHERE tenant_id=? AND closed_at IS NULL AND escalated=0 AND due_at<" + dialect.now(),
                DataSubjectRightsService::map, tenantId);
        for (RightsRequest r : overdue) {
            escalate(c, tenantId, r.requestId(), "system:sla");
        }
        return overdue;
    }

    public void escalate(Connection c, String tenantId, String requestId, String actor) {
        RightsRequest r = get(c, tenantId, requestId);
        Jdbc.update(c, "UPDATE CMP_RIGHTS_REQUEST SET escalated=1 WHERE tenant_id=? AND request_id=?", tenantId, requestId);
        audit.principal(c, tenantId, r.principalRef(), ComplianceAudit.RIGHTS_REQUEST_ESCALATED, Sql.kv("request", requestId, "right", r.right(),
                "dueAt", r.dueAt().toString(), "actor", actor));
    }

    public RightsRequest get(Connection c, String tenantId, String requestId) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND request_id=?", DataSubjectRightsService::map, tenantId, requestId)
                .orElseThrow(() -> ComplianceException.notFound("rights request", requestId));
    }

    public List<RightsRequest> forPrincipal(Connection c, String tenantId, String principalRef) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND principal_ref=? ORDER BY received_at", DataSubjectRightsService::map, tenantId, principalRef);
    }

    public Map<String, Object> statistics(Connection c, String tenantId, Instant from, Instant to) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (JurisdictionPack.Right k : config.forTenant(tenantId).pack.rights()) {
            m.put(k.code(), Sql.kv(
                    "received", count(c, "tenant_id=? AND right_code=? AND received_at>=? AND received_at<?", tenantId, k.code(), from, to),
                    "closed", count(c, "tenant_id=? AND right_code=? AND closed_at>=? AND closed_at<?", tenantId, k.code(), from, to),
                    "closedLate", count(c, "tenant_id=? AND right_code=? AND closed_at>=? AND closed_at<? AND late=1", tenantId, k.code(), from, to),
                    "openOverdue", count(c, "tenant_id=? AND right_code=? AND closed_at IS NULL AND due_at<" + dialect.now(), tenantId, k.code())));
        }
        return m;
    }

    private long count(Connection c, String where, Object... p) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_RIGHTS_REQUEST WHERE " + where, rs -> rs.getLong(1), p).orElse(0L);
    }

    private RightsRequest open(Connection c, String tenantId, String requestId, RightCategory category) {
        RightsRequest r = get(c, tenantId, requestId);
        if (r.category() != category) {
            throw new ComplianceException(Code.INVALID_STATE, "request " + requestId + " is " + r.category() + ", not " + category);
        }
        if (r.closedAt() != null) {
            throw new ComplianceException(Code.INVALID_STATE, "request " + requestId + " is closed");
        }
        return r;
    }

    private void step(Connection c, String tenantId, RightsRequest r, String step, String actor, Map<String, Object> extra) {
        Map<String, Object> ev = new LinkedHashMap<>(extra);
        ev.put("request", r.requestId());
        ev.put("step", step);
        ev.put("actor", actor);
        audit.principal(c, tenantId, r.principalRef(), ComplianceAudit.RIGHTS_REQUEST_UPDATED, ev);
    }

    private static final String SELECT = "SELECT request_id, principal_ref, right_code, category, status, received_at, due_at, closed_at,"
            + " process_instance_id, escalated, late, outcome, response_text FROM CMP_RIGHTS_REQUEST";

    private static RightsRequest map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RightsRequest(rs.getString(1), rs.getString(2), rs.getString(3), RightCategory.valueOf(rs.getString(4)),
                Status.valueOf(rs.getString(5)), Jdbc.instant(rs, "received_at"), Jdbc.instant(rs, "due_at"), Jdbc.instant(rs, "closed_at"),
                rs.getString(9), Sql.bool(rs.getInt(10)), Sql.bool(rs.getInt(11)), rs.getString(12), rs.getString(13));
    }
}
