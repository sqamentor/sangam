// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.accountability.AccountabilityService;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.breach.BreachIncidentService;
import org.sangam.compliance.breach.IncidentReportingService;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.ConsentManagerPort;
import org.sangam.compliance.consent.ConsentNoticeService;
import org.sangam.compliance.consent.ConsentService;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.lawful.LawfulRequestService;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.logs.AuditIntegrityVerifier;
import org.sangam.compliance.logs.LogArchiveService;
import org.sangam.compliance.report.ComplianceEvidenceReportGenerator;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.compliance.transfer.CrossBorderTransferGuard;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.core.security.SecurityIdentity;

/**
 * Delegates every compliance endpoint to the {@link SangamCompliance} services, one transaction per call (the
 * compliance {@code Transactions}: the engine transaction when the server wires {@code Transactions.of(engineAdapter)}).
 * The actor recorded in compliance audit events is the caller's person id.
 */
public final class ComplianceApiImpl implements ComplianceRestService {

    private static final int MAX_CHAINS = 10_000;

    private final SangamCompliance compliance;

    public ComplianceApiImpl(SangamCompliance compliance) {
        this.compliance = compliance;
    }

    private <T> T tx(Function<Connection, T> work) {
        return compliance.tx.inTransaction(work);
    }

    private ComplianceConfig cfg(String tenant) {
        return compliance.config.forTenant(tenant);
    }

    private JurisdictionPack.Language language(String tenant, String code) {
        ComplianceConfig c = cfg(tenant);
        return code == null || code.isBlank() ? c.defaultLanguage : c.pack.requireLanguage(code);
    }

    private static Instant now() {
        return SangamClockHolder.now();
    }

    // ---------------------------------------------------------------- notices and purposes

    @Override
    public ConsentNoticeService.RenderedNotice notice(String tenantId, String key, Integer version, String language) {
        return tx(c -> compliance.notices.get(c, tenantId, key, version, language(tenantId, language)));
    }

    @Override
    public ConsentNoticeService.PublishResult publishNotice(SecurityIdentity id, String key, Set<String> purposeCodes) {
        return tx(c -> compliance.notices.publish(c, id.tenantId(), key, purposeCodes, id.personId()));
    }

    @Override
    public ConsentNoticeService.RenderedNotice privacyPolicy(String tenantId, String language) {
        return tx(c -> compliance.notices.privacyPolicy(c, tenantId, language(tenantId, language)));
    }

    @Override
    public PurposeDefinition definePurpose(SecurityIdentity id, PurposeDefinition purpose) {
        return tx(c -> compliance.purposes.define(c, id.tenantId(), purpose, id.personId()));
    }

    @Override
    public List<PurposeDefinition> purposes(SecurityIdentity id) {
        return tx(c -> compliance.purposes.list(c, id.tenantId()));
    }

    // ---------------------------------------------------------------- consents

    @Override
    public ConsentService.Consent giveConsent(SecurityIdentity id, ConsentService.GiveConsent request) {
        return tx(c -> compliance.consents.give(c, id.tenantId(), request));
    }

    @Override
    public ConsentService.GiveConsent toGiveConsent(SecurityIdentity id, ComplianceRequests.GiveConsentRequest r) {
        String subject = r.principalRef() == null || r.principalRef().isBlank() ? id.personId() : r.principalRef();
        return new ConsentService.GiveConsent(subject, r.noticeKey(), r.noticeVersion(), language(id.tenantId(), r.language()),
                r.purposeCodes(), r.affirmativeAction(), r.channel() == null ? "api" : r.channel(), id.personId(),
                r.guardianVerificationId(), r.consentManagerId(), r.cmArtefactId());
    }

    @Override
    public ConsentService.Consent withdrawConsent(SecurityIdentity id, String consentId, Set<String> purposeCodes) {
        return tx(c -> {
            ConsentService.Consent k = compliance.consents.get(c, id.tenantId(), consentId);
            return compliance.consents.withdraw(c, id.tenantId(), k.principalRef(), consentId, purposeCodes, "api",
                    id.personId());
        });
    }

    @Override
    public String consentPrincipal(SecurityIdentity id, String consentId) {
        return tx(c -> compliance.consents.get(c, id.tenantId(), consentId).principalRef());
    }

    @Override
    public List<ConsentService.Consent> consents(SecurityIdentity id, String principalRef) {
        return tx(c -> compliance.consents.history(c, id.tenantId(), principalRef));
    }

    @Override
    public ConsentService.Consent ingestArtefact(String tenantId, ConsentManagerPort.ConsentArtefact artefact) {
        return tx(c -> compliance.consentManagers.ingest(c, tenantId, artefact));
    }

    // ---------------------------------------------------------------- rights

    @Override
    public DataSubjectRightsService.RightsRequest submitRightsRequest(String tenantId,
                                                                    DataSubjectRightsService.Submission submission) {
        return tx(c -> compliance.rights.submit(c, tenantId, submission));
    }

    @Override
    public DataSubjectRightsService.RightsRequest rightsRequest(SecurityIdentity id, String requestId) {
        return tx(c -> compliance.rights.get(c, id.tenantId(), requestId));
    }

    @Override
    public List<DataSubjectRightsService.RightsRequest> rightsRequestsOf(SecurityIdentity id, String principalRef) {
        return tx(c -> compliance.rights.forPrincipal(c, id.tenantId(), principalRef));
    }

    // ---------------------------------------------------------------- breaches and incidents

    @Override
    public BreachIncidentService.Breach registerBreach(SecurityIdentity id, Map<String, String> facts, Instant awareAt,
                                                      Set<String> subjects, Set<String> roots, String incidentType) {
        return tx(c -> compliance.breaches.register(c, id.tenantId(), facts, now(), awareAt == null ? now() : awareAt,
                subjects == null ? Set.of() : subjects, roots == null ? Set.of() : roots, incidentType, List.of(),
                id.personId()));
    }

    @Override
    public BreachIncidentService.Breach breach(SecurityIdentity id, String breachId) {
        return tx(c -> compliance.breaches.get(c, id.tenantId(), breachId));
    }

    @Override
    public IncidentReportingService.Incident registerIncident(SecurityIdentity id, String authority, String type,
                                                              Instant occurredAt, Instant noticedAt, List<String> systems,
                                                              String description) {
        return tx(c -> compliance.incidents.register(c, id.tenantId(), authority, type, occurredAt,
                noticedAt == null ? now() : noticedAt, systems == null ? List.of() : systems, Map.of(), description,
                null, id.personId()));
    }

    @Override
    public IncidentReportingService.Incident incident(SecurityIdentity id, String incidentId) {
        return tx(c -> compliance.incidents.get(c, id.tenantId(), incidentId));
    }

    @Override
    public List<String> submitIncident(SecurityIdentity id, String incidentId, String actionsTaken) {
        return tx(c -> compliance.incidents.submit(c, id.tenantId(), incidentId, actionsTaken, id.personId()));
    }

    // ---------------------------------------------------------------- lawful requests (maker-checker)

    @Override
    public String receiveLawfulRequest(SecurityIdentity id, String authority, String basis, String reference,
                                       String scope, Instant dueAt) {
        return receiveLawfulRequest(id, new ComplianceRequests.LawfulRequestBody(authority, null, null, basis, reference,
                scope, dueAt)).requestId();
    }

    @Override
    public LawfulRequestService.LawfulRequest receiveLawfulRequest(SecurityIdentity id,
                                                                   ComplianceRequests.LawfulRequestBody b) {
        return tx(c -> compliance.lawfulRequests.receive(c, id.tenantId(), b.authority(), b.officerName(),
                b.officerDesignation(), b.basis(), b.reference(), b.scope(), b.dueAt(), id.personId()));
    }

    @Override
    public LawfulRequestService.LawfulRequest lawfulRequest(SecurityIdentity id, String requestId) {
        return tx(c -> compliance.lawfulRequests.get(c, id.tenantId(), requestId));
    }

    @Override
    public LawfulRequestService.LawfulRequest verifyLawfulRequest(SecurityIdentity id, String requestId,
                                                                  boolean rankAdequate) {
        return tx(c -> {
            compliance.lawfulRequests.verify(c, id.tenantId(), requestId, rankAdequate, id.personId());
            return compliance.lawfulRequests.get(c, id.tenantId(), requestId);
        });
    }

    @Override
    public LawfulRequestService.LawfulRequest approveLawfulRequest(SecurityIdentity id, String requestId) {
        return tx(c -> {
            compliance.lawfulRequests.approve(c, id.tenantId(), requestId, id.personId());
            return compliance.lawfulRequests.get(c, id.tenantId(), requestId);
        });
    }

    @Override
    public LawfulRequestService.LawfulRequest respondLawfulRequest(SecurityIdentity id, String requestId,
                                                                   String responseRef, List<String> principalRefs,
                                                                   Set<String> dataCategories) {
        return tx(c -> {
            compliance.lawfulRequests.respond(c, id.tenantId(), requestId, responseRef,
                    principalRefs == null ? List.of() : principalRefs,
                    dataCategories == null ? Set.of() : dataCategories, id.personId());
            return compliance.lawfulRequests.get(c, id.tenantId(), requestId);
        });
    }

    @Override
    public LawfulRequestService.LawfulRequest rejectLawfulRequest(SecurityIdentity id, String requestId, String reason) {
        return tx(c -> {
            compliance.lawfulRequests.reject(c, id.tenantId(), requestId, reason, id.personId());
            return compliance.lawfulRequests.get(c, id.tenantId(), requestId);
        });
    }

    // ---------------------------------------------------------------- accountability

    @Override
    public void setContact(SecurityIdentity id, ContactDirectory.Contact contact) {
        tx(c -> compliance.contacts.set(c, id.tenantId(), contact, id.personId()));
    }

    @Override
    public List<ContactDirectory.Contact> contacts(SecurityIdentity id) {
        return tx(c -> compliance.contacts.all(c, id.tenantId()));
    }

    @Override
    public void recordAuditEvidence(SecurityIdentity id, String kind, Instant completedAt, String auditor,
                                    String accreditationRef, boolean independent, String reportHash) {
        recordAudit(id, new ComplianceRequests.AuditEvidenceRequest(kind, null, null, completedAt, auditor,
                accreditationRef, independent, List.of(), reportHash, null));
    }

    @Override
    public AccountabilityService.AuditEvidence recordAudit(SecurityIdentity id, ComplianceRequests.AuditEvidenceRequest r) {
        return tx(c -> compliance.accountability.recordAudit(c, id.tenantId(), r.kind(), r.periodFrom(), r.periodTo(),
                r.auditor(), r.accreditationRef(), r.independent(), r.completedAt() == null ? now() : r.completedAt(),
                r.findings() == null ? List.of() : r.findings(), r.reportHash(), r.authorityReportRef(), id.personId()));
    }

    @Override
    public AccountabilityService.ImpactAssessment recordImpactAssessment(SecurityIdentity id,
                                                                         ComplianceRequests.ImpactAssessmentRequest r) {
        return tx(c -> compliance.accountability.recordImpactAssessment(c, id.tenantId(), r.activityId(), r.scope(),
                r.risks() == null ? List.of() : r.risks(), r.measures() == null ? List.of() : r.measures(),
                r.residualRisk(), r.assessor(), r.independent(), r.assessedAt() == null ? now() : r.assessedAt(),
                r.authorityReportRef(), id.personId()));
    }

    // ---------------------------------------------------------------- transfers, logs, reports, jurisdiction

    @Override
    public CrossBorderTransferGuard.Decision evaluateTransfer(SecurityIdentity id, CrossBorderTransferGuard.TransferRequest r) {
        return tx(c -> compliance.transfers.evaluate(c, id.tenantId(), r, id.personId()));
    }

    @Override
    public LogArchiveService.Segment sealLogs(SecurityIdentity id, Instant from, Instant to) {
        return tx(c -> compliance.logs.seal(c, id.tenantId(), from, to == null ? now() : to, id.personId()));
    }

    @Override
    public Map<String, Object> verifyAudit(SecurityIdentity id) {
        return tx(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            AuditIntegrityVerifier.Result chains = compliance.integrity.verifyTenant(c, id.tenantId(), MAX_CHAINS);
            m.put("chains", chains);
            var latest = compliance.logs.latest(c, id.tenantId());
            m.put("latestSegment", latest.orElse(null));
            boolean segmentOk = latest.map(s -> compliance.logs.verify(c, id.tenantId(), s.segmentId())).orElse(true);
            m.put("latestSegmentVerified", segmentOk);
            m.put("ok", chains.ok() && segmentOk);
            m.put("verifiedAt", now());
            return m;
        });
    }

    @Override
    public ComplianceEvidenceReportGenerator.Report report(SecurityIdentity id, Instant from, Instant to,
                                                           boolean verifyChains) {
        return tx(c -> compliance.reports.generate(c, id.tenantId(), from, to, verifyChains, id.personId()));
    }

    @Override
    public Map<String, Object> jurisdiction(String tenantId) {
        ComplianceConfig c = cfg(tenantId);
        JurisdictionPack p = c.pack;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("homeCountry", p.homeCountry());
        m.put("timeZone", p.timeZone().getId());
        m.put("defaultLanguage", c.defaultLanguage == null ? p.defaultLanguage() : c.defaultLanguage.code());
        m.put("languages", c.enabledLanguages);
        m.put("rights", p.rights());
        m.put("authorities", p.authorities());
        m.put("contactRoles", p.contactRoles());
        m.put("limits", p.limits());
        m.put("instruments", p.instruments());
        m.put("organisation", c.organisationName);
        m.put("rightsPortalUrl", c.rightsPortalUrl);
        m.put("privacyPolicyUrl", c.privacyPolicyUrl);
        return m;
    }
}
