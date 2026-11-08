// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.api;

import org.sangam.compliance.breach.BreachIncidentService;
import org.sangam.compliance.breach.IncidentReportingService;
import org.sangam.compliance.consent.ConsentNoticeService;
import org.sangam.compliance.consent.ConsentService;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.report.ComplianceEvidenceReportGenerator;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.core.security.SecurityIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST port for WS-REST (base {@code /api/v1/compliance}). Each method is one endpoint; the controller maps
 * {@code ComplianceException.httpStatus()/code()} to problem+json, takes the tenant from the identity, wraps mutations
 * in the Idempotency-Key filter, and authorises with {@code SecurityPorts.Authorization} on resource type
 * {@code COMPLIANCE} (READ for queries, MODIFY for case work, ADMIN for configuration). The implementation delegates
 * to {@code SangamCompliance} services inside one transaction per call.
 */
public interface ComplianceApi {

    /** GET /notices/{key}?version=&lang= (public) */
    ConsentNoticeService.RenderedNotice notice(String tenantId, String key, Integer version, String language);

    /** POST /notices/{key}:publish {purposes} — ADMIN */
    ConsentNoticeService.PublishResult publishNotice(SecurityIdentity id, String key, Set<String> purposeCodes);

    /** GET /privacy-policy?lang= (public) */
    ConsentNoticeService.RenderedNotice privacyPolicy(String tenantId, String language);

    /** PUT /purposes/{code} — ADMIN */
    PurposeDefinition definePurpose(SecurityIdentity id, PurposeDefinition purpose);

    /** POST /consents — data subject (or guardian) */
    ConsentService.Consent giveConsent(SecurityIdentity id, ConsentService.GiveConsent request);

    /** POST /consents/{id}:withdraw {purposes?} */
    ConsentService.Consent withdrawConsent(SecurityIdentity id, String consentId, Set<String> purposeCodes);

    /** GET /consents?subject= */
    List<ConsentService.Consent> consents(SecurityIdentity id, String principalRef);

    /** POST /consent-managers/{cm}/artefacts — mTLS from the consent manager */
    ConsentService.Consent ingestArtefact(String tenantId, org.sangam.compliance.consent.ConsentManagerPort.ConsentArtefact artefact);

    /** POST /rights-requests (public, authenticated by the published identifier) */
    DataSubjectRightsService.RightsRequest submitRightsRequest(String tenantId, DataSubjectRightsService.Submission submission);

    /** GET /rights-requests/{id} */
    DataSubjectRightsService.RightsRequest rightsRequest(SecurityIdentity id, String requestId);

    /** POST /breaches — MODIFY */
    BreachIncidentService.Breach registerBreach(SecurityIdentity id, Map<String, String> facts, Instant awareAt, Set<String> subjects,
                                                Set<String> roots, String incidentType);

    /** GET /breaches/{id} */
    BreachIncidentService.Breach breach(SecurityIdentity id, String breachId);

    /** POST /incidents — MODIFY */
    IncidentReportingService.Incident registerIncident(SecurityIdentity id, String authority, String type, Instant occurredAt,
                                                       Instant noticedAt, List<String> systems, String description);

    /** POST /incidents/{id}:submit */
    List<String> submitIncident(SecurityIdentity id, String incidentId, String actionsTaken);

    /** POST /lawful-requests, /{id}:verify, :approve, :respond — MODIFY (maker-checker) */
    String receiveLawfulRequest(SecurityIdentity id, String authority, String basis, String reference, String scope, Instant dueAt);

    /** POST /contacts/{role} — ADMIN */
    void setContact(SecurityIdentity id, org.sangam.compliance.accountability.ContactDirectory.Contact contact);

    /** POST /evidence/audits, /evidence/impact-assessments — ADMIN */
    void recordAuditEvidence(SecurityIdentity id, String kind, Instant completedAt, String auditor, String accreditationRef,
                             boolean independent, String reportHash);

    /** POST /transfers:evaluate */
    org.sangam.compliance.transfer.CrossBorderTransferGuard.Decision evaluateTransfer(SecurityIdentity id,
                                                                                   org.sangam.compliance.transfer.CrossBorderTransferGuard.TransferRequest r);

    /** POST /logs/segments:seal, GET /logs/verify — ADMIN / READ_AUDIT */
    Map<String, Object> verifyAudit(SecurityIdentity id);

    /** GET /reports?from=&to= (JSON or text/html) — READ_AUDIT */
    ComplianceEvidenceReportGenerator.Report report(SecurityIdentity id, Instant from, Instant to, boolean verifyChains);

    /** GET /jurisdiction — the active pack (code, languages, rights, authorities, limits) for the UI */
    Map<String, Object> jurisdiction(String tenantId);
}
